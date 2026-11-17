from __future__ import annotations

import asyncio
import json
from typing import AsyncIterator

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from fastapi.responses import FileResponse, StreamingResponse

from app.auth.deps import get_current_session, get_db, require_csrf
from app.auth.security import SessionContext, job_rate_limiter
from app.config import Settings, get_settings
from app.db import get_connection, utc_now_iso
from app.models.schemas import (
    CreateJobRequest,
    JobResponse,
    ParseUrlRequest,
    RouteMatchResponse,
)
from app.security.file_guard import FileSecurityError, ensure_within_dir, sanitize_filename
from app.security.url_guard import UrlValidationError, load_routes_config, validate_and_match_url
from app.services.job_service import (
    build_job_response,
    cancel_job_for_user,
    create_job,
    get_job_for_user,
)

router = APIRouter(tags=["jobs"])

_TERMINAL_JOB_STATES = {"ready", "failed", "cancelled", "expired"}


@router.post("/api/jobs/parse-url", response_model=RouteMatchResponse)
def parse_url(
    payload: ParseUrlRequest,
    _session: SessionContext = Depends(get_current_session),
    settings: Settings = Depends(get_settings),
) -> RouteMatchResponse:
    return _match_url_helper(payload.url, settings)


@router.get("/api/routes/match", response_model=RouteMatchResponse)
def match_route(
    url: str = Query(..., min_length=1, max_length=2048),
    _session: SessionContext = Depends(get_current_session),
    settings: Settings = Depends(get_settings),
) -> RouteMatchResponse:
    return _match_url_helper(url, settings)


def _match_url_helper(raw_url: str, settings: Settings) -> RouteMatchResponse:
    cfg = load_routes_config(settings)
    try:
        matched = validate_and_match_url(raw_url, cfg, verify_dns=False)
        return RouteMatchResponse(
            valid=True,
            platform=matched.route.platform,
            label=matched.route.label,
            route_id=matched.route.id,
            normalized_url=matched.normalized_url,
            primary_engine=matched.route.primary_engine,
            fallback_engines=list(matched.route.fallback_engines),
            experimental=matched.route.experimental,
        )
    except UrlValidationError as exc:
        return RouteMatchResponse(valid=False, error=str(exc))


@router.post("/api/jobs", response_model=JobResponse, status_code=status.HTTP_201_CREATED)
def submit_job(
    payload: CreateJobRequest,
    session: SessionContext = Depends(require_csrf),
    settings: Settings = Depends(get_settings),
    conn=Depends(get_db),
) -> JobResponse:
    allowed, retry_after = job_rate_limiter.check_and_record(
        f"job:{session.user_id}",
        settings.job_rate_limit_max,
        settings.job_rate_limit_window_seconds,
    )
    if not allowed:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Muitos downloads iniciados em sequência. Aguarde alguns segundos.",
            headers={"Retry-After": str(retry_after)},
        )
    return create_job(conn, session.user_id, payload, settings)


@router.get("/api/jobs/{job_id}", response_model=JobResponse)
def get_job(
    job_id: str,
    session: SessionContext = Depends(get_current_session),
    conn=Depends(get_db),
) -> JobResponse:
    return get_job_for_user(conn, job_id, session.user_id)


@router.get("/api/jobs/{job_id}/events")
async def stream_job_events(
    job_id: str,
    request: Request,
    session: SessionContext = Depends(get_current_session),
    settings: Settings = Depends(get_settings),
) -> StreamingResponse:
    # Verify existence & ownership before opening stream
    init_conn = get_connection(settings)
    try:
        get_job_for_user(init_conn, job_id, session.user_id)
    finally:
        init_conn.close()

    async def _event_stream() -> AsyncIterator[str]:
        last_signature: Optional[str] = None
        keepalive_counter = 0
        while True:
            if await request.is_disconnected():
                break

            conn = get_connection(settings)
            try:
                row = conn.execute(
                    "SELECT * FROM jobs WHERE id = ? AND user_id = ?",
                    (job_id, session.user_id),
                ).fetchone()
                if row is None:
                    yield f"event: error\ndata: {json.dumps({'detail': 'Job não encontrado.'})}\n\n"
                    break
                job_resp = build_job_response(conn, row)
            finally:
                conn.close()

            payload_json = job_resp.model_dump_json()
            sig = f"{job_resp.status}:{job_resp.phase_label}:{job_resp.current_engine}:{job_resp.bytes_downloaded}:{job_resp.total_bytes}:{len(job_resp.files)}:{len(job_resp.attempts)}"
            if sig != last_signature:
                last_signature = sig
                keepalive_counter = 0
                yield f"event: job\ndata: {payload_json}\n\n"
                if job_resp.status in _TERMINAL_JOB_STATES:
                    break
            else:
                keepalive_counter += 1
                if keepalive_counter >= 15:
                    keepalive_counter = 0
                    yield ": keepalive\n\n"

            await asyncio.sleep(0.5)

    return StreamingResponse(
        _event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache, no-transform",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@router.post("/api/jobs/{job_id}/cancel", response_model=JobResponse)
def cancel_job(
    job_id: str,
    session: SessionContext = Depends(require_csrf),
    conn=Depends(get_db),
) -> JobResponse:
    return cancel_job_for_user(conn, job_id, session.user_id)


@router.get("/api/jobs/{job_id}/files/{file_id}")
def download_job_file(
    job_id: str,
    file_id: str,
    session: SessionContext = Depends(get_current_session),
    settings: Settings = Depends(get_settings),
    conn=Depends(get_db),
) -> FileResponse:
    row = conn.execute(
        """
        SELECT f.* FROM files f
        JOIN jobs j ON j.id = f.job_id
        WHERE f.id = ? AND f.job_id = ? AND f.user_id = ? AND j.user_id = ?
        """,
        (file_id, job_id, session.user_id, session.user_id),
    ).fetchone()
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Arquivo não encontrado.")

    now_iso = utc_now_iso()
    if row["deleted_at"] is not None or row["expires_at"] <= now_iso:
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="Arquivo expirado e removido do servidor.")

    storage_root = settings.resolved_storage_dir()
    try:
        file_path = ensure_within_dir(storage_root, storage_root / row["storage_path"])
    except FileSecurityError as exc:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=str(exc)) from exc

    if not file_path.is_file():
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="Arquivo físico não está mais disponível.")

    safe_download_name = sanitize_filename(row["filename"], default_stem="droplink_media")
    return FileResponse(
        path=str(file_path),
        media_type=row["mime_type"] or "application/octet-stream",
        filename=safe_download_name,
        headers={
            "X-Content-Type-Options": "nosniff",
            "Cache-Control": "private, no-store",
        },
    )


from typing import Optional  # noqa: E402
