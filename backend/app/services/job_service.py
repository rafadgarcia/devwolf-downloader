from __future__ import annotations

import hashlib
import json
import shutil
import sqlite3
import uuid
from datetime import timedelta
from pathlib import Path
from typing import Optional

from fastapi import HTTPException, status

from app.config import Settings
from app.db import transaction, utc_now, utc_now_iso
from app.models.schemas import (
    CreateJobRequest,
    HistoryItemResponse,
    HistoryListResponse,
    JobAttemptResponse,
    JobFileResponse,
    JobResponse,
    UpdateSettingsRequest,
    UserSettingsResponse,
)
from app.security.file_guard import ensure_within_dir
from app.security.url_guard import UrlValidationError, load_routes_config, validate_and_match_url

PHASE_LABELS: dict[str, str] = {
    "queued": "Link detectado",
    "resolving": "Procurando mídia",
    "downloading": "Baixando",
    "processing": "Preparando arquivo",
    "ready": "Pronto para baixar",
    "failed": "Falhou",
    "cancelled": "Cancelado",
    "expired": "Expirado",
}

ACTIVE_STATUSES = ("queued", "resolving", "downloading", "processing")


def compute_dedup_hash(normalized_url: str, fmt: str, quality: str) -> str:
    payload = f"{normalized_url}|{fmt}|{quality}".encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def ensure_free_disk_space(settings: Settings) -> None:
    storage_dir = settings.resolved_storage_dir()
    storage_dir.mkdir(parents=True, exist_ok=True)
    usage = shutil.disk_usage(storage_dir)
    min_bytes = settings.min_free_disk_mb * 1024 * 1024
    if usage.free < min_bytes:
        raise HTTPException(
            status_code=status.HTTP_507_INSUFFICIENT_STORAGE,
            detail="Espaço em disco insuficiente no servidor para aceitar novos downloads.",
        )


def _row_to_file_response(row: sqlite3.Row, job_id: str, now_iso: str) -> JobFileResponse:
    expired = bool(row["deleted_at"] is not None or row["expires_at"] <= now_iso)
    return JobFileResponse(
        id=row["id"],
        filename=row["filename"],
        mime_type=row["mime_type"],
        size_bytes=int(row["size_bytes"]),
        duration_seconds=row["duration_seconds"],
        is_archive=bool(row["is_archive"]),
        item_count=int(row["item_count"]),
        missing_items=int(row["missing_items"]),
        download_url=f"/api/jobs/{job_id}/files/{row['id']}",
        expires_at=row["expires_at"],
        expired=expired,
    )


def _row_to_attempt_response(row: sqlite3.Row) -> JobAttemptResponse:
    return JobAttemptResponse(
        id=row["id"],
        attempt_number=int(row["attempt_number"]),
        engine=row["engine"],
        status=row["status"],
        error_category=row["error_category"],
        error_code=row["error_code"],
        error_message=row["error_message"],
        started_at=row["started_at"],
        ended_at=row["ended_at"],
    )


def build_job_response(
    conn: sqlite3.Connection,
    job_row: sqlite3.Row,
    *,
    deduplicated: bool = False,
) -> JobResponse:
    now_iso = utc_now_iso()
    job_id = job_row["id"]
    file_rows = conn.execute(
        "SELECT * FROM files WHERE job_id = ? ORDER BY created_at ASC",
        (job_id,),
    ).fetchall()
    attempt_rows = conn.execute(
        "SELECT * FROM job_attempts WHERE job_id = ? ORDER BY attempt_number ASC",
        (job_id,),
    ).fetchall()

    return JobResponse(
        id=job_id,
        original_url=job_row["original_url"],
        normalized_url=job_row["normalized_url"],
        platform=job_row["platform"],
        route_id=job_row["route_id"],
        title=job_row["title"],
        format=job_row["format"],
        quality=job_row["quality"],
        status=job_row["status"],
        phase_label=job_row["phase_label"],
        current_engine=job_row["current_engine"],
        selected_engine=job_row["selected_engine"],
        bytes_downloaded=int(job_row["bytes_downloaded"] or 0),
        total_bytes=int(job_row["total_bytes"]) if job_row["total_bytes"] is not None else None,
        percent=float(job_row["percent"]) if job_row["percent"] is not None else None,
        is_indeterminate=bool(job_row["is_indeterminate"]),
        error_code=job_row["error_code"],
        error_message=job_row["error_message"],
        deduplicated=deduplicated,
        created_at=job_row["created_at"],
        updated_at=job_row["updated_at"],
        started_at=job_row["started_at"],
        completed_at=job_row["completed_at"],
        expires_at=job_row["expires_at"],
        files=[_row_to_file_response(fr, job_id, now_iso) for fr in file_rows],
        attempts=[_row_to_attempt_response(ar) for ar in attempt_rows],
    )


