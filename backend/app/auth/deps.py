from __future__ import annotations

import hmac
from typing import Iterator

from fastapi import Depends, HTTPException, Request, status

from app.auth.security import SessionContext, get_session_by_token, is_same_origin
from app.config import Settings, get_settings
from app.db import get_connection


def get_db(settings: Settings = Depends(get_settings)):
    conn = get_connection(settings)
    try:
        yield conn
    finally:
        conn.close()


def get_optional_session(
    request: Request,
    settings: Settings = Depends(get_settings),
    conn=Depends(get_db),
) -> SessionContext | None:
    raw_token = request.cookies.get(settings.cookie_name)
    if not raw_token:
        return None
    return get_session_by_token(conn, raw_token, settings)


def get_current_session(
    session: SessionContext | None = Depends(get_optional_session),
) -> SessionContext:
    if session is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Autenticação necessária.",
        )
    return session


def require_csrf(
    request: Request,
    session: SessionContext = Depends(get_current_session),
    settings: Settings = Depends(get_settings),
) -> SessionContext:
    origin = request.headers.get("origin") or request.headers.get("referer")
    host = request.headers.get("host", "")
    if origin and not is_same_origin(origin, host, settings):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Origem da requisição não autorizada.",
        )

    provided_csrf = request.headers.get("x-csrf-token", "")
    if not provided_csrf or not hmac.compare_digest(provided_csrf, session.csrf_token):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Token CSRF ausente ou inválido.",
        )
    return session
