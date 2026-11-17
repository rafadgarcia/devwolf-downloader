from __future__ import annotations

import asyncio
import logging
import os
import shutil
import signal
import sqlite3
import time
import uuid
from datetime import timedelta
from pathlib import Path
from typing import Optional

from app.config import Settings, get_settings
from app.db import get_connection, run_migrations, transaction, utc_now, utc_now_iso
from app.engines.base import (
    CancellationToken,
    EngineError,
    EngineRequest,
    ErrorCategory,
    ProgressUpdate,
)
from app.engines.router import EngineRouter
from app.security.file_guard import ensure_within_dir
from app.security.url_guard import load_routes_config, validate_and_match_url
from app.services.cleanup_service import recover_expired_leases, run_cleanup_cycle
from app.services.job_service import PHASE_LABELS, get_user_settings

logger = logging.getLogger("droplink.worker")


def acquire_next_job(
    conn: sqlite3.Connection,
    worker_id: str,
    lease_seconds: int,
) -> Optional[sqlite3.Row]:
    now = utc_now()
    now_iso = now.isoformat()
    lease_expires = (now + timedelta(seconds=lease_seconds)).isoformat()

    with transaction(conn):
        row = conn.execute(
            """
            SELECT * FROM jobs
            WHERE status = 'queued' AND cancel_requested = 0
            ORDER BY created_at ASC
            LIMIT 1
            """
        ).fetchone()
        if row is None:
            return None

        conn.execute(
            """
            UPDATE jobs
            SET status = 'resolving',
                phase_label = ?,
                lease_owner = ?,
                lease_expires_at = ?,
                attempt_count = attempt_count + 1,
                started_at = COALESCE(started_at, ?),
                updated_at = ?
            WHERE id = ? AND status = 'queued'
            """,
            (PHASE_LABELS["resolving"], worker_id, lease_expires, now_iso, now_iso, row["id"]),
        )
        updated = conn.execute("SELECT * FROM jobs WHERE id = ?", (row["id"],)).fetchone()
        return updated


