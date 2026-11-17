from __future__ import annotations

import json
import zipfile
from pathlib import Path

import httpx
import pytest

from app.config import Settings
from app.engines.base import (
    BaseEngine,
    CancellationToken,
    DownloadedFileInfo,
    EngineError,
    EngineHealth,
    EngineRequest,
    EngineResult,
    ErrorCategory,
)
from app.engines.cobalt_adapter import CobaltEngine
from app.engines.router import EngineRouter
from app.security.file_guard import compute_sha256
from app.security.url_guard import load_routes_config, validate_and_match_url

FAKE_MP4_BYTES = b"\x00\x00\x00\x18ftypmp42\x00\x00\x00\x00mp42isom" + b"\x00" * 128
FAKE_JPG_BYTES = b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00" + b"\xab" * 64


@pytest.mark.asyncio
async def test_cobalt_tunnel_and_picker_zip(tmp_path: Path, test_settings: Settings) -> None:
    cfg = test_settings.model_copy(update={"cobalt_api_url": "http://cobalt:9000", "cobalt_api_key": "secret-key"})

    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "GET" and request.url.path == "/":
            return httpx.Response(200, json={"cobalt": {"version": "11.1.0", "services": ["tiktok"]}})
        if request.method == "POST" and request.url.path == "/":
            assert request.headers.get("Authorization") == "Api-Key secret-key"
            body = json.loads(request.content.decode())
            if "carousel" in body["url"]:
                return httpx.Response(
                    200,
                    json={
                        "status": "picker",
                        "picker": [
                            {"type": "photo", "url": "http://cobalt:9000/tunnel?item=1"},
                            {"type": "photo", "url": "http://cobalt:9000/tunnel?item=2"},
                        ],
                    },
                )
            return httpx.Response(
                200,
                json={
                    "status": "tunnel",
                    "url": "http://cobalt:9000/tunnel?id=vid1",
                    "filename": "tiktok_video.mp4",
                },
            )
        if request.url.path == "/tunnel":
            if "item=" in str(request.url):
                return httpx.Response(200, content=FAKE_JPG_BYTES, headers={"Content-Type": "image/jpeg"})
            return httpx.Response(
                200,
                content=FAKE_MP4_BYTES,
                headers={"Content-Type": "video/mp4", "Content-Length": str(len(FAKE_MP4_BYTES))},
            )
        return httpx.Response(404)

    transport = httpx.MockTransport(handler)
    adapter = CobaltEngine(cfg, client_factory=lambda: httpx.AsyncClient(transport=transport))

    health = await adapter.health()
    assert health.healthy is True
    assert health.installed_version == "11.1.0"

    progress_events = []
    req_tunnel = EngineRequest(
        job_id="j1",
        url="https://www.tiktok.com/@user/video/123",
        platform="tiktok",
        route_id="tiktok",
        format="auto",
        quality="best",
        max_size_bytes=10_000_000,
        max_duration_seconds=60,
        max_carousel_items=50,
    )
    res = await adapter.download(req_tunnel, tmp_path / "w1", progress_events.append, CancellationToken())
    assert res.engine == "cobalt"
    assert res.files[0].mime_type == "video/mp4"
    assert progress_events[-1].percent == 100.0

    # Picker carousel becomes single ZIP
    req_picker = EngineRequest(
        job_id="j2",
        url="https://www.tiktok.com/@user/photo/carousel123",
        platform="tiktok",
        route_id="tiktok",
        format="auto",
        quality="best",
        max_size_bytes=10_000_000,
        max_duration_seconds=60,
        max_carousel_items=50,
    )
    res_zip = await adapter.download(req_picker, tmp_path / "w2", lambda p: None, CancellationToken())
    assert res_zip.files[0].is_archive is True
    assert res_zip.files[0].mime_type == "application/zip"
    assert res_zip.files[0].item_count == 2
    with zipfile.ZipFile(res_zip.files[0].path) as zf:
        assert len(zf.namelist()) == 2


