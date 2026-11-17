from __future__ import annotations

from typing import Literal, Optional

from pydantic import BaseModel, Field, field_validator


JobFormat = Literal["auto", "video", "audio", "image"]
JobQuality = Literal["best", "1080p", "720p", "480p", "audio"]
JobStatus = Literal[
    "queued",
    "resolving",
    "downloading",
    "processing",
    "ready",
    "failed",
    "cancelled",
    "expired",
]
ThemeType = Literal["dark", "light"]
ExecutionMode = Literal["auto", "local", "vps"]
EngineName = Literal["yt-dlp", "gallery-dl", "cobalt"]


class LoginRequest(BaseModel):
    username: str = Field(default="admin", min_length=1, max_length=64)
    password: str = Field(min_length=1, max_length=256)


class UserResponse(BaseModel):
    id: str
    username: str
    role: str


class AuthMeResponse(BaseModel):
    authenticated: bool
    user: Optional[UserResponse] = None
    csrf_token: Optional[str] = None
    session_token: Optional[str] = None


class ParseUrlRequest(BaseModel):
    url: str = Field(min_length=1, max_length=2048)


class RouteMatchResponse(BaseModel):
    valid: bool
    platform: Optional[str] = None
    label: Optional[str] = None
    route_id: Optional[str] = None
    normalized_url: Optional[str] = None
    primary_engine: Optional[str] = None
    fallback_engines: list[str] = Field(default_factory=list)
    experimental: bool = False
    error: Optional[str] = None


class CreateJobRequest(BaseModel):
    url: str = Field(min_length=1, max_length=2048)
    format: JobFormat = "auto"
    quality: JobQuality = "best"
    idempotency_key: Optional[str] = Field(default=None, max_length=128)
    force_manual: bool = False
    status_mode: bool = False
    segment_duration: Optional[int] = Field(default=None, ge=1, le=600)
    keep_original: bool = False


JobCreateRequest = CreateJobRequest


class JobFileResponse(BaseModel):
    id: str
    filename: str
    mime_type: str
    size_bytes: int
    duration_seconds: Optional[float] = None
    is_archive: bool = False
    item_count: int = 1
    missing_items: int = 0
    is_original: bool = False
    segment_index: Optional[int] = None
    download_url: str
    expires_at: str
    expired: bool = False


class JobAttemptResponse(BaseModel):
    id: str
    attempt_number: int
    engine: str
    status: str
    error_category: Optional[str] = None
    error_code: Optional[str] = None
    error_message: Optional[str] = None
    started_at: str
    ended_at: Optional[str] = None


class JobResponse(BaseModel):
    id: str
    original_url: str
    normalized_url: str
    platform: str
    route_id: str
    title: Optional[str] = None
    format: str
    quality: str
    status_mode: bool = False
    segment_duration: Optional[int] = None
    keep_original: bool = False
    status: JobStatus
    phase_label: str
    current_engine: Optional[str] = None
    selected_engine: Optional[str] = None
    bytes_downloaded: int = 0
    total_bytes: Optional[int] = None
    percent: Optional[float] = None
    is_indeterminate: bool = True
    error_code: Optional[str] = None
    error_message: Optional[str] = None
    deduplicated: bool = False
    created_at: str
    updated_at: str
    started_at: Optional[str] = None
    completed_at: Optional[str] = None
    expires_at: Optional[str] = None
    files: list[JobFileResponse] = Field(default_factory=list)
    attempts: list[JobAttemptResponse] = Field(default_factory=list)


class HistoryItemResponse(BaseModel):
    id: str
    original_url: str
    normalized_url: str
    platform: str
    title: Optional[str] = None
    format: str
    quality: str
    status_mode: bool = False
    segment_duration: Optional[int] = None
    keep_original: bool = False
    status: JobStatus
    phase_label: str
    selected_engine: Optional[str] = None
    error_message: Optional[str] = None
    created_at: str
    completed_at: Optional[str] = None
    file: Optional[JobFileResponse] = None
    has_active_file: bool = False
    file_expired: bool = False