def create_job(
    conn: sqlite3.Connection,
    user_id: str,
    req: CreateJobRequest,
    settings: Settings,
) -> JobResponse:
    routes_cfg = load_routes_config(settings)
    try:
        matched = validate_and_match_url(req.url, routes_cfg, verify_dns=False)
    except UrlValidationError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    # Idempotency key check
    if req.idempotency_key:
        existing_idem = conn.execute(
            "SELECT * FROM jobs WHERE user_id = ? AND idempotency_key = ? ORDER BY created_at DESC LIMIT 1",
            (user_id, req.idempotency_key),
        ).fetchone()
        if existing_idem is not None:
            return build_job_response(conn, existing_idem, deduplicated=True)

    dedup_hash = compute_dedup_hash(matched.normalized_url, req.format, req.quality)

    # Deduplication within active jobs or recent 10-minute window unless force_manual=true
    if not req.force_manual:
        active_dup = conn.execute(
            """
            SELECT * FROM jobs
            WHERE user_id = ? AND dedup_hash = ? AND status IN ('queued', 'resolving', 'downloading', 'processing')
            ORDER BY created_at DESC LIMIT 1
            """,
            (user_id, dedup_hash),
        ).fetchone()
        if active_dup is not None:
            return build_job_response(conn, active_dup, deduplicated=True)

        cutoff_iso = (utc_now() - timedelta(seconds=settings.dedup_window_seconds)).isoformat()
        recent_dup = conn.execute(
            """
            SELECT * FROM jobs
            WHERE user_id = ? AND dedup_hash = ? AND status = 'ready' AND created_at >= ?
            ORDER BY created_at DESC LIMIT 1
            """,
            (user_id, dedup_hash, cutoff_iso),
        ).fetchone()
        if recent_dup is not None:
            # Ensure at least one file has not expired
            has_valid_file = conn.execute(
                "SELECT 1 FROM files WHERE job_id = ? AND deleted_at IS NULL AND expires_at > ? LIMIT 1",
                (recent_dup["id"], utc_now_iso()),
            ).fetchone()
            if has_valid_file is not None:
                return build_job_response(conn, recent_dup, deduplicated=True)

    # Check user queue limit
    active_count_row = conn.execute(
        "SELECT COUNT(*) AS cnt FROM jobs WHERE user_id = ? AND status IN ('queued', 'resolving', 'downloading', 'processing')",
        (user_id,),
    ).fetchone()
    if active_count_row and int(active_count_row["cnt"]) >= settings.max_queued_jobs_per_user:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Limite de downloads na fila atingido. Aguarde a conclusão dos atuais.",
        )

    ensure_free_disk_space(settings)

    job_id = str(uuid.uuid4())
    now = utc_now_iso()
    with transaction(conn):
        conn.execute(
            """
            INSERT INTO jobs (
                id, user_id, original_url, normalized_url, platform, route_id,
                format, quality, idempotency_key, dedup_hash,
                status, phase_label, current_engine, bytes_downloaded,
                is_indeterminate, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'queued', ?, ?, 0, 1, ?, ?)
            """,
            (
                job_id,
                user_id,
                req.url.strip(),
                matched.normalized_url,
                matched.route.platform,
                matched.route.id,
                req.format,
                req.quality,
                req.idempotency_key,
                dedup_hash,
                PHASE_LABELS["queued"],
                matched.route.primary_engine,
                now,
                now,
            ),
        )
    row = conn.execute("SELECT * FROM jobs WHERE id = ?", (job_id,)).fetchone()
    assert row is not None
    return build_job_response(conn, row, deduplicated=False)


