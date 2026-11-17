from __future__ import annotations

from fastapi import APIRouter, Depends

from app.auth.deps import get_current_session, get_db, require_csrf
from app.auth.security import SessionContext
from app.config import Settings, get_settings
from app.models.schemas import UpdateSettingsRequest, UserSettingsResponse
from app.services.job_service import get_user_settings, update_user_settings

router = APIRouter(prefix="/api/settings", tags=["settings"])


@router.get("", response_model=UserSettingsResponse)
def read_settings(
    session: SessionContext = Depends(get_current_session),
    settings: Settings = Depends(get_settings),
    conn=Depends(get_db),
) -> UserSettingsResponse:
    return get_user_settings(conn, session.user_id, settings)


@router.patch("", response_model=UserSettingsResponse)
def patch_settings(
    payload: UpdateSettingsRequest,
    session: SessionContext = Depends(require_csrf),
    settings: Settings = Depends(get_settings),
    conn=Depends(get_db),
) -> UserSettingsResponse:
    return update_user_settings(conn, session.user_id, payload, settings)
