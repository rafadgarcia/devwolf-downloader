from __future__ import annotations

from fastapi import APIRouter, Depends

from app.auth.deps import get_current_session, get_db
from app.auth.security import SessionContext
from app.config import Settings, get_settings
from app.db import transaction, utc_now_iso
from app.engines.cobalt_adapter import CobaltEngine, read_cobalt_updater_status
from app.engines.gallerydl_adapter import GalleryDlEngine
from app.engines.router import EngineCircuitBreaker
from app.engines.ytdlp_adapter import YtDlpEngine
from app.models.schemas import EngineStatusResponse, EnginesOverviewResponse

router = APIRouter(prefix="/api/engines", tags=["engines"])


@router.get("", response_model=EnginesOverviewResponse)
async def get_engines_status(
    _session: SessionContext = Depends(get_current_session),
    settings: Settings = Depends(get_settings),
    conn=Depends(get_db),
) -> EnginesOverviewResponse:
    breaker = EngineCircuitBreaker(settings)
    breaker.ensure_engine_rows(conn)

    adapters = {
        "yt-dlp": YtDlpEngine(settings),
        "gallery-dl": GalleryDlEngine(settings),
        "cobalt": CobaltEngine(settings),
    }

    cobalt_updater = read_cobalt_updater_status(settings)
    now_iso = utc_now_iso()
    responses: list[EngineStatusResponse] = []

    for eng_name, adapter in adapters.items():
        health_info = await adapter.health()
        row = conn.execute("SELECT * FROM engine_status WHERE engine = ?", (eng_name,)).fetchone()

        circuit_state = row["circuit_state"] if row else "closed"
        consecutive_failures = int(row["consecutive_failures"]) if row else 0
        circuit_retry_at = row["circuit_retry_at"] if row else None

        if health_info.maintenance_mode:
            health_label = "maintenance"
        elif circuit_state == "open":
            health_label = "degraded"
        elif health_info.healthy:
            health_label = "online"
        else:
            health_label = "offline"

        with transaction(conn):
            conn.execute(
                """
                UPDATE engine_status
                SET installed_version = COALESCE(?, installed_version),
                    health = ?,
                    last_checked_at = ?,
                    maintenance_mode = ?,
                    maintenance_note = ?,
                    updated_at = ?
                WHERE engine = ?
                """,
                (
                    health_info.installed_version,
                    health_label,
                    now_iso,
                    int(health_info.maintenance_mode),
                    health_info.maintenance_note or health_info.detail,
                    now_iso,
                    eng_name,
                ),
            )

        updated_row = conn.execute("SELECT * FROM engine_status WHERE engine = ?", (eng_name,)).fetchone()
        responses.append(
            EngineStatusResponse(
                engine=eng_name,
                installed_version=updated_row["installed_version"] if updated_row else health_info.installed_version,
                health=health_label,
                circuit_state=circuit_state,
                consecutive_failures=consecutive_failures,
                circuit_retry_at=circuit_retry_at,
                last_success_at=updated_row["last_success_at"] if updated_row else None,
                last_error_at=updated_row["last_error_at"] if updated_row else None,
                last_error_message=updated_row["last_error_message"] if updated_row else None,
                last_checked_at=now_iso,
                maintenance_mode=health_info.maintenance_mode,
                maintenance_note=health_info.maintenance_note or health_info.detail,
                updater_status=cobalt_updater if eng_name == "cobalt" else None,
            )
        )

    return EnginesOverviewResponse(engines=responses)
