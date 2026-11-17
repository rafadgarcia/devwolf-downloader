from __future__ import annotations

import shutil
import sqlite3
import uuid
from datetime import timedelta
from pathlib import Path
from typing import Callable, Optional

from app.config import Settings, get_settings
from app.db import transaction, utc_now, utc_now_iso
from app.engines.base import (
    BaseEngine,
    CancellationToken,
    EngineError,
    EngineRequest,
    EngineResult,
    ErrorCategory,
    ProgressCallback,
    ProgressUpdate,
)
from app.engines.cobalt_adapter import CobaltEngine, read_cobalt_updater_status
from app.engines.gallerydl_adapter import GalleryDlEngine
from app.engines.ytdlp_adapter import YtDlpEngine
from app.security.url_guard import MatchedRoute


class EngineCircuitBreaker:
    """SQLite-backed per-engine circuit breaker and platform rate-limit cooldown tracker."""

    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()

    def ensure_engine_rows(self, conn: sqlite3.Connection) -> None:
        now = utc_now_iso()
        for eng in ("yt-dlp", "gallery-dl", "cobalt"):
            conn.execute(
                """
                INSERT OR IGNORE INTO engine_status (engine, health, circuit_state, consecutive_failures, updated_at)
                VALUES (?, 'unknown', 'closed', 0, ?)
                """,
                (eng, now),
            )

    def can_use_engine(self, conn: sqlite3.Connection, engine_name: str) -> tuple[bool, Optional[str]]:
        self.ensure_engine_rows(conn)
        row = conn.execute(
            "SELECT circuit_state, circuit_retry_at FROM engine_status WHERE engine = ?",
            (engine_name,),
        ).fetchone()
        if row is None:
            return True, None
        state = row["circuit_state"]
        retry_at = row["circuit_retry_at"]
        if state == "open" and retry_at:
            now_iso = utc_now_iso()
            if now_iso < retry_at:
                return False, f"Circuit breaker aberto até {retry_at}"
            # Move to half-open to allow a single probe attempt
            with transaction(conn):
                conn.execute(
                    "UPDATE engine_status SET circuit_state = 'half_open', updated_at = ? WHERE engine = ?",
                    (now_iso, engine_name),
                )
        return True, None

    def record_success(self, conn: sqlite3.Connection, engine_name: str, version: Optional[str]) -> None:
        self.ensure_engine_rows(conn)
        now = utc_now_iso()
        with transaction(conn):
            conn.execute(
                """
                UPDATE engine_status
                SET installed_version = COALESCE(?, installed_version),
                    health = 'online',
                    circuit_state = 'closed',
                    consecutive_failures = 0,
                    circuit_opened_at = NULL,
                    circuit_retry_at = NULL,
                    last_success_at = ?,
                    last_checked_at = ?,
                    updated_at = ?
                WHERE engine = ?
                """,
                (version, now, now, now, engine_name),
            )

    def record_failure(
        self,
        conn: sqlite3.Connection,
        engine_name: str,
        *,
        is_infra_failure: bool,
        error_message: str,
    ) -> None:
        self.ensure_engine_rows(conn)
        now_dt = utc_now()
        now_iso = now_dt.isoformat()
        sanitized_msg = error_message[:240]

        with transaction(conn):
            row = conn.execute(
                "SELECT consecutive_failures, circuit_state FROM engine_status WHERE engine = ?",
                (engine_name,),
            ).fetchone()
            prev_failures = int(row["consecutive_failures"]) if row else 0

            if is_infra_failure:
                new_failures = prev_failures + 1
                threshold = self.settings.circuit_breaker_threshold
                if new_failures >= threshold:
                    retry_at = (now_dt + timedelta(seconds=self.settings.circuit_breaker_cooldown_seconds)).isoformat()
                    conn.execute(
                        """
                        UPDATE engine_status
                        SET health = 'degraded',
                            circuit_state = 'open',
                            consecutive_failures = ?,
                            circuit_opened_at = ?,
                            circuit_retry_at = ?,
                            last_error_at = ?,
                            last_error_message = ?,
                            updated_at = ?
                        WHERE engine = ?
                        """,
                        (new_failures, now_iso, retry_at, now_iso, sanitized_msg, now_iso, engine_name),
                    )
                else:
                    conn.execute(
                        """
                        UPDATE engine_status
                        SET consecutive_failures = ?,
                            last_error_at = ?,
                            last_error_message = ?,
                            updated_at = ?
                        WHERE engine = ?
                        """,
                        (new_failures, now_iso, sanitized_msg, now_iso, engine_name),
                    )
            else:
                # Non-infra failure (e.g. removed media or unsupported format) must NOT trip the circuit breaker
                conn.execute(
                    """
                    UPDATE engine_status
                    SET last_error_at = ?,
                        last_error_message = ?,
                        updated_at = ?
                    WHERE engine = ?
                    """,
                    (now_iso, sanitized_msg, now_iso, engine_name),
                )

    def check_platform_cooldown(self, conn: sqlite3.Connection, platform: str) -> Optional[str]:
        row = conn.execute(
            "SELECT retry_after_at, reason FROM platform_cooldowns WHERE platform = ?",
            (platform,),
        ).fetchone()
        if row is None:
            return None
        now_iso = utc_now_iso()
        if now_iso < row["retry_after_at"]:
            return row["retry_after_at"]
        with transaction(conn):
            conn.execute("DELETE FROM platform_cooldowns WHERE platform = ?", (platform,))
        return None

    def set_platform_cooldown(
        self,
        conn: sqlite3.Connection,
        platform: str,
        retry_after_seconds: int,
        reason: str,
    ) -> None:
        now_dt = utc_now()
        retry_at = (now_dt + timedelta(seconds=max(10, retry_after_seconds))).isoformat()
        with transaction(conn):
            conn.execute(
                """
                INSERT INTO platform_cooldowns (platform, retry_after_at, reason, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(platform) DO UPDATE SET
                    retry_after_at = excluded.retry_after_at,
                    reason = excluded.reason,
                    updated_at = excluded.updated_at
                """,
                (platform, retry_at, reason[:200], now_dt.isoformat()),
            )


