from __future__ import annotations

from typing import Optional

from fastapi import APIRouter, Depends, Query

from app.auth.deps import get_current_session, get_db, require_csrf
from app.auth.security import SessionContext
from app.config import Settings, get_settings
from app.models.schemas import HistoryListResponse
from app.services.job_service import delete_history_item, get_history_for_user

router = APIRouter(prefix="/api/history", tags=["history"])


@router.get("", response_model=HistoryListResponse)
def list_history(
    q: Optional[str] = Query(default=None, max_length=200),
    platform: Optional[str] = Query(default=None, max_length=40),
    status_filter: Optional[str] = Query(default=None, alias="status", max_length=40),
    page: int = Query(default=1, ge=1, le=1000),
    page_size: int = Query(default=20, ge=1, le=100),
    session: SessionContext = Depends(get_current_session),
    conn=Depends(get_db),
) -> HistoryListResponse:
    return get_history_for_user(
        conn,
        session.user_id,
        q=q,
        platform=platform,
        status_filter=status_filter,
        page=page,
        page_size=page_size,
    )


@router.delete("/{job_id}")
def remove_history_item(
    job_id: str,
    session: SessionContext = Depends(require_csrf),
    settings: Settings = Depends(get_settings),
    conn=Depends(get_db),
) -> dict[str, bool]:
    delete_history_item(conn, job_id, session.user_id, settings)
    return {"ok": True}