@pytest.mark.asyncio
async def test_cobalt_redirect_ssrf_blocked(tmp_path: Path, test_settings: Settings) -> None:
    cfg = test_settings.model_copy(update={"cobalt_api_url": "http://cobalt:9000"})

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"status": "redirect", "url": "http://169.254.169.254/latest/meta-data"})

    adapter = CobaltEngine(cfg, client_factory=lambda: httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    req = EngineRequest(
        job_id="j_ssrf",
        url="https://www.tiktok.com/@user/video/123",
        platform="tiktok",
        route_id="tiktok",
        format="auto",
        quality="best",
        max_size_bytes=10_000_000,
        max_duration_seconds=60,
        max_carousel_items=50,
    )
    with pytest.raises(EngineError) as exc_info:
        await adapter.download(req, tmp_path / "w_ssrf", lambda p: None, CancellationToken())
    assert exc_info.value.category == ErrorCategory.TERMINAL


class _StubEngine(BaseEngine):
    def __init__(self, name: str, behavior):
        self.name = name
        self._behavior = behavior
        self.calls = 0

    def supports(self, request: EngineRequest) -> bool:
        return True

    async def version(self):
        return "1.0.0"

    async def health(self) -> EngineHealth:
        return EngineHealth(engine=self.name, installed_version="1.0.0", healthy=True)

    async def download(self, request, workspace, progress_callback, cancellation_token) -> EngineResult:
        self.calls += 1
        return await self._behavior(workspace)


@pytest.mark.asyncio
async def test_fallback_on_recoverable_vs_no_fallback_on_terminal_and_ratelimit(
    db_conn,
    tmp_path: Path,
    test_settings: Settings,
) -> None:
    routes_cfg = load_routes_config(test_settings)
    matched = validate_and_match_url("https://www.tiktok.com/@user/video/999", routes_cfg)
    req = EngineRequest(
        job_id="job-fb",
        url=matched.normalized_url,
        platform="tiktok",
        route_id=matched.route.id,
        format="auto",
        quality="best",
        max_size_bytes=10_000_000,
        max_duration_seconds=60,
        max_carousel_items=50,
    )

    async def cobalt_500(_ws: Path):
        raise EngineError("Cobalt 502", category=ErrorCategory.RECOVERABLE, code="cobalt_http_502", is_infra_failure=True)

    async def ytdlp_ok(ws: Path):
        out = ws / "video.mp4"
        out.write_bytes(FAKE_MP4_BYTES)
        return EngineResult(
            engine="yt-dlp",
            engine_version="2026.10.26",
            files=[
                DownloadedFileInfo(
                    path=out,
                    filename="video.mp4",
                    mime_type="video/mp4",
                    size_bytes=len(FAKE_MP4_BYTES),
                    sha256=compute_sha256(out),
                )
            ],
        )

    cobalt_stub = _StubEngine("cobalt", cobalt_500)
    ytdlp_stub = _StubEngine("yt-dlp", ytdlp_ok)
    gallery_stub = _StubEngine("gallery-dl", ytdlp_ok)

    router = EngineRouter(
        test_settings,
        engines={"cobalt": cobalt_stub, "yt-dlp": ytdlp_stub, "gallery-dl": gallery_stub},
    )

    res = await router.execute_with_fallback(
        conn=db_conn,
        matched=matched,
        request=req,
        enabled_engines=["cobalt", "yt-dlp", "gallery-dl"],
        job_tmp_root=tmp_path / "job_fb",
        on_engine_start=lambda e, a: None,
        on_attempt_done=lambda *args: None,
        progress_callback=lambda p: None,
        cancellation_token=CancellationToken(),
    )
    assert res.engine == "yt-dlp"
    assert cobalt_stub.calls == 1
    assert ytdlp_stub.calls == 1
    assert gallery_stub.calls == 0

    # Terminal error (private media) must NOT trigger fallback
    async def cobalt_private(_ws: Path):
        raise EngineError("Mídia privada", category=ErrorCategory.TERMINAL, code="private")

    cobalt_term = _StubEngine("cobalt", cobalt_private)
    ytdlp_unused = _StubEngine("yt-dlp", ytdlp_ok)
    router2 = EngineRouter(test_settings, engines={"cobalt": cobalt_term, "yt-dlp": ytdlp_unused})

    with pytest.raises(EngineError) as exc_term:
        await router2.execute_with_fallback(
            conn=db_conn,
            matched=matched,
            request=req,
            enabled_engines=["cobalt", "yt-dlp"],
            job_tmp_root=tmp_path / "job_term",
            on_engine_start=lambda e, a: None,
            on_attempt_done=lambda *args: None,
            progress_callback=lambda p: None,
            cancellation_token=CancellationToken(),
        )
    assert exc_term.value.category == ErrorCategory.TERMINAL
    assert ytdlp_unused.calls == 0

    # Rate limit error must NOT switch engine and must set cooldown
    async def cobalt_429(_ws: Path):
        raise EngineError("429", category=ErrorCategory.RATE_LIMIT, code="rate_limited", retry_after_seconds=120)

    cobalt_rl = _StubEngine("cobalt", cobalt_429)
    ytdlp_unused2 = _StubEngine("yt-dlp", ytdlp_ok)
    router3 = EngineRouter(test_settings, engines={"cobalt": cobalt_rl, "yt-dlp": ytdlp_unused2})

    with pytest.raises(EngineError) as exc_rl:
        await router3.execute_with_fallback(
            conn=db_conn,
            matched=matched,
            request=req,
            enabled_engines=["cobalt", "yt-dlp"],
            job_tmp_root=tmp_path / "job_rl",
            on_engine_start=lambda e, a: None,
            on_attempt_done=lambda *args: None,
            progress_callback=lambda p: None,
            cancellation_token=CancellationToken(),
        )
    assert exc_rl.value.category == ErrorCategory.RATE_LIMIT
    assert ytdlp_unused2.calls == 0
    assert router3.breaker.check_platform_cooldown(db_conn, "tiktok") is not None
