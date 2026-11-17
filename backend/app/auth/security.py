from __future__ import annotations

import hashlib
import hmac
import logging
import secrets
import sqlite3
import threading
import time
import uuid
from dataclasses import dataclass
from datetime import timedelta
from pathlib import Path
from typing import Optional
from urllib.parse import urlsplit

from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError

from app.config import Settings
from app.db import transaction, utc_now, utc_now_iso

logger = logging.getLogger("droplink.auth")

_PASSWORD_HASHER = PasswordHasher()


@dataclass(frozen=True)
class SessionContext:
    session_id: str
    user_id: str
    username: str
    role: str
    csrf_token: str
    expires_at: str


class SlidingWindowRateLimiter:
    """In-memory thread-safe sliding window rate limiter."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._buckets: dict[str, list[float]] = {}

    def check_and_record(self, key: str, max_requests: int, window_seconds: int) -> tuple[bool, int]:
        now = time.monotonic()
        cutoff = now - window_seconds
        with self._lock:
            timestamps = [ts for ts in self._buckets.get(key, []) if ts > cutoff]
            if len(timestamps) >= max_requests:
                retry_after = max(1, int(timestamps[0] + window_seconds - now))
                self._buckets[key] = timestamps
                return False, retry_after
            timestamps.append(now)
            self._buckets[key] = timestamps
            return True, 0

    def clear(self) -> None:
        with self._lock:
            self._buckets.clear()


login_rate_limiter = SlidingWindowRateLimiter()
job_rate_limiter = SlidingWindowRateLimiter()


def get_or_create_session_secret(settings: Settings) -> str:
    if settings.session_secret:
        return settings.session_secret
    storage_dir = settings.resolved_storage_dir()
    storage_dir.mkdir(parents=True, exist_ok=True)
    secret_file = storage_dir / ".session_secret"
    if secret_file.exists():
        val = secret_file.read_text(encoding="utf-8").strip()
        if val:
            return val
    generated = secrets.token_hex(32)
    secret_file.write_text(generated, encoding="utf-8")
    try:
        secret_file.chmod(0o600)
    except OSError:
        pass
    return generated


def hash_session_token(raw_token: str, secret: str) -> str:
    return hmac.new(secret.encode("utf-8"), raw_token.encode("utf-8"), hashlib.sha256).hexdigest()


def hash_password(password: str) -> str:
    return _PASSWORD_HASHER.hash(password)


def verify_password(password_hash: str, plain_password: str) -> bool:
    try:
        return _PASSWORD_HASHER.verify(password_hash, plain_password)
    except VerifyMismatchError:
        return False
    except Exception:
        return False


def ensure_initial_admin(conn: sqlite3.Connection, settings: Settings) -> None:
    """Create the initial personal admin account on first startup without a hardcoded public password."""
    existing = conn.execute("SELECT id, username FROM users LIMIT 1").fetchone()
    if existing is not None:
        return

    raw_password = settings.admin_password
    generated_setup = False
    if not raw_password:
        raw_password = secrets.token_urlsafe(18)
        generated_setup = True

    user_id = str(uuid.uuid4())
    now = utc_now_iso()
    pw_hash = hash_password(raw_password)
    with transaction(conn):
        conn.execute(
            """
            INSERT INTO users (id, username, password_hash, role, is_active, created_at, updated_at)
            VALUES (?, ?, ?, 'admin', 1, ?, ?)
            """,
            (user_id, settings.admin_username, pw_hash, now, now),
        )
        conn.execute(
            """
            INSERT INTO settings (user_id, auto_download, default_quality, data_saver, file_retention_hours, theme, enabled_engines, updated_at)
            VALUES (?, 1, 'best', 0, ?, 'dark', '["yt-dlp","gallery-dl","cobalt"]', ?)
            """,
            (user_id, settings.file_retention_hours, now),
        )

    if generated_setup:
        storage_dir = settings.resolved_storage_dir()
        storage_dir.mkdir(parents=True, exist_ok=True)
        setup_path = storage_dir / ".initial_setup_token"
        setup_path.write_text(
            f"username={settings.admin_username}\ninitial_password={raw_password}\n",
            encoding="utf-8",
        )
        try:
            setup_path.chmod(0o600)
        except OSError:
            pass
        logger.warning(
            "ADMIN_PASSWORD não definida. Usuário '%s' criado com senha inicial aleatória salva em %s",
            settings.admin_username,
            setup_path,
        )


def authenticate_user(conn: sqlite3.Connection, username: str, password: str) -> Optional[sqlite3.Row]:
    row = conn.execute(
        "SELECT id, username, password_hash, role, is_active FROM users WHERE username = ?",
        (username.strip(),),
    ).fetchone()
    if row is None or not int(row["is_active"]):
        return None
    if not verify_password(row["password_hash"], password):
        return None
    if _PASSWORD_HASHER.check_needs_rehash(row["password_hash"]):
        with transaction(conn):
            conn.execute(
                "UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?",
                (hash_password(password), utc_now_iso(), row["id"]),
            )
    return row


def create_session(conn: sqlite3.Connection, user_id: str, settings: Settings) -> tuple[str, str, str]:
    raw_token = secrets.token_urlsafe(32)
    csrf_token = secrets.token_urlsafe(24)
    secret = get_or_create_session_secret(settings)
    token_hash = hash_session_token(raw_token, secret)
    now = utc_now()
    expires = now + timedelta(hours=settings.session_ttl_hours)
    session_id = str(uuid.uuid4())
    with transaction(conn):
        conn.execute(
            """
            INSERT INTO sessions (id, user_id, token_hash, csrf_token, expires_at, created_at, last_seen_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (session_id, user_id, token_hash, csrf_token, expires.isoformat(), now.isoformat(), now.isoformat()),
        )
    return raw_token, csrf_token, expires.isoformat()


