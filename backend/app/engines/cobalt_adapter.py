from __future__ import annotations

import json
import mimetypes
import time
from pathlib import Path
from typing import Any, Callable, Optional
from urllib.parse import urljoin, urlsplit

import httpx

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
from app.models.schemas import CobaltUpdaterStatus
from app.security.file_guard import (
    FileSecurityError,
    compute_sha256,
    create_safe_zip,
    detect_mime_from_file,
    sanitize_filename,
)
from app.security.url_guard import UrlValidationError, validate_external_media_url

_QUALITY_MAP = {
    "best": "max",
    "1080p": "1080",
    "720p": "720",
    "480p": "480",
    "audio": "1080",
}


def read_cobalt_updater_status(settings: Settings) -> Optional[CobaltUpdaterStatus]:
    status_path = settings.resolved_cobalt_status_file()
    if status_path is None or not status_path.exists():
        return None
    try:
        raw = json.loads(status_path.read_text(encoding="utf-8"))
        if not isinstance(raw, dict):
            return None
        return CobaltUpdaterStatus(
            maintenance=bool(raw.get("maintenance", False)),
            state=str(raw.get("state", "idle")),
            current_digest=raw.get("current_digest"),
            current_version=raw.get("current_version"),
            previous_digest=raw.get("previous_digest"),
            rejected_digest=raw.get("rejected_digest"),
            rejected_until=raw.get("rejected_until"),
            last_check_at=raw.get("last_check_at"),
            last_update_at=raw.get("last_update_at"),
            last_rollback_at=raw.get("last_rollback_at"),
            last_error=raw.get("last_error"),
        )
    except Exception:
        return None


def _classify_cobalt_api_error(error_code: str, context: dict[str, Any] | None = None) -> tuple[ErrorCategory, str, bool]:
    low = (error_code or "").lower()
    if "rate" in low or "limit" in low or "429" in low:
        return (
            ErrorCategory.RATE_LIMIT,
            "Cobalt atingiu limite de requisições na plataforma.",
            False,
        )
    if any(
        k in low
        for k in (
            "private",
            "unavailable",
            "empty",
            "deleted",
            "auth",
            "login",
            "captcha",
            "too_big",
            "age",
            "geo",
            "region",
            "copyright",
        )
    ):
        return (
            ErrorCategory.TERMINAL,
            "Mídia indisponível, privada ou acima do limite permitido.",
            False,
        )
    if any(k in low for k in ("link.invalid", "link.unsupported", "service", "not_supported")):
        return (
            ErrorCategory.UNSUPPORTED,
            "Este link ou formato não é suportado pelo Cobalt.",
            False,
        )
    if any(k in low for k in ("fetch.critical", "api.unreachable", "timeout", "500", "502", "503")):
        return (
            ErrorCategory.RECOVERABLE,
            "Erro interno ou de rede na instância Cobalt.",
            True,
        )
    return (
        ErrorCategory.RECOVERABLE,
        f"Cobalt não conseguiu processar a solicitação ({error_code or 'erro desconhecido'}).",
        False,
    )


