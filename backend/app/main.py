from __future__ import annotations

import asyncio
import contextlib
import logging
from pathlib import Path
from typing import AsyncIterator

from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from app.api import auth, engines, health, history, jobs, settings as settings_api, share
from app.auth.security import ensure_initial_admin
from app.config import Settings, get_settings
from app.db import get_connection, run_migrations
from app.services.cleanup_service import recover_expired_leases, run_cleanup_cycle
from app.worker.runner import run_worker_loop

logger = logging.getLogger("droplink.api")


async def _periodic_cleanup_loop(settings: Settings, stop_event: asyncio.Event) -> None:
    while not stop_event.is_set():
        try:
            conn = get_connection(settings)
            try:
                run_cleanup_cycle(conn, settings)
            finally:
                conn.close()
        except Exception:
            logger.exception("Erro na rotina de limpeza periódica.")
        try:
            await asyncio.wait_for(stop_event.wait(), timeout=settings.cleanup_interval_seconds)
        except asyncio.TimeoutError:
            continue


def create_app(custom_settings: Settings | None = None) -> FastAPI:
    cfg = custom_settings or get_settings()

    @contextlib.asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        run_migrations(cfg)
        conn = get_connection(cfg)
        try:
            ensure_initial_admin(conn, cfg)
            recover_expired_leases(conn, cfg)
        finally:
            conn.close()

        stop_event = asyncio.Event()
        tasks: list[asyncio.Task[None]] = []
        tasks.append(asyncio.create_task(_periodic_cleanup_loop(cfg, stop_event)))
        if cfg.embedded_worker:
            tasks.append(asyncio.create_task(run_worker_loop(cfg, stop_event=stop_event)))

        try:
            yield
        finally:
            stop_event.set()
            for t in tasks:
                t.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)

    app = FastAPI(
        title=f"{cfg.app_name} API",
        version=cfg.app_version,
        docs_url="/api/docs" if cfg.app_env == "development" else None,
        redoc_url=None,
        lifespan=lifespan,
    )

    if custom_settings is not None:
        app.dependency_overrides[get_settings] = lambda: cfg

    app.add_middleware(
        CORSMiddleware,
        allow_origins=cfg.allowed_origins(),
        allow_credentials=True,
        allow_methods=["GET", "POST", "PATCH", "DELETE", "OPTIONS"],
        allow_headers=["Content-Type", "X-CSRF-Token", "Authorization"],
    )

    @app.middleware("http")
    async def security_headers_middleware(request: Request, call_next):
        response = await call_next(request)
        response.headers.setdefault("X-Content-Type-Options", "nosniff")
        response.headers.setdefault("X-Frame-Options", "DENY")
        response.headers.setdefault("Referrer-Policy", "no-referrer")
        response.headers.setdefault(
            "Permissions-Policy",
            "camera=(), microphone=(), geolocation=()",
        )
        response.headers.setdefault(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            "img-src 'self' data: blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self';",
        )
        if request.url.path.startswith("/api/"):
            response.headers.setdefault("Cache-Control", "no-store")
        return response

    app.include_router(health.router)
    app.include_router(auth.router)
    app.include_router(jobs.router)
    app.include_router(history.router)
    app.include_router(engines.router)
    app.include_router(settings_api.router)
    app.include_router(share.router)

    # Serve built frontend static files on same origin if directory exists
    dist_dir = cfg.resolved_frontend_dist_dir()
    if dist_dir.exists() and dist_dir.is_dir():
        assets_dir = dist_dir / "assets"
        if assets_dir.exists() and assets_dir.is_dir():
            app.mount("/assets", StaticFiles(directory=str(assets_dir)), name="frontend-assets")

        @app.get("/{full_path:path}", include_in_schema=False)
        def serve_spa(full_path: str):
            if full_path.startswith(("api/", "share-target", "healthz", "readyz")):
                raise HTTPException(status_code=404, detail="Not found")
            candidate = (dist_dir / full_path).resolve()
            if full_path and candidate.is_file():
                try:
                    candidate.relative_to(dist_dir.resolve())
                    return FileResponse(str(candidate))
                except ValueError:
                    pass
            index_file = dist_dir / "index.html"
            if index_file.is_file():
                return FileResponse(str(index_file))
            return JSONResponse({"detail": "Frontend não compilado."}, status_code=404)

    return app


app = create_app()
