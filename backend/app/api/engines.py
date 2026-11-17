from __future__ import annotations

import asyncio
import time
from typing import Optional

import httpx
from fastapi import APIRouter, Depends, Query

from app.auth.deps import get_current_session, get_db
from app.auth.security import SessionContext
from app.config import Settings, get_settings
from app.db import transaction, utc_now_iso
from app.engines.cobalt_adapter import CobaltEngine, read_cobalt_updater_status
from app.engines.gallerydl_adapter import GalleryDlEngine
from app.engines.router import EngineCircuitBreaker
from app.engines.ytdlp_adapter import YtDlpEngine
from app.models.schemas import (
    EngineReleaseInfo,
    EngineStatusResponse,
    EnginesOverviewResponse,
    EnginesReleasesResponse,
    PlatformCapability,
)
from app.security.url_guard import load_routes_config

router = APIRouter(prefix="/api/engines", tags=["engines"])

_RELEASE_CACHE_TTL_SECONDS = 900.0
_release_cache: dict[str, tuple[float, Optional[str]]] = {}


def _normalize_ver(ver: Optional[str]) -> tuple[int, ...]:
    if not ver:
        return ()
    parts: list[int] = []
    for token in ver.strip().lstrip("v").split("."):
        digits = "".join(ch for ch in token if ch.isdigit())
        if digits:
            parts.append(int(digits))
        else:
            break
    return tuple(parts)


def _is_newer_version(installed: Optional[str], latest: Optional[str]) -> bool:
    if not installed or not latest:
        return False
    inst_tuple = _normalize_ver(installed)
    lat_tuple = _normalize_ver(latest)
    if inst_tuple and lat_tuple:
        return lat_tuple > inst_tuple
    return latest.strip() != installed.strip()


async def _fetch_pypi_latest_version(package_name: str, *, force_refresh: bool = False) -> Optional[str]:
    now = time.monotonic()
    cached = _release_cache.get(package_name)
    if not force_refresh and cached and (now - cached[0]) < _RELEASE_CACHE_TTL_SECONDS:
        return cached[1]

    latest: Optional[str] = None
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(2.5, connect=1.5)) as client:
            resp = await client.get(
                f"https://pypi.org/pypi/{package_name}/json",
                headers={"Accept": "application/json", "User-Agent": "DevwolfDownloader/2.0"},
            )
            if resp.status_code == 200:
                data = resp.json()
                info = data.get("info") if isinstance(data, dict) else None
                if isinstance(info, dict) and info.get("version"):
                    latest = str(info["version"])
    except Exception:
        latest = cached[1] if cached else None

    _release_cache[package_name] = (now, latest)
    return latest


def _build_platform_capabilities(settings: Settings) -> list[PlatformCapability]:
    routes_cfg = load_routes_config(settings)
    platform_order = ["instagram", "facebook", "tiktok", "youtube", "twitter", "reddit", "pinterest"]
    platform_labels = {
        "instagram": "Instagram (Reels, Posts e Stories)",
        "facebook": "Facebook (Reels, Vídeos e fb.watch)",
        "tiktok": "TikTok",
        "youtube": "YouTube & Shorts",
        "twitter": "X / Twitter",
        "reddit": "Reddit",
        "pinterest": "Pinterest",
    }
    local_ytdlp_platforms = {"instagram", "facebook", "tiktok", "youtube", "twitter", "reddit", "pinterest"}

    grouped: dict[str, dict] = {}
    for rule in routes_cfg.routes:
        entry = grouped.setdefault(
            rule.platform,
            {
                "primary": rule.primary_engine,
                "fallbacks": list(rule.fallback_engines),
                "media_types": list(rule.media_types),
            },
        )
        for fb in rule.fallback_engines:
            if fb not in entry["fallbacks"] and fb != entry["primary"]:
                entry["fallbacks"].append(fb)
        for mt in rule.media_types:
            if mt not in entry["media_types"]:
                entry["media_types"].append(mt)

    capabilities: list[PlatformCapability] = []
    for plat in platform_order:
        info = grouped.get(plat)
        if not info:
            continue
        notes = None
        if plat in {"instagram", "twitter", "pinterest"}:
            notes = "Vídeos suportados localmente via yt-dlp Android; carrosséis/fotos usam VPS (gallery-dl/Cobalt)."
        elif plat == "tiktok":
            notes = "Prioriza Cobalt na VPS (sem marca d'água) com fallback para yt-dlp local/VPS."
        capabilities.append(
            PlatformCapability(
                platform=plat,
                label=platform_labels.get(plat, plat.title()),
                local_supported=plat in local_ytdlp_platforms,
                remote_supported=True,
                primary_engine=info["primary"],
                fallback_engines=info["fallbacks"],
                media_types=info["media_types"],
                notes=notes,
            )
        )
    return capabilities


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


@router.get("/releases", response_model=EnginesReleasesResponse)
async def get_engines_releases(
    refresh: bool = Query(default=False),
    _session: SessionContext = Depends(get_current_session),
    settings: Settings = Depends(get_settings),
) -> EnginesReleasesResponse:
    yt_adapter = YtDlpEngine(settings)
    gdl_adapter = GalleryDlEngine(settings)
    cobalt_adapter = CobaltEngine(settings)

    yt_ver, gdl_ver, cobalt_ver, yt_latest, gdl_latest = await asyncio.gather(
        yt_adapter.version(),
        gdl_adapter.version(),
        cobalt_adapter.version(),
        _fetch_pypi_latest_version("yt-dlp", force_refresh=refresh),
        _fetch_pypi_latest_version("gallery-dl", force_refresh=refresh),
    )

    now_iso = utc_now_iso()
    cobalt_updater = read_cobalt_updater_status(settings)
    cobalt_version = cobalt_ver or (cobalt_updater.current_version if cobalt_updater else None)

    releases = [
        EngineReleaseInfo(
            engine="yt-dlp",
            installed_version=yt_ver,
            latest_version=yt_latest,
            update_available=_is_newer_version(yt_ver, yt_latest),
            release_url="https://github.com/yt-dlp/yt-dlp/releases",
            checked_at=now_iso,
            source="pypi",
        ),
        EngineReleaseInfo(
            engine="gallery-dl",
            installed_version=gdl_ver,
            latest_version=gdl_latest,
            update_available=_is_newer_version(gdl_ver, gdl_latest),
            release_url="https://github.com/mikf/gallery-dl/releases",
            checked_at=now_iso,
            source="pypi",
        ),
        EngineReleaseInfo(
            engine="cobalt",
            installed_version=cobalt_version,
            latest_version=cobalt_updater.current_version if cobalt_updater else None,
            update_available=False,
            release_url="https://github.com/imputnet/cobalt/pkgs/container/cobalt",
            checked_at=(cobalt_updater.last_check_at if cobalt_updater else None) or now_iso,
            source="cobalt-updater",
        ),
    ]

    return EnginesReleasesResponse(
        engines=releases,
        capabilities=_build_platform_capabilities(settings),
        checked_at=now_iso,
    )
