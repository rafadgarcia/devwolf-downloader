from __future__ import annotations

import shutil
from fastapi import APIRouter, Depends, Response, status

from app.config import Settings, get_settings
from app.db import get_connection

router = APIRouter(tags=["health"])


@router.get("/healthz")
def healthz() -> dict[str, str]:
    return {"status": "ok"}


@router.get("/readyz")
def readyz(response: Response, settings: Settings = Depends(get_settings)) -> dict[str, str]:
    try:
        conn = get_connection(settings)
        try:
            conn.execute("SELECT 1").fetchone()
        finally:
            conn.close()
        storage_dir = settings.resolved_storage_dir()
        storage_dir.mkdir(parents=True, exist_ok=True)
        shutil.disk_usage(storage_dir)
        return {"status": "ready"}
    except Exception:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return {"status": "not_ready"}