class HistoryListResponse(BaseModel):
    items: list[HistoryItemResponse]
    total: int
    page: int
    page_size: int
    has_more: bool


class CobaltUpdaterStatus(BaseModel):
    maintenance: bool = False
    state: str = "unknown"
    current_digest: Optional[str] = None
    current_version: Optional[str] = None
    previous_digest: Optional[str] = None
    rejected_digest: Optional[str] = None
    rejected_until: Optional[str] = None
    last_check_at: Optional[str] = None
    last_update_at: Optional[str] = None
    last_rollback_at: Optional[str] = None
    last_error: Optional[str] = None


class EngineStatusResponse(BaseModel):
    engine: str
    installed_version: Optional[str] = None
    health: str
    circuit_state: str
    consecutive_failures: int = 0
    circuit_retry_at: Optional[str] = None
    last_success_at: Optional[str] = None
    last_error_at: Optional[str] = None
    last_error_message: Optional[str] = None
    last_checked_at: Optional[str] = None
    maintenance_mode: bool = False
    maintenance_note: Optional[str] = None
    updater_status: Optional[CobaltUpdaterStatus] = None


class EnginesOverviewResponse(BaseModel):
    engines: list[EngineStatusResponse]
    disclaimer: str = "Online significa processo respondendo, não garantia de que todas as plataformas funcionam."


class EngineReleaseInfo(BaseModel):
    engine: str
    installed_version: Optional[str] = None
    latest_version: Optional[str] = None
    update_available: bool = False
    release_url: Optional[str] = None
    checked_at: Optional[str] = None
    source: Optional[str] = None


class PlatformCapability(BaseModel):
    platform: str
    label: str
    local_supported: bool
    remote_supported: bool
    primary_engine: str
    fallback_engines: list[str] = Field(default_factory=list)
    media_types: list[str] = Field(default_factory=list)
    notes: Optional[str] = None


class EnginesReleasesResponse(BaseModel):
    engines: list[EngineReleaseInfo]
    capabilities: list[PlatformCapability]
    checked_at: str


class UserSettingsResponse(BaseModel):
    auto_download: bool
    default_quality: JobQuality
    data_saver: bool
    file_retention_hours: int
    theme: ThemeType
    enabled_engines: list[EngineName]
    preferred_execution_mode: ExecutionMode = "auto"
    status_mode_enabled: bool = False
    status_max_duration_seconds: int = 30
    status_keep_original: bool = False
    auto_update_engines: bool = True
    notifications_enabled: bool = True


class UpdateSettingsRequest(BaseModel):
    auto_download: Optional[bool] = None
    default_quality: Optional[JobQuality] = None
    data_saver: Optional[bool] = None
    file_retention_hours: Optional[int] = Field(default=None, ge=1, le=168)
    theme: Optional[ThemeType] = None
    enabled_engines: Optional[list[EngineName]] = None
    preferred_execution_mode: Optional[ExecutionMode] = None
    status_mode_enabled: Optional[bool] = None
    status_max_duration_seconds: Optional[int] = Field(default=None, ge=5, le=300)
    status_keep_original: Optional[bool] = None
    auto_update_engines: Optional[bool] = None
    notifications_enabled: Optional[bool] = None

    @field_validator("enabled_engines")
    @classmethod
    def check_at_least_one_engine(cls, v: Optional[list[EngineName]]) -> Optional[list[EngineName]]:
        if v is not None and len(v) == 0:
            raise ValueError("Pelo menos um motor deve permanecer habilitado.")
        return v


SettingsPayload = UpdateSettingsRequest


class SharePendingResponse(BaseModel):
    has_pending: bool
    url: Optional[str] = None
    title: Optional[str] = None
    platform: Optional[str] = None
    error: Optional[str] = None
