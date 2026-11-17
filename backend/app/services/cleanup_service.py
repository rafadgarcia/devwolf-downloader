from __future__ import annotations

import logging
import shutil
import sqlite3
from datetime import timedelta

from app.config import Settings
from app.db import transaction, utc_now, utc_now_iso
from app.security.file_guard import ensure_within_dir
from app.services.job_service import PHASE_LABELS

logger = logging.getLogger("droplink.cleanup")


def recover_expired_leases(conn: sqlite3.Connection, settings: Settings) -> int:
    """Recover jobs whose worker lease expired (e.g., after container restart or crash)."""
    now_iso = utc_now_iso()
    recovered = 0
    tmp_root = settings.resolved_tmp_dir()

    with transaction(conn):
        rows = conn.execute(
            """
            SELECT id, attempt_count, cancel_requested
            FROM jobs
            WHERE status IN ('resolving', 'downloading', 'processing')
              AND (lease_expires_at IS NULL OR lease_expires_at <= ?)
            """,
            (now_iso,),
        ).fetchall()

        for row in rows:
            job_id = row["id"]
            if int(row["cancel_requested"]):
                conn.execute(
                    """
                    UPDATE jobs
                    SET status = 'cancelled',
                        phase_label = ?,
                        lease_owner = NULL,
                        lease_expires_at = NULL,
                        completed_at = ?,
                        updated_at = ?
                    WHERE id = ?
                    """,
                    (PHASE_LABELS["cancelled"], now_iso, now_iso, job_id),
                )
            elif int(row["attempt_count"]) >= 3:
                conn.execute(
                    """
                    UPDATE jobs
                    SET status = 'failed',
                        phase_label = ?,
                        error_code = 'worker_lease_expired',
                        error_message = 'Processamento interrompido repetidamente antes de concluir.',
                        lease_owner = NULL,
                        lease_expires_at = NULL,
                        completed_at = ?,
                        updated_at = ?
                    WHERE id = ?
                    """,
                    (PHASE_LABELS["failed"], now_iso, now_iso, job_id),
                )
            else:
                conn.execute(
                    """
                    UPDATE jobs
                    SET status = 'queued',
                        phase_label = ?,
                        lease_owner = NULL,
                        lease_expires_at = NULL,
                        bytes_downloaded = 0,
                        total_bytes = NULL,
                        percent = NULL,
                        is_indeterminate = 1,
                        updated_at = ?
                    WHERE id = ?
                    """,
                    (PHASE_LABELS["queued"], now_iso, job_id),
                )
            recovered += 1
            shutil.rmtree(tmp_root / job_id, ignore_errors=True)

    return recovered


def run_cleanup_cycle(conn: sqlite3.Connection, settings: Settings) -> dict[str, int]:
    """Delete expired files, old history, expired sessions/tokens, and orphaned temp workspaces."""
    now_dt = utc_now()
    now_iso = now_dt.isoformat()
    storage_root = settings.resolved_storage_dir()
    tmp_root = settings.resolved_tmp_dir()

    expired_files_removed = 0
    history_deleted = 0
    tmp_dirs_removed = 0

    # 1. Remove expired media files unless their owning job has an active lease
    file_rows = conn.execute(
        """
        SELECT f.id, f.job_id, f.storage_path
        FROM files f
        JOIN jobs j ON j.id = f.job_id
        WHERE f.deleted_at IS NULL
          AND f.expires_at <= ?
          AND NOT (
              j.status IN ('resolving', 'downloading', 'processing')
              AND j.lease_expires_at IS NOT NULL
              AND j.lease_expires_at > ?
          )
        """,
        (now_iso, now_iso),
    ).fetchall()

    for fr in file_rows:
        try:
            target = ensure_within_dir(storage_root, storage_root / fr["storage_path"])
            if target.exists():
                target.unlink()
        except Exception:
            pass
        job_dir = storage_root / fr["job_id"]
        if job_dir.exists() and not any(job_dir.iterdir()):
            shutil.rmtree(job_dir, ignore_errors=True)
        with transaction(conn):
            conn.execute(
                "UPDATE files SET deleted_at = ? WHERE id = ?",
                (now_iso, fr["id"]),
            )
            conn.execute(
                """
                UPDATE jobs
                SET status = 'expired',
                    phase_label = ?,
                    updated_at = ?
                WHERE id = ? AND status = 'ready'
                """,
                (PHASE_LABELS["expired"], now_iso, fr["job_id"]),
            )
        expired_files_removed += 1

    # 2. Remove history older than HISTORY_RETENTION_DAYS
    history_cutoff = (now_dt - timedelta(days=settings.history_retention_days)).isoformat()
    old_jobs = conn.execute(
        """
        SELECT id FROM jobs
        WHERE created_at <= ?
          AND status IN ('ready', 'failed', 'cancelled', 'expired')
        """,
        (history_cutoff,),
    ).fetchall()
    for oj in old_jobs:
        shutil.rmtree(storage_root / oj["id"], ignore_errors=True)
        with transaction(conn):
            conn.execute("DELETE FROM jobs WHERE id = ?", (oj["id"],))
        history_deleted += 1

    # 3. Clean expired sessions & share_tokens
    with transaction(conn):
        conn.execute("DELETE FROM sessions WHERE expires_at <= ?", (now_iso,))
        conn.execute("DELETE FROM share_tokens WHERE expires_at <= ?", (now_iso,))

    # 4. Clean orphaned temp directories not belonging to an active leased job
    if tmp_root.exists():
        active_job_rows = conn.execute(
            "SELECT id FROM jobs WHERE status IN ('resolving', 'downloading', 'processing') AND lease_expires_at > ?",
            (now_iso,),
        ).fetchall()
        active_ids = {r["id"] for r in active_job_rows}
        for child in tmp_root.iterdir():
            if child.is_dir() and child.name not in active_ids:
                shutil.rmtree(child, ignore_errors=True)
                tmp_dirs_removed += 1

    return {
        "expired_files_removed": expired_files_removed,
        "history_deleted": history_deleted,
        "tmp_dirs_removed": tmp_dirs_removed,
    }
