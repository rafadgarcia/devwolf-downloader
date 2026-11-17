from __future__ import annotations

import asyncio
import json
import re
import shutil
import subprocess
import sys
import threading
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
    detect_mime_from_file,
    ensure_within_dir,
    sanitize_filename,
)

_PROGRESS_PREFIX = "DROPLINK_PROG:"
_TITLE_PREFIX = "DROPLINK_META:"

_QUALITY_FORMAT_MAP = {
    "best": "bv*+ba/b",
    "1080p": "bv*[height<=1080]+ba/b[height<=1080]/bv*+ba/b",
    "720p": "bv*[height<=720]+ba/b[height<=720]/bv*+ba/b",
    "480p": "bv*[height<=480]+ba/b[height<=480]/bv*+ba/b",
    "audio": "ba/b",
}


def _resolve_ytdlp_cmd(ytdlp_bin: str) -> list[str]:
    found = shutil.which(ytdlp_bin)
    if found:
        return [found]
    venv_candidate = Path(sys.executable).parent / ytdlp_bin
    if venv_candidate.exists():
        return [str(venv_candidate)]
    return [sys.executable, "-m", "yt_dlp"]


def _classify_ytdlp_error(stderr_text: str) -> tuple[ErrorCategory, str, str, bool]:
    low = stderr_text.lower()
    if any(
        k in low
        for k in (
            "http error 429",
            "too many requests",
            "rate limit",
            "ratelimit",
        )
    ):
        return (
            ErrorCategory.RATE_LIMIT,
            "rate_limited",
            "A plataforma aplicou limite temporário de requisições (429).",
            False,
        )
    if any(
        k in low
        for k in (
            "private",
            "sign in to confirm",
            "login required",
            "cookies are needed",
            "members-only",
            "age-restricted",
            "this video is unavailable",
            "video unavailable",
            "has been removed",
            "deleted",
            "copyright",
            "geo-restricted",
            "not available in your country",
            "drm",
            "captcha",
        )
    ):
        return (
            ErrorCategory.TERMINAL,
            "media_restricted_or_unavailable",
            "Mídia indisponível, privada ou protegida por restrição da plataforma.",
            False,
        )
    if any(
        k in low
        for k in (
            "unsupported url",
            "no video formats found",
            "unable to extract",
            "requested format is not available",
        )
    ):
        return (
            ErrorCategory.UNSUPPORTED,
            "unsupported_by_ytdlp",
            "yt-dlp não conseguiu extrair mídia compatível para este link.",
            False,
        )
    if any(
        k in low
        for k in (
            "timed out",
            "connection",
            "name resolution",
            "http error 5",
            "ssl",
            "network is unreachable",
            "reset by peer",
        )
    ):
        return (
            ErrorCategory.RECOVERABLE,
            "ytdlp_network_error",
            "Falha de conexão ou erro temporário ao acessar a mídia via yt-dlp.",
            True,
        )
    return (
        ErrorCategory.RECOVERABLE,
        "ytdlp_failed",
        "yt-dlp falhou ao processar a mídia.",
        False,
    )


