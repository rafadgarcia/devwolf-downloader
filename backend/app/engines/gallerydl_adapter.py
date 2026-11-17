from __future__ import annotations

import asyncio
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Optional

from app.config import Settings, get_settings
from app.engines.base import (
    BaseEngine,
    CancellationToken,
    DownloadedFileInfo,
    EngineError,
    EngineHealth,
    EngineRequest,
    EngineResult,
    ErrorCategory,
    ProgressCallback,
    ProgressUpdate,
)
from app.security.file_guard import (
    FileSecurityError,
    compute_sha256,
    create_safe_zip,
    detect_mime_from_file,
    ensure_within_dir,
    sanitize_filename,
)


def _resolve_gallerydl_cmd(bin_name: str) -> list[str]:
    found = shutil.which(bin_name)
    if found:
        return [found]
    venv_candidate = Path(sys.executable).parent / bin_name
    if venv_candidate.exists():
        return [str(venv_candidate)]
    return [sys.executable, "-m", "gallery_dl"]


def _classify_gallerydl_error(stderr_text: str) -> tuple[ErrorCategory, str, str, bool]:
    low = stderr_text.lower()
    if any(k in low for k in ("429", "too many requests", "rate limit")):
        return (
            ErrorCategory.RATE_LIMIT,
            "rate_limited",
            "Limite de requisições atingido na plataforma (429).",
            False,
        )
    if any(
        k in low
        for k in (
            "login",
            "auth",
            "private",
            "401",
            "403",
            "404",
            "not found",
            "unavailable",
            "captcha",
            "challenge",
        )
    ):
        return (
            ErrorCategory.TERMINAL,
            "media_restricted_or_unavailable",
            "Conteúdo privado, removido ou requer autenticação na plataforma.",
            False,
        )
    if any(k in low for k in ("unsupported", "no extractor")):
        return (
            ErrorCategory.UNSUPPORTED,
            "unsupported_by_gallerydl",
            "gallery-dl não suporta esta URL.",
            False,
        )
    if any(k in low for k in ("timeout", "connection", "500", "502", "503", "504", "ssl")):
        return (
            ErrorCategory.RECOVERABLE,
            "gallerydl_network_error",
            "Falha de rede temporária ao baixar com gallery-dl.",
            True,
        )
    return (
        ErrorCategory.RECOVERABLE,
        "gallerydl_failed",
        "gallery-dl falhou ao baixar a mídia.",
        False,
    )


