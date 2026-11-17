from __future__ import annotations

import secrets
from datetime import timedelta
from typing import Optional

from fastapi import APIRouter, Depends, Form, Request, Response, status
from fastapi.responses import RedirectResponse

from app.auth.deps import get_current_session, get_db
from app.auth.security import SessionContext
from app.config import Settings, get_settings
from app.db import transaction, utc_now
from app.models.schemas import SharePendingResponse
from app.security.url_guard import (
    UrlValidationError,
    extract_first_supported_url,
    load_routes_config,
    validate_and_match_url,
)

router = APIRouter(tags=["share"])

SHARE_COOKIE_NAME = "droplink_share_token"


@router.post("/share-target")
@router.post("/api/share-target")
def receive_share_target(
    title: Optional[str] = Form(default=None),
    text: Optional[str] = Form(default=None),
    url: Optional[str] = Form(default=None),
    settings: Settings = Depends(get_settings),
    conn=Depends(get_db),
) -> RedirectResponse:
    """Handle Android PWA Web Share Target POST safely without creating jobs pre-auth or leaking URLs in logs/query."""
    routes_cfg = load_routes_config(settings)
    extracted_url: Optional[str] = None

    for candidate in (url, text):
        if not candidate:
            continue
        try:
            matched = validate_and_match_url(candidate, routes_cfg, verify_dns=False)
            extracted_url = matched.normalized_url
            break
        except UrlValidationError:
            extracted = extract_first_supported_url(candidate, routes_cfg)
            if extracted:
                extracted_url = extracted
                break

    token = secrets.token_urlsafe(24)
    now = utc_now()
    expires = now + timedelta(minutes=10)
    safe_title = (title or "").strip()[:160] or None

    with transaction(conn):
        conn.execute(
            """
            INSERT INTO share_tokens (token, url, title, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            (token, extracted_url or "", safe_title, now.isoformat(), expires.isoformat()),
        )

    redirect_resp = RedirectResponse(url="/?shared=1", status_code=status.HTTP_303_SEE_OTHER)
    redirect_resp.set_cookie(
        key=SHARE_COOKIE_NAME,
        value=token,
        httponly=True,
        samesite="lax",
        secure=settings.is_cookie_secure(),
        max_age=600,
        path="/",
    )
    return redirect_resp


@router.get("/api/share-target/pending", response_model=SharePendingResponse)
def consume_pending_share(
    request: Request,
    response: Response,
    _session: SessionContext = Depends(get_current_session),
    settings: Settings = Depends(get_settings),
    conn=Depends(get_db),
) -> SharePendingResponse:
    token = request.cookies.get(SHARE_COOKIE_NAME)
    if not token:
        return SharePendingResponse(has_pending=False)

    response.delete_cookie(key=SHARE_COOKIE_NAME, path="/")

    row = conn.execute(
        "SELECT * FROM share_tokens WHERE token = ? AND consumed_at IS NULL",
        (token,),
    ).fetchone()
    if row is None:
        return SharePendingResponse(has_pending=False)

    now_iso = utc_now().isoformat()
    with transaction(conn):
        conn.execute("UPDATE share_tokens SET consumed_at = ? WHERE token = ?", (now_iso, token))

    if row["expires_at"] <= now_iso:
        return SharePendingResponse(has_pending=False, error="O link compartilhado expirou. Compartilhe novamente.")

    raw_url = row["url"] or ""
    if not raw_url:
        return SharePendingResponse(
            has_pending=True,
            url=None,
            title=row["title"],
            error="Nenhum link de plataforma suportada encontrado no compartilhamento.",
        )

    routes_cfg = load_routes_config(settings)
    try:
        matched = validate_and_match_url(raw_url, routes_cfg, verify_dns=False)
        return SharePendingResponse(
            has_pending=True,
            url=matched.normalized_url,
            title=row["title"],
            platform=matched.route.platform,
        )
    except UrlValidationError as exc:
        return SharePendingResponse(
            has_pending=True,
            url=None,
            title=row["title"],
            error=str(exc),
        )
