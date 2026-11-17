from __future__ import annotations

import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterator

from app.config import Settings, get_settings


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def utc_now_iso() -> str:
    return utc_now().isoformat()


def _configure_connection(conn: sqlite3.Connection) -> None:
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL;")
    conn.execute("PRAGMA synchronous=NORMAL;")
    conn.execute("PRAGMA foreign_keys=ON;")
    conn.execute("PRAGMA busy_timeout=5000;")


def get_connection(settings: Settings | None = None) -> sqlite3.Connection:
    cfg = settings or get_settings()
    db_path = cfg.resolved_database_path()
    db_path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(
        str(db_path),
        timeout=30.0,
        isolation_level=None,
        check_same_thread=False,
    )
    _configure_connection(conn)
    return conn


@contextmanager
def transaction(conn: sqlite3.Connection) -> Iterator[sqlite3.Connection]:
    conn.execute("BEGIN IMMEDIATE;")
    try:
        yield conn
        conn.execute("COMMIT;")
    except Exception:
        conn.execute("ROLLBACK;")
        raise


def _migrations_dir() -> Path:
    return Path(__file__).resolve().parent.parent / "migrations"


def run_migrations(settings: Settings | None = None) -> list[str]:
    cfg = settings or get_settings()
    conn = get_connection(cfg)
    applied: list[str] = []
    try:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS schema_migrations (
                version INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                applied_at TEXT NOT NULL
            );
            """
        )
        rows = conn.execute("SELECT version FROM schema_migrations").fetchall()
        existing_versions = {int(row["version"]) for row in rows}

        mig_dir = _migrations_dir()
        files = sorted(mig_dir.glob("*.sql"))
        for sql_file in files:
            prefix = sql_file.stem.split("_", 1)[0]
            try:
                version = int(prefix)
            except ValueError:
                continue
            if version in existing_versions:
                continue
            sql = sql_file.read_text(encoding="utf-8")
            with transaction(conn):
                # executescript issues an implicit commit, so execute statements sequentially
                # inside our explicit BEGIN IMMEDIATE transaction.
                statements = [s.strip() for s in sql.split(";") if s.strip()]
                for stmt in statements:
                    conn.execute(stmt)
                conn.execute(
                    "INSERT INTO schema_migrations (version, name, applied_at) VALUES (?, ?, ?)",
                    (version, sql_file.name, utc_now_iso()),
                )
            applied.append(sql_file.name)
    finally:
        conn.close()
    return applied