class GalleryDlEngine(BaseEngine):
    name = "gallery-dl"

    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()

    def supports(self, request: EngineRequest) -> bool:
        if request.format == "audio":
            return False
        return True

    async def version(self) -> Optional[str]:
        def _run() -> Optional[str]:
            cmd = [*_resolve_gallerydl_cmd(self.settings.gallerydl_bin), "--version"]
            try:
                proc = subprocess.run(
                    cmd,
                    capture_output=True,
                    text=True,
                    timeout=10,
                    shell=False,
                    check=False,
                )
                if proc.returncode == 0:
                    return proc.stdout.strip() or None
            except Exception:
                return None
            return None

        return await asyncio.to_thread(_run)

    async def health(self) -> EngineHealth:
        ver = await self.version()
        if not ver:
            return EngineHealth(
                engine=self.name,
                installed_version=None,
                healthy=False,
                detail="Executável gallery-dl indisponível.",
            )
        return EngineHealth(
            engine=self.name,
            installed_version=ver,
            healthy=True,
            detail="gallery-dl operacional",
        )

    async def download(
        self,
        request: EngineRequest,
        workspace: Path,
        progress_callback: ProgressCallback,
        cancellation_token: CancellationToken,
    ) -> EngineResult:
        cancellation_token.raise_if_cancelled()
        workspace.mkdir(parents=True, exist_ok=True)
        ver = await self.version()

        def _execute() -> EngineResult:
            dl_dir = workspace / "items"
            dl_dir.mkdir(parents=True, exist_ok=True)

            cmd: list[str] = [
                *_resolve_gallerydl_cmd(self.settings.gallerydl_bin),
                "--no-mtime",
                "--write-metadata",
                "--directory",
                str(dl_dir),
                "--filename",
                "{num:>03}_{id}.{extension}",
                "--range",
                f"1-{request.max_carousel_items}",
                "--filesize-max",
                str(request.max_size_bytes),
                "-o",
                "extractor.sleep-request=0.5",
                request.url,
            ]

            proc = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
                errors="replace",
                shell=False,
            )

            deadline = time.monotonic() + request.max_duration_seconds
            last_bytes = 0
            try:
                while proc.poll() is None:
                    if cancellation_token.is_cancelled():
                        proc.terminate()
                        try:
                            proc.wait(timeout=3)
                        except subprocess.TimeoutExpired:
                            proc.kill()
                        raise EngineError(
                            "Download cancelado pelo usuário.",
                            category=ErrorCategory.CANCELLED,
                            code="cancelled",
                        )

                    if time.monotonic() > deadline:
                        proc.kill()
                        raise EngineError(
                            "Tempo limite excedido ao baixar galeria.",
                            category=ErrorCategory.RECOVERABLE,
                            code="timeout",
                        )

                    current_bytes = sum(
                        p.stat().st_size
                        for p in dl_dir.rglob("*")
                        if p.is_file() and not p.name.endswith(".json")
                    )
                    if current_bytes > request.max_size_bytes:
                        proc.kill()
                        raise EngineError(
                            "Tamanho total do carrossel excede o limite configurado.",
                            category=ErrorCategory.TERMINAL,
                            code="file_too_large",
                        )
                    if current_bytes != last_bytes:
                        last_bytes = current_bytes
                        progress_callback(
                            ProgressUpdate(
                                bytes_downloaded=current_bytes,
                                total_bytes=None,
                                percent=None,
                                is_indeterminate=True,
                                phase="downloading",
                                phase_label="Baixando",
                            )
                        )
                    time.sleep(0.35)

                stderr_text = proc.stderr.read() if proc.stderr else ""
                returncode = proc.returncode
            except EngineError:
                raise
            finally:
                if proc.poll() is None:
                    proc.kill()

            # Collect media files and metadata
            media_files: list[Path] = []
            metadata_title: Optional[str] = None
            expected_count: Optional[int] = None

            for item in sorted(dl_dir.rglob("*")):
                if item.is_symlink():
                    raise EngineError(
                        "Symlink detectado no diretório de trabalho.",
                        category=ErrorCategory.TERMINAL,
                        code="security_violation",
                    )
                if not item.is_file():
                    continue
                if item.name.endswith(".json"):
                    try:
                        meta = json.loads(item.read_text(encoding="utf-8"))
                        if isinstance(meta, dict):
                            if not metadata_title:
                                metadata_title = (
                                    meta.get("description")
                                    or meta.get("title")
                                    or meta.get("content")
                                )
                                if isinstance(metadata_title, str):
                                    metadata_title = metadata_title.strip().splitlines()[0][:120]
                            if isinstance(meta.get("count"), int):
                                expected_count = meta["count"]
                    except Exception:
                        pass
                    continue
                if item.name.endswith((".part", ".tmp")):
                    continue
                media_files.append(ensure_within_dir(workspace, item))

            if returncode != 0 and not media_files:
                cat, code, msg, is_infra = _classify_gallerydl_error(stderr_text)
                raise EngineError(msg, category=cat, code=code, is_infra_failure=is_infra)

            if not media_files:
                raise EngineError(
                    "Nenhuma mídia extraída pelo gallery-dl.",
                    category=ErrorCategory.RECOVERABLE,
                    code="empty_output",
                )

            # Enforce max items and total size
            media_files = media_files[: request.max_carousel_items]
            total_bytes = sum(f.stat().st_size for f in media_files)
            if total_bytes > request.max_size_bytes:
                raise EngineError(
                    "Arquivos excedem o limite máximo de tamanho.",
                    category=ErrorCategory.TERMINAL,
                    code="file_too_large",
                )

            missing_items = 0
            if expected_count is not None and expected_count > len(media_files):
                missing_items = min(expected_count, request.max_carousel_items) - len(media_files)

            # Validate every file before serving or zipping
            for mf in media_files:
                try:
                    detect_mime_from_file(mf)
                except FileSecurityError as exc:
                    raise EngineError(
                        str(exc),
                        category=ErrorCategory.RECOVERABLE,
                        code="invalid_media_file",
                    ) from exc

            if len(media_files) > 1:
                progress_callback(
                    ProgressUpdate(
                        bytes_downloaded=total_bytes,
                        total_bytes=total_bytes,
                        percent=None,
                        is_indeterminate=True,
                        phase="processing",
                        phase_label="Preparando arquivo",
                    )
                )
                zip_name = sanitize_filename(
                    f"{metadata_title}_carrossel.zip" if metadata_title else f"droplink_{request.platform}_carrossel.zip",
                    default_stem="droplink_carrossel",
                    default_ext=".zip",
                )
                zip_path = workspace / zip_name
                create_safe_zip(media_files, zip_path, workspace)
                zip_size = zip_path.stat().st_size
                file_info = DownloadedFileInfo(
                    path=zip_path,
                    filename=zip_name,
                    mime_type="application/zip",
                    size_bytes=zip_size,
                    sha256=compute_sha256(zip_path),
                    is_archive=True,
                    item_count=len(media_files),
                    missing_items=missing_items,
                )
                return EngineResult(
                    engine=self.name,
                    engine_version=ver,
                    files=[file_info],
                    title=metadata_title,
                )

            single = media_files[0]
            mime_type = detect_mime_from_file(single)
            safe_name = sanitize_filename(
                f"{metadata_title}{single.suffix}" if metadata_title else single.name,
                default_stem=f"droplink_{request.platform}",
                default_ext=single.suffix,
            )
            file_info = DownloadedFileInfo(
                path=single,
                filename=safe_name,
                mime_type=mime_type,
                size_bytes=single.stat().st_size,
                sha256=compute_sha256(single),
                is_archive=False,
                item_count=1,
                missing_items=missing_items,
            )
            return EngineResult(
                engine=self.name,
                engine_version=ver,
                files=[file_info],
                title=metadata_title,
            )

        return await asyncio.to_thread(_execute)