def get_job_for_user(conn: sqlite3.Connection, job_id: str, user_id: str) -> JobResponse:
    row = conn.execute(
        "SELECT * FROM jobs WHERE id = ? AND user_id = ?",
        (job_id, user_id),
    ).fetchone()
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Job não encontrado.")
    return build_job_response(conn, row)


def cancel_job_for_user(conn: sqlite3.Connection, job_id: str, user_id: str) -> JobResponse:
    with transaction(conn):
        row = conn.execute(
            "SELECT * FROM jobs WHERE id = ? AND user_id = ?",
            (job_id, user_id),
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Job não encontrado.")

        now = utc_now_iso()
        if row["status"] == "queued":
            conn.execute(
                """
                UPDATE jobs
                SET status = 'cancelled',
                    phase_label = ?,
                    cancel_requested = 1,
                    completed_at = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                (PHASE_LABELS["cancelled"], now, now, job_id),
            )
        elif row["status"] in ("resolving", "downloading", "processing"):
            conn.execute(
                """
                UPDATE jobs
                SET cancel_requested = 1,
                    updated_at = ?
                WHERE id = ?
                """,
                (now, job_id),
            )

    updated = conn.execute("SELECT * FROM jobs WHERE id = ?", (job_id,)).fetchone()
    assert updated is not None
    return build_job_response(conn, updated)


def get_history_for_user(
    conn: sqlite3.Connection,
    user_id: str,
    *,
    q: Optional[str] = None,
    platform: Optional[str] = None,
    status_filter: Optional[str] = None,
    page: int = 1,
    page_size: int = 20,
) -> HistoryListResponse:
    where = ["user_id = ?"]
    params: list[object] = [user_id]
    if platform:
        where.append("platform = ?")
        params.append(platform.lower())
    if status_filter:
        where.append("status = ?")
        params.append(status_filter.lower())
    if q:
        like_q = f"%{q.strip()}%"
        where.append("(COALESCE(title, '') LIKE ? OR normalized_url LIKE ? OR platform LIKE ?)")
        params.extend([like_q, like_q, like_q])

    where_sql = " AND ".join(where)
    total_row = conn.execute(f"SELECT COUNT(*) AS cnt FROM jobs WHERE {where_sql}", params).fetchone()
    total = int(total_row["cnt"]) if total_row else 0

    offset = (page - 1) * page_size
    rows = conn.execute(
        f"SELECT * FROM jobs WHERE {where_sql} ORDER BY created_at DESC LIMIT ? OFFSET ?",
        [*params, page_size, offset],
    ).fetchall()

    now_iso = utc_now_iso()
    items: list[HistoryItemResponse] = []
    for r in rows:
        file_row = conn.execute(
            "SELECT * FROM files WHERE job_id = ? ORDER BY created_at ASC LIMIT 1",
            (r["id"],),
        ).fetchone()
        file_resp = _row_to_file_response(file_row, r["id"], now_iso) if file_row else None
        has_active = bool(file_resp is not None and not file_resp.expired)
        file_expired = bool(file_resp is not None and file_resp.expired)
        items.append(
            HistoryItemResponse(
                id=r["id"],
                original_url=r["original_url"],
                normalized_url=r["normalized_url"],
                platform=r["platform"],
                title=r["title"],
                format=r["format"],
                quality=r["quality"],
                status=r["status"],
                phase_label=r["phase_label"],
                selected_engine=r["selected_engine"],
                error_message=r["error_message"],
                created_at=r["created_at"],
                completed_at=r["completed_at"],
                file=file_resp,
                has_active_file=has_active,
                file_expired=file_expired,
            )
        )

    return HistoryListResponse(
        items=items,
        total=total,
        page=page,
        page_size=page_size,
        has_more=(offset + len(items)) < total,
    )


def delete_history_item(
    conn: sqlite3.Connection,
    job_id: str,
    user_id: str,
    settings: Settings,
) -> None:
    row = conn.execute(
        "SELECT * FROM jobs WHERE id = ? AND user_id = ?",
        (job_id, user_id),
    ).fetchone()
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Registro não encontrado.")

    if row["status"] in ("resolving", "downloading", "processing") and row["lease_expires_at"] and row["lease_expires_at"] > utc_now_iso():
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Cancele o download em andamento antes de excluí-lo.",
        )

    file_rows = conn.execute(
        "SELECT storage_path FROM files WHERE job_id = ? AND user_id = ?",
        (job_id, user_id),
    ).fetchall()

    storage_root = settings.resolved_storage_dir()
    for fr in file_rows:
        try:
            target = ensure_within_dir(storage_root, storage_root / fr["storage_path"])
            if target.exists():
                target.unlink()
        except Exception:
            pass

    job_storage_dir = storage_root / job_id
    if job_storage_dir.exists() and job_storage_dir.is_dir():
        shutil.rmtree(job_storage_dir, ignore_errors=True)

    with transaction(conn):
        conn.execute("DELETE FROM jobs WHERE id = ? AND user_id = ?", (job_id, user_id))


def get_user_settings(conn: sqlite3.Connection, user_id: str, settings: Settings) -> UserSettingsResponse:
    row = conn.execute("SELECT * FROM settings WHERE user_id = ?", (user_id,)).fetchone()
    if row is None:
        now = utc_now_iso()
        with transaction(conn):
            conn.execute(
                """
                INSERT INTO settings (user_id, auto_download, default_quality, data_saver, file_retention_hours, theme, enabled_engines, updated_at)
                VALUES (?, 1, 'best', 0, ?, 'dark', '["yt-dlp","gallery-dl","cobalt"]', ?)
                """,
                (user_id, settings.file_retention_hours, now),
            )
        row = conn.execute("SELECT * FROM settings WHERE user_id = ?", (user_id,)).fetchone()
        assert row is not None

    try:
        engines = json.loads(row["enabled_engines"])
    except Exception:
        engines = ["yt-dlp", "gallery-dl", "cobalt"]

    return UserSettingsResponse(
        auto_download=bool(row["auto_download"]),
        default_quality=row["default_quality"],
        data_saver=bool(row["data_saver"]),
        file_retention_hours=int(row["file_retention_hours"]),
        theme=row["theme"],
        enabled_engines=engines,
    )


def update_user_settings(
    conn: sqlite3.Connection,
    user_id: str,
    req: UpdateSettingsRequest,
    settings: Settings,
) -> UserSettingsResponse:
    current = get_user_settings(conn, user_id, settings)
    auto_dl = req.auto_download if req.auto_download is not None else current.auto_download
    def_qual = req.default_quality if req.default_quality is not None else current.default_quality
    data_saver = req.data_saver if req.data_saver is not None else current.data_saver
    retention = req.file_retention_hours if req.file_retention_hours is not None else current.file_retention_hours
    theme = req.theme if req.theme is not None else current.theme
    engines = req.enabled_engines if req.enabled_engines is not None else current.enabled_engines

    with transaction(conn):
        conn.execute(
            """
            UPDATE settings
            SET auto_download = ?,
                default_quality = ?,
                data_saver = ?,
                file_retention_hours = ?,
                theme = ?,
                enabled_engines = ?,
                updated_at = ?
            WHERE user_id = ?
            """,
            (
                int(auto_dl),
                def_qual,
                int(data_saver),
                retention,
                theme,
                json.dumps(engines),
                utc_now_iso(),
                user_id,
            ),
        )
    return get_user_settings(conn, user_id, settings)