async def process_job(
    conn: sqlite3.Connection,
    job_row: sqlite3.Row,
    settings: Settings,
    router: EngineRouter,
    worker_id: str,
) -> None:
    job_id = job_row["id"]
    user_id = job_row["user_id"]
    tmp_root = settings.resolved_tmp_dir()
    job_tmp = tmp_root / job_id
    storage_root = settings.resolved_storage_dir()
    job_storage = storage_root / job_id

    routes_cfg = load_routes_config(settings)
    user_prefs = get_user_settings(conn, user_id, settings)

    try:
        matched = validate_and_match_url(job_row["normalized_url"], routes_cfg, verify_dns=False)
    except Exception as exc:
        now_iso = utc_now_iso()
        with transaction(conn):
            conn.execute(
                """
                UPDATE jobs
                SET status = 'failed',
                    phase_label = ?,
                    error_code = 'invalid_url',
                    error_message = ?,
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    completed_at = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                (PHASE_LABELS["failed"], str(exc)[:240], now_iso, now_iso, job_id),
            )
        return

    engine_request = EngineRequest(
        job_id=job_id,
        url=matched.normalized_url,
        platform=matched.route.platform,
        route_id=matched.route.id,
        format=job_row["format"],
        quality=job_row["quality"],
        max_size_bytes=settings.max_job_size_mb * 1024 * 1024,
        max_duration_seconds=settings.max_job_duration_seconds,
        max_carousel_items=min(settings.max_carousel_items, routes_cfg.max_carousel_items),
        engine_constraints=matched.route.engine_constraints,
    )

    last_db_progress_ts = 0.0
    last_cancel_check_ts = 0.0
    cancelled_flag = False
    attempt_ids: dict[int, str] = {}

    def _check_cancel() -> bool:
        nonlocal last_cancel_check_ts, cancelled_flag
        if cancelled_flag:
            return True
        now_mono = time.monotonic()
        if now_mono - last_cancel_check_ts < 0.4:
            return False
        last_cancel_check_ts = now_mono
        try:
            r = conn.execute("SELECT cancel_requested FROM jobs WHERE id = ?", (job_id,)).fetchone()
            if r is not None and int(r["cancel_requested"]):
                cancelled_flag = True
                return True
        except Exception:
            pass
        return False

    cancellation_token = CancellationToken(check_fn=_check_cancel)

    def _on_progress(prog: ProgressUpdate) -> None:
        nonlocal last_db_progress_ts
        now_mono = time.monotonic()
        if prog.phase is None and (now_mono - last_db_progress_ts) < 0.4:
            return
        last_db_progress_ts = now_mono
        now_dt = utc_now()
        lease_exp = (now_dt + timedelta(seconds=settings.job_lease_seconds)).isoformat()
        phase = prog.phase or "downloading"
        phase_label = prog.phase_label or PHASE_LABELS.get(phase, "Baixando")
        try:
            with transaction(conn):
                conn.execute(
                    """
                    UPDATE jobs
                    SET status = ?,
                        phase_label = ?,
                        bytes_downloaded = ?,
                        total_bytes = ?,
                        percent = ?,
                        is_indeterminate = ?,
                        lease_expires_at = ?,
                        updated_at = ?
                    WHERE id = ?
                    """,
                    (
                        phase,
                        phase_label,
                        prog.bytes_downloaded,
                        prog.total_bytes,
                        prog.percent,
                        int(prog.is_indeterminate),
                        lease_exp,
                        now_dt.isoformat(),
                        job_id,
                    ),
                )
        except Exception:
            pass

    def _on_engine_start(engine_name: str, attempt_num: int) -> None:
        attempt_id = str(uuid.uuid4())
        attempt_ids[attempt_num] = attempt_id
        now_dt = utc_now()
        lease_exp = (now_dt + timedelta(seconds=settings.job_lease_seconds)).isoformat()
        with transaction(conn):
            conn.execute(
                """
                UPDATE jobs
                SET current_engine = ?,
                    status = 'resolving',
                    phase_label = ?,
                    lease_expires_at = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                (engine_name, PHASE_LABELS["resolving"], lease_exp, now_dt.isoformat(), job_id),
            )
            conn.execute(
                """
                INSERT INTO job_attempts (id, job_id, attempt_number, engine, status, started_at)
                VALUES (?, ?, ?, ?, 'running', ?)
                """,
                (attempt_id, job_id, attempt_num, engine_name, now_dt.isoformat()),
            )

    def _on_attempt_done(
        engine_name: str,
        attempt_num: int,
        status_str: str,
        err_cat: Optional[str],
        err_code: Optional[str],
        err_msg: Optional[str],
    ) -> None:
        attempt_id = attempt_ids.get(attempt_num)
        if not attempt_id:
            return
        with transaction(conn):
            conn.execute(
                """
                UPDATE job_attempts
                SET status = ?,
                    error_category = ?,
                    error_code = ?,
                    error_message = ?,
                    ended_at = ?
                WHERE id = ?
                """,
                (status_str, err_cat, err_code, err_msg[:240] if err_msg else None, utc_now_iso(), attempt_id),
            )

    try:
        result = await router.execute_with_fallback(
            conn=conn,
            matched=matched,
            request=engine_request,
            enabled_engines=list(user_prefs.enabled_engines),
            job_tmp_root=job_tmp,
            on_engine_start=_on_engine_start,
            on_attempt_done=_on_attempt_done,
            progress_callback=_on_progress,
            cancellation_token=cancellation_token,
        )

        # Move finalized files into persistent storage
        now_dt = utc_now()
        retention_hours = user_prefs.file_retention_hours or settings.file_retention_hours
        expires_at = (now_dt + timedelta(hours=retention_hours)).isoformat()
        job_storage.mkdir(parents=True, exist_ok=True)

        stored_rows: list[tuple] = []
        total_final_bytes = 0
        for f_info in result.files:
            file_id = str(uuid.uuid4())
            rel_path = f"{job_id}/{file_id}_{f_info.filename}"
            dest_path = ensure_within_dir(storage_root, storage_root / rel_path)
            dest_path.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(f_info.path), str(dest_path))
            total_final_bytes += f_info.size_bytes
            stored_rows.append(
                (
                    file_id,
                    job_id,
                    user_id,
                    f_info.filename,
                    rel_path,
                    f_info.mime_type,
                    f_info.size_bytes,
                    f_info.duration_seconds,
                    int(f_info.is_archive),
                    f_info.item_count,
                    f_info.missing_items,
                    f_info.sha256,
                    now_dt.isoformat(),
                    expires_at,
                )
            )

        with transaction(conn):
            for sr in stored_rows:
                conn.execute(
                    """
                    INSERT INTO files (
                        id, job_id, user_id, filename, storage_path, mime_type, size_bytes,
                        duration_seconds, is_archive, item_count, missing_items, sha256,
                        created_at, expires_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    sr,
                )
            conn.execute(
                """
                UPDATE jobs
                SET status = 'ready',
                    phase_label = ?,
                    title = COALESCE(?, title),
                    selected_engine = ?,
                    current_engine = ?,
                    bytes_downloaded = ?,
                    total_bytes = ?,
                    percent = 100.0,
                    is_indeterminate = 0,
                    error_code = NULL,
                    error_message = NULL,
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    completed_at = ?,
                    expires_at = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                (
                    PHASE_LABELS["ready"],
                    result.title,
                    result.engine,
                    result.engine,
                    total_final_bytes,
                    total_final_bytes,
                    now_dt.isoformat(),
                    expires_at,
                    now_dt.isoformat(),
                    job_id,
                ),
            )
    except EngineError as exc:
        now_iso = utc_now_iso()
        if exc.category == ErrorCategory.CANCELLED or _check_cancel():
            with transaction(conn):
                conn.execute(
                    """
                    UPDATE jobs
                    SET status = 'cancelled',
                        phase_label = ?,
                        error_code = 'cancelled',
                        error_message = 'Download cancelado pelo usuário.',
                        lease_owner = NULL,
                        lease_expires_at = NULL,
                        completed_at = ?,
                        updated_at = ?
                    WHERE id = ?
                    """,
                    (PHASE_LABELS["cancelled"], now_iso, now_iso, job_id),
                )
        else:
            with transaction(conn):
                conn.execute(
                    """
                    UPDATE jobs
                    SET status = 'failed',
                        phase_label = ?,
                        error_code = ?,
                        error_message = ?,
                        lease_owner = NULL,
                        lease_expires_at = NULL,
                        completed_at = ?,
                        updated_at = ?
                    WHERE id = ?
                    """,
                    (PHASE_LABELS["failed"], exc.code, exc.message[:240], now_iso, now_iso, job_id),
                )
    except Exception as exc:
        logger.exception("Unhandled error processing job %s", job_id)
        now_iso = utc_now_iso()
        with transaction(conn):
            conn.execute(
                """
                UPDATE jobs
                SET status = 'failed',
                    phase_label = ?,
                    error_code = 'internal_error',
                    error_message = ?,
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    completed_at = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                (PHASE_LABELS["failed"], f"Erro interno: {type(exc).__name__}", now_iso, now_iso, job_id),
            )
    finally:
        shutil.rmtree(job_tmp, ignore_errors=True)


async def run_worker_loop(
    settings: Settings | None = None,
    stop_event: Optional[asyncio.Event] = None,
    router: Optional[EngineRouter] = None,
) -> None:
    cfg = settings or get_settings()
    run_migrations(cfg)
    conn = get_connection(cfg)
    eng_router = router or EngineRouter(cfg)
    eng_router.breaker.ensure_engine_rows(conn)

    worker_id = f"worker-{os.getpid()}-{uuid.uuid4().hex[:8]}"
    recovered = recover_expired_leases(conn, cfg)
    if recovered:
        logger.info("Recovered %d expired job leases on worker startup", recovered)

    semaphore = asyncio.Semaphore(max(1, cfg.max_concurrent_jobs))
    active_tasks: set[asyncio.Task[None]] = set()
    last_cleanup = time.monotonic()

    async def _run_single(job_row: sqlite3.Row) -> None:
        task_conn = get_connection(cfg)
        try:
            async with semaphore:
                await process_job(task_conn, job_row, cfg, eng_router, worker_id)
        finally:
            task_conn.close()

    try:
        while stop_event is None or not stop_event.is_set():
            # Clean finished tasks
            done_tasks = {t for t in active_tasks if t.done()}
            active_tasks -= done_tasks

            if len(active_tasks) < cfg.max_concurrent_jobs:
                next_job = acquire_next_job(conn, worker_id, cfg.job_lease_seconds)
                if next_job is not None:
                    task = asyncio.create_task(_run_single(next_job))
                    active_tasks.add(task)
                    continue

            now_mono = time.monotonic()
            if now_mono - last_cleanup >= cfg.cleanup_interval_seconds:
                try:
                    run_cleanup_cycle(conn, cfg)
                except Exception:
                    logger.exception("Error during worker cleanup cycle")
                last_cleanup = now_mono

            await asyncio.sleep(cfg.worker_poll_interval_seconds)
    finally:
        if active_tasks:
            await asyncio.gather(*active_tasks, return_exceptions=True)
        conn.close()


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )
    settings = get_settings()
    stop_event = asyncio.Event()

    async def _runner() -> None:
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            try:
                loop.add_signal_handler(sig, stop_event.set)
            except NotImplementedError:
                pass
        await run_worker_loop(settings, stop_event=stop_event)

    asyncio.run(_runner())


if __name__ == "__main__":
    main()
