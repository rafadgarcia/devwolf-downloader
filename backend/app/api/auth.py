from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status

from app.auth.deps import get_db, get_optional_session, require_csrf
from app.auth.security import (
    SessionContext,
    authenticate_user,
    create_session,
    is_same_origin,
    login_rate_limiter,
    revoke_session_by_token,
)
from app.config import Settings, get_settings
from app.models.schemas import AuthMeResponse, LoginRequest, UserResponse

router = APIRouter(prefix="/api/auth", tags=["auth"])


@router.post("/login", response_model=AuthMeResponse)
def login(
    payload: LoginRequest,
    request: Request,
    response: Response,
    settings: Settings = Depends(get_settings),
    conn=Depends(get_db),
) -> AuthMeResponse:
    origin = request.headers.get("origin") or request.headers.get("referer")
    if origin and not is_same_origin(origin, request.headers.get("host", ""), settings):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Origem não autorizada.")

    client_ip = request.client.host if request.client else "unknown"
    allowed, retry_after = login_rate_limiter.check_and_record(
        f"login:{client_ip}",
        settings.login_rate_limit_max,
        settings.login_rate_limit_window_seconds,
    )
    if not allowed:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Muitas tentativas de login. Tente novamente em instantes.",
            headers={"Retry-After": str(retry_after)},
        )

    user_row = authenticate_user(conn, payload.username, payload.password)
    if user_row is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Credenciais inválidas.",
        )

    raw_token, csrf_token, _expires_at = create_session(conn, user_row["id"], settings)
    response.set_cookie(
        key=settings.cookie_name,
        value=raw_token,
        httponly=True,
        samesite="lax",
        secure=settings.is_cookie_secure(),
        max_age=settings.session_ttl_hours * 3600,
        path="/",
    )
    return AuthMeResponse(
        authenticated=True,
        user=UserResponse(
            id=user_row["id"],
            username=user_row["username"],
            role=user_row["role"],
        ),
        csrf_token=csrf_token,
    )


@router.post("/logout")
def logout(
    request: Request,
    response: Response,
    session: SessionContext = Depends(require_csrf),
    settings: Settings = Depends(get_settings),
    conn=Depends(get_db),
) -> dict[str, bool]:
    raw_token = request.cookies.get(settings.cookie_name, "")
    revoke_session_by_token(conn, raw_token, settings)
    response.delete_cookie(key=settings.cookie_name, path="/")
    return {"ok": True}


@router.get("/me", response_model=AuthMeResponse)
def me(session: SessionContext | None = Depends(get_optional_session)) -> AuthMeResponse:
    if session is None:
        return AuthMeResponse(authenticated=False, user=None, csrf_token=None)
    return AuthMeResponse(
        authenticated=True,
        user=UserResponse(
            id=session.user_id,
            username=session.username,
            role=session.role,
        ),
        csrf_token=session.csrf_token,
    )
