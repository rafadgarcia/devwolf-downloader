from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path
from typing import Optional

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


def _default_repo_root() -> Path:
    # backend/app/config.py -> backend -> repo root
    here = Path(__file__).resolve()
    backend_dir = here.parent.parent
    if (backend_dir.parent / "config").exists() or (backend_dir.parent / "DropLink-PROJETO.md").exists():
        return backend_dir.parent
    return backend_dir


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    app_name: str = "DropLink"
    app_env: str = Field(default="development", alias="APP_ENV")
    app_url: str = Field(default="http://localhost:8000", alias="APP_URL")
    cors_origins: str = Field(default="", alias="CORS_ORIGINS")

    # Auth & Security
    admin_username: str = Field(default="admin", alias="ADMIN_USERNAME")
    admin_password: Optional[str] = Field(default=None, alias="ADMIN_PASSWORD")
    session_secret: Optional[str] = Field(default=None, alias="SESSION_SECRET")
    session_ttl_hours: int = Field(default=168, alias="SESSION_TTL_HOURS")
    cookie_secure: Optional[bool] = Field(default=None, alias="COOKIE_SECURE")
    cookie_name: str = Field(default="droplink_session", alias="COOKIE_NAME")
    login_rate_limit_max: int = Field(default=10, alias="LOGIN_RATE_LIMIT_MAX")
    login_rate_limit_window_seconds: int = Field(default=300, alias="LOGIN_RATE_LIMIT_WINDOW_SECONDS")
    job_rate_limit_max: int = Field(default=30, alias="JOB_RATE_LIMIT_MAX")
    job_rate_limit_window_seconds: int = Field(default=60, alias="JOB_RATE_LIMIT_WINDOW_SECONDS")

    # Paths
    database_path: str = Field(default="", alias="DATABASE_PATH")
    storage_dir: str = Field(default="", alias="STORAGE_DIR")
    tmp_dir: str = Field(default="", alias="TMP_DIR")
    routes_config_path: str = Field(default="", alias="ROUTES_CONFIG_PATH")
    frontend_dist_dir: str = Field(default="", alias="FRONTEND_DIST_DIR")
    cobalt_status_file: str = Field(default="", alias="COBALT_STATUS_FILE")

    # Cobalt
    cobalt_api_url: str = Field(default="", alias="COBALT_API_URL")
    cobalt_api_key: str = Field(default="", alias="COBALT_API_KEY")

    # Limits & Worker
    max_concurrent_jobs: int = Field(default=2, alias="MAX_CONCURRENT_JOBS")
    max_queued_jobs_per_user: int = Field(default=20, alias="MAX_QUEUED_JOBS_PER_USER")
    max_job_size_mb: int = Field(default=500, alias="MAX_JOB_SIZE_MB")
    max_job_duration_seconds: int = Field(default=600, alias="MAX_JOB_DURATION_SECONDS")
    max_carousel_items: int = Field(default=50, alias="MAX_CAROUSEL_ITEMS")
    file_retention_hours: int = Field(default=24, alias="FILE_RETENTION_HOURS")
    history_retention_days: int = Field(default=30, alias="HISTORY_RETENTION_DAYS")
    min_free_disk_mb: int = Field(default=2048, alias="MIN_FREE_DISK_MB")
    dedup_window_seconds: int = Field(default=600, alias="DEDUP_WINDOW_SECONDS")
    job_lease_seconds: int = Field(default=120, alias="JOB_LEASE_SECONDS")
    cleanup_interval_seconds: int = Field(default=300, alias="CLEANUP_INTERVAL_SECONDS")

    # Circuit breaker
    circuit_breaker_threshold: int = Field(default=3, alias="CIRCUIT_BREAKER_THRESHOLD")
    circuit_breaker_cooldown_seconds: int = Field(default=300, alias="CIRCUIT_BREAKER_COOLDOWN_SECONDS")

    # Worker mode
    embedded_worker: bool = Field(default=False, alias="EMBEDDED_WORKER")
    worker_poll_interval_seconds: float = Field(default=1.0, alias="WORKER_POLL_INTERVAL_SECONDS")

    # Executables override
    ytdlp_bin: str = Field(default="yt-dlp", alias="YTDLP_BIN")
    gallerydl_bin: str = Field(default="gallery-dl", alias="GALLERYDL_BIN")
    ffmpeg_bin: str = Field(default="ffmpeg", alias="FFMPEG_BIN")

    def resolved_database_path(self) -> Path:
        if self.database_path:
            return Path(self.database_path).resolve()
        return (_default_repo_root() / "data" / "droplink.db").resolve()

    def resolved_storage_dir(self) -> Path:
        if self.storage_dir:
            return Path(self.storage_dir).resolve()
        return (_default_repo_root() / "storage").resolve()

    def resolved_tmp_dir(self) -> Path:
        if self.tmp_dir:
            return Path(self.tmp_dir).resolve()
        return (_default_repo_root() / "tmp").resolve()

    def resolved_routes_config_path(self) -> Path:
        if self.routes_config_path:
            return Path(self.routes_config_path).resolve()
        root = _default_repo_root()
        candidate = root / "config" / "routes.yaml"
        if candidate.exists():
            return candidate
        return (Path(__file__).resolve().parent.parent / "config" / "routes.yaml").resolve()

    def resolved_frontend_dist_dir(self) -> Path:
        if self.frontend_dist_dir:
            return Path(self.frontend_dist_dir).resolve()
        return (_default_repo_root() / "frontend" / "dist").resolve()

    def resolved_cobalt_status_file(self) -> Optional[Path]:
        if self.cobalt_status_file:
            return Path(self.cobalt_status_file).resolve()
        default_candidate = _default_repo_root() / "data" / "cobalt-status.json"
        if default_candidate.exists():
            return default_candidate
        return None

    def is_cookie_secure(self) -> bool:
        if self.cookie_secure is not None:
            return self.cookie_secure
        return self.app_url.lower().startswith("https://")

    def allowed_origins(self) -> list[str]:
        origins = [self.app_url.rstrip("/")]
        if self.cors_origins:
            for item in self.cors_origins.split(","):
                cleaned = item.strip().rstrip("/")
                if cleaned and cleaned not in origins:
                    origins.append(cleaned)
        return origins


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()


def reset_settings_cache() -> None:
    get_settings.cache_clear()