class EngineRouter:
    """Executes primary engine + ordered fallbacks while enforcing circuit breakers and error policies."""

    def __init__(
        self,
        settings: Settings | None = None,
        engines: Optional[dict[str, BaseEngine]] = None,
        circuit_breaker: Optional[EngineCircuitBreaker] = None,
    ) -> None:
        self.settings = settings or get_settings()
        self.engines: dict[str, BaseEngine] = engines or {
            "yt-dlp": YtDlpEngine(self.settings),
            "gallery-dl": GalleryDlEngine(self.settings),
            "cobalt": CobaltEngine(self.settings),
        }
        self.breaker = circuit_breaker or EngineCircuitBreaker(self.settings)

    def candidate_engines(
        self,
        matched: MatchedRoute,
        enabled_engines: list[str],
    ) -> list[str]:
        ordered = [matched.route.primary_engine, *matched.route.fallback_engines]
        seen: set[str] = set()
        result: list[str] = []
        enabled_set = set(enabled_engines)
        for name in ordered:
            if name in seen or name not in self.engines or name not in enabled_set:
                continue
            seen.add(name)
            result.append(name)
        return result

    async def execute_with_fallback(
        self,
        *,
        conn: sqlite3.Connection,
        matched: MatchedRoute,
        request: EngineRequest,
        enabled_engines: list[str],
        job_tmp_root: Path,
        on_engine_start: Callable[[str, int], None],
        on_attempt_done: Callable[[str, int, str, Optional[str], Optional[str], Optional[str]], None],
        progress_callback: ProgressCallback,
        cancellation_token: CancellationToken,
    ) -> EngineResult:
        cooldown_until = self.breaker.check_platform_cooldown(conn, request.platform)
        if cooldown_until is not None:
            raise EngineError(
                f"Plataforma em cooldown por limite de requisições até {cooldown_until}.",
                category=ErrorCategory.RATE_LIMIT,
                code="platform_cooldown",
            )

        candidates = self.candidate_engines(matched, enabled_engines)
        if not candidates:
            raise EngineError(
                "Nenhum motor compatível habilitado para esta plataforma.",
                category=ErrorCategory.TERMINAL,
                code="no_engines_enabled",
            )

        last_error: Optional[EngineError] = None
        attempt_num = 0

        for engine_name in candidates:
            cancellation_token.raise_if_cancelled()
            engine = self.engines[engine_name]

            if not engine.supports(request):
                continue

            if engine_name == "cobalt":
                updater = read_cobalt_updater_status(self.settings)
                if updater and updater.maintenance:
                    continue

            can_use, _reason = self.breaker.can_use_engine(conn, engine_name)
            if not can_use:
                continue

            attempt_num += 1
            attempt_workspace = job_tmp_root / f"attempt_{attempt_num}_{engine_name}"
            attempt_workspace.mkdir(parents=True, exist_ok=True)
            on_engine_start(engine_name, attempt_num)

            try:
                progress_callback(
                    ProgressUpdate(
                        bytes_downloaded=0,
                        total_bytes=None,
                        percent=None,
                        is_indeterminate=True,
                        phase="resolving",
                        phase_label="Procurando mídia",
                    )
                )
                result = await engine.download(
                    request,
                    attempt_workspace,
                    progress_callback,
                    cancellation_token,
                )
                self.breaker.record_success(conn, engine_name, result.engine_version)
                on_attempt_done(engine_name, attempt_num, "succeeded", None, None, None)
                return result
            except EngineError as exc:
                last_error = exc
                # Clean up failed attempt workspace partials
                if exc.category != ErrorCategory.CANCELLED:
                    shutil.rmtree(attempt_workspace, ignore_errors=True)

                on_attempt_done(
                    engine_name,
                    attempt_num,
                    "cancelled" if exc.category == ErrorCategory.CANCELLED else "failed",
                    exc.category.value,
                    exc.code,
                    exc.message,
                )

                if exc.category == ErrorCategory.CANCELLED:
                    raise

                if exc.category == ErrorCategory.RATE_LIMIT:
                    self.breaker.set_platform_cooldown(
                        conn,
                        request.platform,
                        exc.retry_after_seconds or 120,
                        exc.message,
                    )
                    # Do NOT switch engines to evade rate limits per spec section 7.4
                    raise

                if exc.category == ErrorCategory.TERMINAL:
                    # Do NOT fallback on private media, auth required, CAPTCHA, deleted post
                    raise

                # Recoverable / Unsupported -> record failure & proceed to next fallback
                self.breaker.record_failure(
                    conn,
                    engine_name,
                    is_infra_failure=exc.is_infra_failure,
                    error_message=exc.message,
                )
                continue
            except Exception as exc:
                shutil.rmtree(attempt_workspace, ignore_errors=True)
                err = EngineError(
                    f"Erro inesperado no motor {engine_name}: {type(exc).__name__}",
                    category=ErrorCategory.RECOVERABLE,
                    code="unexpected_engine_error",
                    is_infra_failure=True,
                )
                last_error = err
                on_attempt_done(
                    engine_name,
                    attempt_num,
                    "failed",
                    err.category.value,
                    err.code,
                    err.message,
                )
                self.breaker.record_failure(
                    conn,
                    engine_name,
                    is_infra_failure=True,
                    error_message=err.message,
                )
                continue

        if last_error is not None:
            raise last_error

        raise EngineError(
            "Todos os motores compatíveis estão indisponíveis ou com circuito aberto no momento.",
            category=ErrorCategory.RECOVERABLE,
            code="all_engines_unavailable",
        )
