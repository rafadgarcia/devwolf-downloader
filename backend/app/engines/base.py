from __future__ import annotations

import threading
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any, Callable, Optional


class ErrorCategory(str, Enum):
    RECOVERABLE = "recoverable"
    TERMINAL = "terminal"
    RATE_LIMIT = "rate_limit"
    UNSUPPORTED = "unsupported"
    CANCELLED = "cancelled"


class EngineError(Exception):
    """Structured error raised by a download engine adapter."""

    def __init__(
        self,
        message: str,
        *,
        category: ErrorCategory = ErrorCategory.RECOVERABLE,
        code: str = "engine_error",
        is_infra_failure: bool = False,
        retry_after_seconds: Optional[int] = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.category = category
        self.code = code
        self.is_infra_failure = is_infra_failure
        self.retry_after_seconds = retry_after_seconds


class CancellationToken:
    """Thread-safe cancellation signal checked by download adapters."""

    def __init__(self, check_fn: Optional[Callable[[], bool]] = None) -> None:
        self._event = threading.Event()
        self._check_fn = check_fn

    def cancel(self) -> None:
        self._event.set()

    def is_cancelled(self) -> bool:
        if self._event.is_set():
            return True
        if self._check_fn is not None and self._check_fn():
            self._event.set()
            return True
        return False

    def raise_if_cancelled(self) -> None:
        if self.is_cancelled():
            raise EngineError(
                "Download cancelado pelo usuário.",
                category=ErrorCategory.CANCELLED,
                code="cancelled",
            )


@dataclass(frozen=True)
class ProgressUpdate:
    bytes_downloaded: int
    total_bytes: Optional[int]
    percent: Optional[float]
    is_indeterminate: bool
    phase: Optional[str] = None
    phase_label: Optional[str] = None


ProgressCallback = Callable[[ProgressUpdate], None]


@dataclass(frozen=True)
class DownloadedFileInfo:
    path: Path
    filename: str
    mime_type: str
    size_bytes: int
    sha256: str
    duration_seconds: Optional[float] = None
    is_archive: bool = False
    item_count: int = 1
    missing_items: int = 0
    is_original: bool = False
    segment_index: Optional[int] = None


@dataclass(frozen=True)
class EngineRequest:
    job_id: str
    url: str
    platform: str
    route_id: str
    format: str
    quality: str
    max_size_bytes: int
    max_duration_seconds: int
    max_carousel_items: int
    engine_constraints: dict[str, dict[str, Any]] = field(default_factory=dict)


@dataclass(frozen=True)
class EngineResult:
    engine: str
    engine_version: Optional[str]
    files: list[DownloadedFileInfo]
    title: Optional[str] = None
    duration_seconds: Optional[float] = None


@dataclass(frozen=True)
class EngineHealth:
    engine: str
    installed_version: Optional[str]
    healthy: bool
    detail: Optional[str] = None
    maintenance_mode: bool = False
    maintenance_note: Optional[str] = None


class BaseEngine(ABC):
    name: str = ""

    @abstractmethod
    def supports(self, request: EngineRequest) -> bool:
        """Return True if this engine can attempt the given request."""

    @abstractmethod
    async def version(self) -> Optional[str]:
        """Return installed engine version string or None if unavailable."""

    @abstractmethod
    async def health(self) -> EngineHealth:
        """Check whether the engine binary or remote service is responsive."""

    @abstractmethod
    async def download(
        self,
        request: EngineRequest,
        workspace: Path,
        progress_callback: ProgressCallback,
        cancellation_token: CancellationToken,
    ) -> EngineResult:
        """Execute download into isolated workspace and return normalized result."""