class CobaltEngine(BaseEngine):
    name = "cobalt"

    def __init__(
        self,
        settings: Settings | None = None,
        client_factory: Optional[Callable[[], httpx.AsyncClient]] = None,
    ) -> None:
        self.settings = settings or get_settings()
        self._client_factory = client_factory

    def _is_configured(self) -> bool:
        return bool(self.settings.cobalt_api_url.strip())

    def _base_url(self) -> str:
        return self.settings.cobalt_api_url.strip().rstrip("/")

    def _headers(self) -> dict[str, str]:
        headers = {
            "Accept": "application/json",
            "Content-Type": "application/json",
            "User-Agent": "DevwolfDownloader/2.0",
        }
        api_key = self.settings.cobalt_api_key.strip()
        if api_key:
            headers["Authorization"] = f"Api-Key {api_key}"
        return headers

    def _make_client(self, timeout_seconds: float = 30.0) -> httpx.AsyncClient:
        if self._client_factory is not None:
            return self._client_factory()
        return httpx.AsyncClient(
            follow_redirects=False,
            timeout=httpx.Timeout(timeout_seconds, connect=10.0),
        )

    def supports(self, request: EngineRequest) -> bool:
        if not self._is_configured():
            return False
        updater = read_cobalt_updater_status(self.settings)
        if updater and updater.maintenance:
            return False
        return True

    async def version(self) -> Optional[str]:
        if not self._is_configured():
            return None
        try:
            async with self._make_client(timeout_seconds=5.0) as client:
                resp = await client.get(f"{self._base_url()}/", headers={"Accept": "application/json"})
                if resp.status_code != 200:
                    return None
                data = resp.json()
                if isinstance(data, dict):
                    cobalt_info = data.get("cobalt")
                    if isinstance(cobalt_info, dict) and cobalt_info.get("version"):
                        return str(cobalt_info["version"])
                    if data.get("version"):
                        return str(data["version"])
        except Exception:
            return None
        return None

    async def health(self) -> EngineHealth:
        updater = read_cobalt_updater_status(self.settings)
        if updater and updater.maintenance:
            return EngineHealth(
                engine=self.name,
                installed_version=updater.current_version,
                healthy=False,
                detail="Instância Cobalt em manutenção programada.",
                maintenance_mode=True,
                maintenance_note=f"Estado do atualizador: {updater.state}",
            )

        if not self._is_configured():
            return EngineHealth(
                engine=self.name,
                installed_version=None,
                healthy=False,
                detail="COBALT_API_URL não configurada.",
            )

        try:
            async with self._make_client(timeout_seconds=5.0) as client:
                resp = await client.get(f"{self._base_url()}/", headers={"Accept": "application/json"})
                if resp.status_code != 200:
                    return EngineHealth(
                        engine=self.name,
                        installed_version=None,
                        healthy=False,
                        detail=f"Cobalt respondeu HTTP {resp.status_code}.",
                    )
                data = resp.json()
                ver = None
                if isinstance(data, dict):
                    cobalt_info = data.get("cobalt")
                    if isinstance(cobalt_info, dict):
                        ver = cobalt_info.get("version")
                    ver = ver or data.get("version")
                return EngineHealth(
                    engine=self.name,
                    installed_version=str(ver) if ver else None,
                    healthy=True,
                    detail="Instância Cobalt respondendo normalmente.",
                )
        except Exception as exc:
            return EngineHealth(
                engine=self.name,
                installed_version=None,
                healthy=False,
                detail=f"Falha ao conectar ao Cobalt: {type(exc).__name__}",
            )

    async def download(
        self,
        request: EngineRequest,
        workspace: Path,
        progress_callback: ProgressCallback,
        cancellation_token: CancellationToken,
    ) -> EngineResult:
        cancellation_token.raise_if_cancelled()
        if not self._is_configured():
            raise EngineError(
                "Cobalt não está configurado.",
                category=ErrorCategory.UNSUPPORTED,
                code="cobalt_unconfigured",
            )

        updater = read_cobalt_updater_status(self.settings)
        if updater and updater.maintenance:
            raise EngineError(
                "Cobalt está em janela de manutenção.",
                category=ErrorCategory.RECOVERABLE,
                code="cobalt_maintenance",
            )

        workspace.mkdir(parents=True, exist_ok=True)
        base_url = self._base_url()

        payload: dict[str, Any] = {
            "url": request.url,
            "videoQuality": _QUALITY_MAP.get(request.quality, "1080"),
            "downloadMode": "audio" if (request.format == "audio" or request.quality == "audio") else "auto",
            "filenameStyle": "basic",
            "localProcessing": "disabled",
        }

        ver: Optional[str] = None
        async with self._make_client(timeout_seconds=max(30.0, float(request.max_duration_seconds))) as client:
            try:
                resp = await client.post(
                    f"{base_url}/",
                    headers=self._headers(),
                    json=payload,
                )
            except (httpx.ConnectError, httpx.TimeoutException, httpx.NetworkError) as exc:
                raise EngineError(
                    f"Falha de conexão com Cobalt ({type(exc).__name__}).",
                    category=ErrorCategory.RECOVERABLE,
                    code="cobalt_unreachable",
                    is_infra_failure=True,
                ) from exc

            if resp.status_code >= 500:
                raise EngineError(
                    f"Instância Cobalt retornou erro {resp.status_code}.",
                    category=ErrorCategory.RECOVERABLE,
                    code=f"cobalt_http_{resp.status_code}",
                    is_infra_failure=True,
                )

            if resp.status_code == 429:
                retry_after = _parse_retry_after(resp.headers.get("Retry-After"))
                raise EngineError(
                    "Cobalt atingiu limite de taxa (429).",
                    category=ErrorCategory.RATE_LIMIT,
                    code="rate_limited",
                    retry_after_seconds=retry_after,
                )

            try:
                body = resp.json()
            except ValueError as exc:
                raise EngineError(
                    "Resposta inválida do Cobalt (JSON malformado).",
                    category=ErrorCategory.RECOVERABLE,
                    code="cobalt_invalid_json",
                    is_infra_failure=True,
                ) from exc

            status_val = str(body.get("status", "")).lower()
            if status_val == "error" or resp.status_code >= 400:
                err_obj = body.get("error") if isinstance(body.get("error"), dict) else {}
                err_code = str(err_obj.get("code") or f"http_{resp.status_code}")
                cat, msg, is_infra = _classify_cobalt_api_error(err_code, err_obj.get("context"))
                raise EngineError(msg, category=cat, code=err_code, is_infra_failure=is_infra)

            if status_val == "local-processing":
                raise EngineError(
                    "Cobalt solicitou processamento local (não habilitado no MVP).",
                    category=ErrorCategory.UNSUPPORTED,
                    code="cobalt_local_processing",
                )

            if status_val in {"tunnel", "redirect"}:
                media_url = body.get("url")
                if not isinstance(media_url, str) or not media_url:
                    raise EngineError(
                        "Cobalt não retornou URL de mídia.",
                        category=ErrorCategory.RECOVERABLE,
                        code="cobalt_missing_url",
                    )
                suggested_name = body.get("filename") if isinstance(body.get("filename"), str) else None
                downloaded = await self._stream_media_file(
                    client=client,
                    raw_url=media_url,
                    dest_dir=workspace,
                    suggested_name=suggested_name,
                    platform=request.platform,
                    max_size_bytes=request.max_size_bytes,
                    max_duration_seconds=request.max_duration_seconds,
                    progress_callback=progress_callback,
                    cancellation_token=cancellation_token,
                    verify_dns=self._client_factory is None,
                )
                return EngineResult(
                    engine=self.name,
                    engine_version=ver,
                    files=[downloaded],
                    title=Path(downloaded.filename).stem,
                )

            if status_val == "picker":
                items = body.get("picker")
                if not isinstance(items, list) or not items:
                    raise EngineError(
                        "Cobalt retornou picker vazio.",
                        category=ErrorCategory.RECOVERABLE,
                        code="cobalt_empty_picker",
                    )

                items = items[: request.max_carousel_items]
                downloaded_paths: list[Path] = []
                total_accumulated = 0
                missing_items = 0

                for idx, item in enumerate(items, start=1):
                    cancellation_token.raise_if_cancelled()
                    if not isinstance(item, dict) or not isinstance(item.get("url"), str):
                        missing_items += 1
                        continue
                    try:
                        item_info = await self._stream_media_file(
                            client=client,
                            raw_url=item["url"],
                            dest_dir=workspace / "items",
                            suggested_name=f"{idx:03d}_{request.platform}",
                            platform=request.platform,
                            max_size_bytes=request.max_size_bytes - total_accumulated,
                            max_duration_seconds=request.max_duration_seconds,
                            progress_callback=progress_callback,
                            cancellation_token=cancellation_token,
                            verify_dns=self._client_factory is None,
                        )
                        total_accumulated += item_info.size_bytes
                        downloaded_paths.append(item_info.path)
                    except EngineError as exc:
                        if exc.category == ErrorCategory.CANCELLED:
                            raise
                        missing_items += 1

                if not downloaded_paths:
                    raise EngineError(
                        "Nenhum item do carrossel pôde ser baixado via Cobalt.",
                        category=ErrorCategory.RECOVERABLE,
                        code="cobalt_picker_failed",
                    )

                if len(downloaded_paths) > 1:
                    zip_name = sanitize_filename(
                        f"droplink_{request.platform}_carrossel.zip",
                        default_stem="droplink_carrossel",
                        default_ext=".zip",
                    )
                    zip_path = workspace / zip_name
                    create_safe_zip(downloaded_paths, zip_path, workspace)
                    return EngineResult(
                        engine=self.name,
                        engine_version=ver,
                        files=[
                            DownloadedFileInfo(
                                path=zip_path,
                                filename=zip_name,
                                mime_type="application/zip",
                                size_bytes=zip_path.stat().st_size,
                                sha256=compute_sha256(zip_path),
                                is_archive=True,
                                item_count=len(downloaded_paths),
                                missing_items=missing_items,
                            )
                        ],
                    )

                only_path = downloaded_paths[0]
                mime = detect_mime_from_file(only_path)
                safe_name = sanitize_filename(only_path.name, default_stem=f"droplink_{request.platform}")
                return EngineResult(
                    engine=self.name,
                    engine_version=ver,
                    files=[
                        DownloadedFileInfo(
                            path=only_path,
                            filename=safe_name,
                            mime_type=mime,
                            size_bytes=only_path.stat().st_size,
                            sha256=compute_sha256(only_path),
                            is_archive=False,
                            item_count=1,
                            missing_items=missing_items,
                        )
                    ],
                )

            raise EngineError(
                f"Status inesperado do Cobalt: {status_val or 'vazio'}",
                category=ErrorCategory.RECOVERABLE,
                code="cobalt_unexpected_status",
            )

    async def _stream_media_file(
        self,
        *,
        client: httpx.AsyncClient,
        raw_url: str,
        dest_dir: Path,
        suggested_name: Optional[str],
        platform: str,
        max_size_bytes: int,
        max_duration_seconds: int,
        progress_callback: ProgressCallback,
        cancellation_token: CancellationToken,
        verify_dns: bool = True,
    ) -> DownloadedFileInfo:
        dest_dir.mkdir(parents=True, exist_ok=True)
        cobalt_base = self._base_url()
        current_url = raw_url
        max_hops = 5
        deadline = time.monotonic() + max_duration_seconds

        for _hop in range(max_hops):
            cancellation_token.raise_if_cancelled()
            try:
                validated_url, is_cobalt_origin = validate_external_media_url(
                    current_url,
                    cobalt_api_url=cobalt_base,
                    verify_dns=verify_dns,
                )
            except UrlValidationError as exc:
                raise EngineError(
                    str(exc),
                    category=ErrorCategory.TERMINAL,
                    code=exc.code,
                ) from exc

            req_headers = {"User-Agent": "DevwolfDownloader/2.0"}
            if is_cobalt_origin and self.settings.cobalt_api_key.strip():
                req_headers["Authorization"] = f"Api-Key {self.settings.cobalt_api_key.strip()}"

            try:
                async with client.stream("GET", validated_url, headers=req_headers) as resp:
                    if resp.status_code in {301, 302, 303, 307, 308}:
                        location = resp.headers.get("location")
                        if not location:
                            raise EngineError(
                                "Redirect sem destino na entrega de mídia.",
                                category=ErrorCategory.RECOVERABLE,
                                code="invalid_redirect",
                            )
                        current_url = urljoin(validated_url, location.strip())
                        continue

                    if resp.status_code >= 500:
                        raise EngineError(
                            f"Erro {resp.status_code} ao transferir stream do Cobalt.",
                            category=ErrorCategory.RECOVERABLE,
                            code=f"stream_http_{resp.status_code}",
                            is_infra_failure=is_cobalt_origin,
                        )
                    if resp.status_code >= 400:
                        raise EngineError(
                            f"Falha HTTP {resp.status_code} ao baixar mídia.",
                            category=ErrorCategory.RECOVERABLE,
                            code=f"stream_http_{resp.status_code}",
                        )

                    content_length_hdr = resp.headers.get("content-length")
                    total_bytes: Optional[int] = None
                    if content_length_hdr and content_length_hdr.isdigit():
                        total_bytes = int(content_length_hdr)
                        if total_bytes > max_size_bytes:
                            raise EngineError(
                                "Tamanho do arquivo excede o limite configurado.",
                                category=ErrorCategory.TERMINAL,
                                code="file_too_large",
                            )

                    content_type = (resp.headers.get("content-type") or "").split(";")[0].strip().lower()
                    ext_guess = mimetypes.guess_extension(content_type) or ".bin"
                    temp_name = sanitize_filename(
                        suggested_name or Path(urlsplit(validated_url).path).name or f"droplink_{platform}{ext_guess}",
                        default_stem=f"droplink_{platform}",
                        default_ext=ext_guess,
                    )
                    temp_path = dest_dir / temp_name

                    bytes_written = 0
                    with temp_path.open("wb") as fh:
                        async for chunk in resp.aiter_bytes(chunk_size=65536):
                            cancellation_token.raise_if_cancelled()
                            if time.monotonic() > deadline:
                                raise EngineError(
                                    "Tempo limite excedido durante transferência.",
                                    category=ErrorCategory.RECOVERABLE,
                                    code="timeout",
                                )
                            bytes_written += len(chunk)
                            if bytes_written > max_size_bytes:
                                raise EngineError(
                                    "Download excedeu o tamanho máximo permitido.",
                                    category=ErrorCategory.TERMINAL,
                                    code="file_too_large",
                                )
                            fh.write(chunk)
                            pct: Optional[float] = None
                            indet = True
                            if total_bytes and total_bytes > 0:
                                pct = round(min(100.0, (bytes_written / total_bytes) * 100.0), 1)
                                indet = False
                            progress_callback(
                                ProgressUpdate(
                                    bytes_downloaded=bytes_written,
                                    total_bytes=total_bytes,
                                    percent=pct,
                                    is_indeterminate=indet,
                                    phase="downloading",
                                    phase_label="Baixando",
                                )
                            )

                    try:
                        mime = detect_mime_from_file(temp_path)
                    except FileSecurityError as exc:
                        raise EngineError(
                            str(exc),
                            category=ErrorCategory.RECOVERABLE,
                            code="invalid_media_file",
                        ) from exc

                    # Normalize extension if it landed as .bin
                    if temp_path.suffix == ".bin":
                        better_ext = mimetypes.guess_extension(mime) or ".mp4"
                        if better_ext == ".jpe":
                            better_ext = ".jpg"
                        final_name = sanitize_filename(
                            f"{temp_path.stem}{better_ext}",
                            default_stem=f"droplink_{platform}",
                            default_ext=better_ext,
                        )
                        final_path = dest_dir / final_name
                        temp_path.rename(final_path)
                    else:
                        final_path = temp_path
                        final_name = temp_name

                    return DownloadedFileInfo(
                        path=final_path,
                        filename=final_name,
                        mime_type=mime,
                        size_bytes=bytes_written,
                        sha256=compute_sha256(final_path),
                        is_archive=False,
                        item_count=1,
                        missing_items=0,
                    )
            except (httpx.ConnectError, httpx.TimeoutException, httpx.NetworkError) as exc:
                raise EngineError(
                    f"Erro de conexão durante stream ({type(exc).__name__}).",
                    category=ErrorCategory.RECOVERABLE,
                    code="stream_network_error",
                    is_infra_failure=is_cobalt_origin,
                ) from exc

        raise EngineError(
            "Excesso de redirecionamentos ao baixar mídia.",
            category=ErrorCategory.TERMINAL,
            code="too_many_redirects",
        )


def _parse_retry_after(val: Optional[str]) -> Optional[int]:
    if not val:
        return None
    if val.strip().isdigit():
        return max(1, int(val.strip()))
    return 60