def get_session_by_token(
    conn: sqlite3.Connection,
    raw_token: str,
    settings: Settings,
) -> Optional[SessionContext]:
    if not raw_token:
        return None
    secret = get_or_create_session_secret(settings)
    token_hash = hash_session_token(raw_token, secret)
    row = conn.execute(
        """
        SELECT s.id AS session_id, s.user_id, s.csrf_token, s.expires_at, u.username, u.role, u.is_active
        FROM sessions s
        JOIN users u ON u.id = s.user_id
        WHERE s.token_hash = ?
        """,
        (token_hash,),
    ).fetchone()
    if row is None or not int(row["is_active"]):
        return None

    now = utc_now()
    if row["expires_at"] <= now.isoformat():
        with transaction(conn):
            conn.execute("DELETE FROM sessions WHERE id = ?", (row["session_id"],))
        return None

    conn.execute(
        "UPDATE sessions SET last_seen_at = ? WHERE id = ?",
        (now.isoformat(), row["session_id"]),
    )
    return SessionContext(
        session_id=row["session_id"],
        user_id=row["user_id"],
        username=row["username"],
        role=row["role"],
        csrf_token=row["csrf_token"],
        expires_at=row["expires_at"],
    )


def revoke_session_by_token(conn: sqlite3.Connection, raw_token: str, settings: Settings) -> None:
    if not raw_token:
        return
    secret = get_or_create_session_secret(settings)
    token_hash = hash_session_token(raw_token, secret)
    with transaction(conn):
        conn.execute("DELETE FROM sessions WHERE token_hash = ?", (token_hash,))


def is_same_origin(origin_or_referer: Optional[str], request_host: str, settings: Settings) -> bool:
    if not origin_or_referer:
        return True
    parsed = urlsplit(origin_or_referer)
    if not parsed.scheme or not parsed.netloc:
        return False
    origin = f"{parsed.scheme.lower()}://{parsed.netloc.lower()}"
    allowed = {o.lower() for o in settings.allowed_origins()}
    if request_host:
        allowed.add(f"http://{request_host.lower()}")
        allowed.add(f"https://{request_host.lower()}")
    return origin in allowed
