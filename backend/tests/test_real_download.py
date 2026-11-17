from __future__ import annotations

import http.server
import shutil
import subprocess
import threading
from pathlib import Path

import pytest

from app.config import Settings
from app.engines.base import CancellationToken, EngineRequest
from app.engines.gallerydl_adapter import GalleryDlEngine
from app.engines.ytdlp_adapter import YtDlpEngine


@pytest.mark.asyncio
async def test_real_engine_binaries_installed_and_healthy(test_settings: Settings) -> None:
    yt = YtDlpEngine(test_settings)
    gdl = GalleryDlEngine(test_settings)

    yt_ver = await yt.version()
    gdl_ver = await gdl.version()
    assert yt_ver is not None and len(yt_ver) > 0
    assert gdl_ver is not None and len(gdl_ver) > 0

    yt_health = await yt.health()
    gdl_health = await gdl.health()
    assert yt_health.healthy is True
    assert gdl_health.healthy is True


@pytest.mark.asyncio
async def test_real_ytdlp_download_against_local_media_server(
    tmp_path: Path,
    test_settings: Settings,
) -> None:
    """Proves real yt-dlp (+ ffmpeg if available) subprocess execution, progress hook, and magic-byte validation."""
    ffmpeg = shutil.which(test_settings.ffmpeg_bin)
    media_dir = tmp_path / "media_server"
    media_dir.mkdir(parents=True, exist_ok=True)
    sample_mp4 = media_dir / "sample_reel.mp4"

    if ffmpeg:
        subprocess.run(
            [
                ffmpeg,
                "-y",
                "-f",
                "lavfi",
                "-i",
                "color=c=lime:s=320x240:d=1",
                "-f",
                "lavfi",
                "-i",
                "anullsrc=r=44100:cl=stereo",
                "-t",
                "1",
                "-c:v",
                "libx264",
                "-pix_fmt",
                "yuv420p",
                "-c:a",
                "aac",
                str(sample_mp4),
            ],
            check=True,
            capture_output=True,
            timeout=30,
        )
    else:
        # Minimal valid MP4 header+payload if ffmpeg is absent in a minimal test container
        sample_mp4.write_bytes(b"\x00\x00\x00\x18ftypmp42\x00\x00\x00\x00mp42isom" + b"\x00" * 4096)

    class QuietHandler(http.server.SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=str(media_dir), **kwargs)

        def log_message(self, format, *args):
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), QuietHandler)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    try:
        yt = YtDlpEngine(test_settings)
        progress_updates = []
        req = EngineRequest(
            job_id="real-ytdlp-1",
            url=f"http://127.0.0.1:{port}/sample_reel.mp4",
            platform="instagram",
            route_id="instagram_reel",
            format="video",
            quality="best",
            max_size_bytes=50_000_000,
            max_duration_seconds=60,
            max_carousel_items=50,
        )
        ws = tmp_path / "yt_workspace"
        result = await yt.download(req, ws, progress_updates.append, CancellationToken())
        assert result.engine == "yt-dlp"
        assert result.engine_version is not None
        assert len(result.files) == 1
        out_file = result.files[0]
        assert out_file.path.exists()
        assert out_file.mime_type == "video/mp4"
        assert out_file.size_bytes > 0
        assert len(out_file.sha256) == 64
        assert len(progress_updates) >= 1
        assert progress_updates[-1].bytes_downloaded > 0
    finally:
        server.shutdown()
        server.server_close()