class YtDlpEngine(BaseEngine):
    name = "yt-dlp"

    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()

    def supports(self, request: EngineRequest) -> bool:
        constraints = request.engine_constraints.get(self.name) or {}
        if constraints.get("video_only") and request.format == "image":
            return False
        if request.format == "image":
            return False
        return True

    async def version(self) -> Optional[str]:
        def _run() -> Optional[str]:
            cmd = [*_resolve_ytdlp_cmd(self.settings.ytdlp_bin), "--version"]
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
                detail="Executável yt-dlp indisponível.",
            )
        ffmpeg_ok = shutil.which(self.settings.ffmpeg_bin) is not None
        js_runtime = "deno" if shutil.which("deno") else ("node" if shutil.which("node") else None)
        extras = []
        extras.append("FFmpeg disponível" if ffmpeg_ok else "FFmpeg ausente")
        if js_runtime:
            extras.append(f"JS runtime: {js_runtime}")
        detail = f"yt-dlp operacional ({', '.join(extras)})"
        return EngineHealth(
            engine=self.name,
            installed_version=ver,
            healthy=True,
            detail=detail,
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
            ffmpeg_path = shutil.which(self.settings.ffmpeg_bin)
            is_audio = request.format == "audio" or request.quality == "audio"
            fmt_selector = _QUALITY_FORMAT_MAP.get(request.quality, _QUALITY_FORMAT_MAP["best"])
            if is_audio:
                fmt_selector = _QUALITY_FORMAT_MAP["audio"]
            elif not ffmpeg_path:
                # Without ffmpeg merge, prefer single-file progressive format
                fmt_selector = "b[ext=mp4]/b"

            output_tpl = str(workspace / "media_%(id).40s.%(ext)s")
            cmd: list[str] = [
                *_resolve_ytdlp_cmd(self.settings.ytdlp_bin),
                "--no-playlist",
                "--no-warnings",
                "--no-color",
                "--progress",
                "--newline",
                "--restrict-filenames",
                "--max-filesize",
                str(request.max_size_bytes),
                "--socket-timeout",
                "20",
                "--retries",
                "2",
                "--fragment-retries",
                "2",
                "--progress-template",
                f"download:{_PROGRESS_PREFIX}%(progress.downloaded_bytes)s|%(progress.total_bytes)s|%(progress.total_bytes_estimate)s",
                "--print",
                f"after_move:{_TITLE_PREFIX}%(title)s|%(duration)s|%(filepath)s",
                "-f",
                fmt_selector,
                "-o",
                output_tpl,
            ]

            if ffmpeg_path:
                cmd.extend(["--ffmpeg-location", ffmpeg_path])
                if is_audio:
                    cmd.extend(["-x", "--audio-format", "mp3", "--audio-quality", "0"])
                else:
                    cmd.extend(["--merge-output-format", "mp4"])

            deno_path = shutil.which("deno")
            node_path = shutil.which("node")
            if deno_path:
                cmd.extend(["--js-runtimes", f"deno:{deno_path}"])
            elif node_path:
                cmd.extend(["--js-runtimes", f"node:{node_path}"])

            cmd.append(request.url)

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
            stderr_lines: list[str] = []
            title: Optional[str] = None
            duration_seconds: Optional[float] = None
            reported_filepath: Optional[Path] = None
            watchdog_stop = threading.Event()
            timed_out = threading.Event()

            def _watchdog() -> None:
                while not watchdog_stop.wait(0.25):
                    if cancellation_token.is_cancelled():
                        try:
                            proc.terminate()
                        except Exception:
                            pass
                        break
                    if time.monotonic() > deadline:
                        timed_out.set()
                        try:
                            proc.kill()
                        except Exception:
                            pass
                        break

            watchdog_thread = threading.Thread(target=_watchdog, daemon=True)
            watchdog_thread.start()

            try:
                assert proc.stdout is not None
                for raw_line in proc.stdout:
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

                    if timed_out.is_set() or time.monotonic() > deadline:
                        proc.kill()
                        raise EngineError(
                            "Tempo limite do download excedido.",
                            category=ErrorCategory.RECOVERABLE,
                            code="timeout",
                            is_infra_failure=False,
                        )

                    line = raw_line.strip()
                    if line.startswith(_PROGRESS_PREFIX):
                        payload = line[len(_PROGRESS_PREFIX) :]
                        parts = payload.split("|")
                        if len(parts) == 3:
                            dl_b = _parse_int(parts[0]) or 0
                            tot_b = _parse_int(parts[1]) or _parse_int(parts[2])
                            pct: Optional[float] = None
                            indet = True
                            if tot_b and tot_b > 0:
                                pct = round(min(100.0, (dl_b / tot_b) * 100.0), 1)
                                indet = False
                            if dl_b > request.max_size_bytes:
                                proc.kill()
                                raise EngineError(
                                    "Arquivo excede o tamanho máximo permitido.",
                                    category=ErrorCategory.TERMINAL,
                                    code="file_too_large",
                                )
                            progress_callback(
                                ProgressUpdate(
                                    bytes_downloaded=dl_b,
                                    total_bytes=tot_b,
                                    percent=pct,
                                    is_indeterminate=indet,
                                    phase="downloading",
                                    phase_label="Baixando",
                                )
                            )
                    elif line.startswith(_TITLE_PREFIX):
                        payload = line[len(_TITLE_PREFIX) :]
                        parts = payload.split("|", 2)
                        if len(parts) == 3:
                            if parts[0] and parts[0] != "NA":
                                title = parts[0][:200]
                            dur_val = _parse_float(parts[1])
                            if dur_val is not None:
                                duration_seconds = dur_val
                            if parts[2]:
                                reported_filepath = Path(parts[2])

                remaining_err = proc.stderr.read() if proc.stderr else ""
                if remaining_err:
                    stderr_lines.append(remaining_err)
                returncode = proc.wait(timeout=15)
                if cancellation_token.is_cancelled():
                    raise EngineError(
                        "Download cancelado pelo usuário.",
                        category=ErrorCategory.CANCELLED,
                        code="cancelled",
                    )
                if timed_out.is_set():
                    raise EngineError(
                        "Tempo limite do download excedido.",
                        category=ErrorCategory.RECOVERABLE,
                        code="timeout",
                        is_infra_failure=False,
                    )
            except EngineError:
                raise
            except subprocess.TimeoutExpired:
                proc.kill()
                raise EngineError(
                    "Processo yt-dlp não respondeu dentro do limite.",
                    category=ErrorCategory.RECOVERABLE,
                    code="timeout",
                )
            finally:
                watchdog_stop.set()
                if proc.poll() is None:
                    proc.kill()

            if returncode != 0:
                err_text = "\n".join(stderr_lines).strip()
                cat, code, msg, is_infra = _classify_ytdlp_error(err_text)
                raise EngineError(msg, category=cat, code=code, is_infra_failure=is_infra)

            # Locate downloaded media file safely inside workspace
            candidates: list[Path] = []
            if reported_filepath and reported_filepath.exists():
                candidates.append(reported_filepath)
            else:
                for p in sorted(workspace.iterdir()):
                    if p.is_file() and not p.name.endswith((".part", ".ytdl", ".tmp")):
                        candidates.append(p)

            if not candidates:
                raise EngineError(
                    "yt-dlp concluiu sem gerar arquivo de mídia válido.",
                    category=ErrorCategory.RECOVERABLE,
                    code="empty_output",
                )

            target_file = ensure_within_dir(workspace, candidates[0])
            size_bytes = target_file.stat().st_size
            if size_bytes > request.max_size_bytes:
                raise EngineError(
                    "Arquivo final excede o limite de tamanho configurado.",
                    category=ErrorCategory.TERMINAL,
                    code="file_too_large",
                )

            try:
                mime_type = detect_mime_from_file(target_file)
            except FileSecurityError as exc:
                raise EngineError(
                    str(exc),
                    category=ErrorCategory.RECOVERABLE,
                    code="invalid_media_file",
                ) from exc

            safe_name = sanitize_filename(
                f"{title}{target_file.suffix}" if title else target_file.name,
                default_stem=f"droplink_{request.platform}",
                default_ext=target_file.suffix or ".mp4",
            )

            file_info = DownloadedFileInfo(
                path=target_file,
                filename=safe_name,
                mime_type=mime_type,
                size_bytes=size_bytes,
                sha256=compute_sha256(target_file),
                duration_seconds=duration_seconds,
                is_archive=False,
                item_count=1,
                missing_items=0,
            )
            return EngineResult(
                engine=self.name,
                engine_version=ver,
                files=[file_info],
                title=title,
                duration_seconds=duration_seconds,
            )

        return await asyncio.to_thread(_execute)


def _parse_int(val: str) -> Optional[int]:
    val = val.strip()
    if not val or val in {"NA", "None", "unknown"}:
        return None
    try:
        return int(float(val))
    except ValueError:
        return None


def _parse_float(val: str) -> Optional[float]:
    val = val.strip()
    if not val or val in {"NA", "None", "unknown"}:
        return None
    try:
        return float(val)
    except ValueError:
        return None
