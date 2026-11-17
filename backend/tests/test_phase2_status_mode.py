from __future__ import annotations

import shutil
import subprocess
from pathlib import Path
from typing import Optional

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.db import get_connection, transaction, utc_now_iso
from app.engines.base import (
    BaseEngine,
    CancellationToken,
    DownloadedFileInfo,
    EngineHealth,
    EngineRequest,
    EngineResult,
    ProgressCallback,
)
from app.engines.router import EngineRouter
from app.security.file_guard import compute_sha256, detect_mime_from_file
from app.services.media_processor import probe_media, process_media_for_status
from app.worker.runner import process_job


def _require_ffmpeg(settings: Settings) -> str:
    ffmpeg = shutil.which(settings.ffmpeg_bin)
    if not ffmpeg:
        pytest.skip("ffmpeg não encontrado no ambiente.")
    return ffmpeg


def _generate_sample_video(
    ffmpeg: str,
    dest: Path,
    *,
    duration: int = 2,
    size: str = "180x320",
    vcodec: str = "libx264",
    acodec: str = "aac",
    extra_video_args: Optional[list[str]] = None,
) -> None:
    cmd = [
        ffmpeg,
        "-y",
        "-f",
        "lavfi",
        "-i",
        f"color=c=lime:s={size}:d={duration}:r=30",
        "-f",
        "lavfi",
        "-i",
        "anullsrc=r=44100:cl=stereo",
        "-t",
        str(duration),
        "-c:v",
        vcodec,
    ]
    if extra_video_args:
        cmd.extend(extra_video_args)
    if vcodec == "libx264":
        cmd.extend(["-pix_fmt", "yuv420p"])
    cmd.extend(["-c:a", acodec, str(dest)])
    subprocess.run(cmd, check=True, capture_output=True, timeout=45, shell=False)


def test_status_mode_smart_copy_skips_reencode_when_compliant(tmp_path: Path, test_settings: Settings) -> None:
    ffmpeg = _require_ffmpeg(test_settings)
    ws = tmp_path / "ws_copy"
    ws.mkdir()
    src = ws / "reel.mp4"
    _generate_sample_video(ffmpeg, src, duration=2, size="360x640", vcodec="libx264", acodec="aac")

    probe_before = probe_media(src, ffprobe_bin=test_settings.ffprobe_bin, ffmpeg_bin=test_settings.ffmpeg_bin)
    assert probe_before.is_whatsapp_status_compliant is True
    assert probe_before.video_codec == "h264"
    assert probe_before.audio_codec == "aac"

    res = process_media_for_status(
        src,
        ws,
        preferred_filename="instagram_reel.mp4",
        status_mode=True,
        keep_original=True,
        ffmpeg_bin=test_settings.ffmpeg_bin,
        ffprobe_bin=test_settings.ffprobe_bin,
    )
    assert res.was_reencoded is False
    assert res.was_remuxed is True
    assert len(res.files) == 2
    status_file, orig_file = res.files[0], res.files[1]
    assert status_file.is_original is False
    assert orig_file.is_original is True
    assert status_file.filename.endswith("_status.mp4")
    assert orig_file.filename.endswith("_original.mp4")

    probe_after = probe_media(status_file.path, ffprobe_bin=test_settings.ffprobe_bin, ffmpeg_bin=test_settings.ffmpeg_bin)
    assert probe_after.is_whatsapp_status_compliant is True
    assert probe_after.width == 360
    assert probe_after.height == 640


def test_status_mode_transcodes_non_h264_preserving_aspect_ratio(tmp_path: Path, test_settings: Settings) -> None:
    ffmpeg = _require_ffmpeg(test_settings)
    ws = tmp_path / "ws_transcode"
    ws.mkdir()
    # Non-H.264 / non-MP4 container (AVI with mpeg4 + mp3, vertical 9:16 180x320)
    src = ws / "clip.avi"
    _generate_sample_video(ffmpeg, src, duration=2, size="180x320", vcodec="mpeg4", acodec="libmp3lame")

    probe_before = probe_media(src, ffprobe_bin=test_settings.ffprobe_bin, ffmpeg_bin=test_settings.ffmpeg_bin)
    assert probe_before.is_whatsapp_status_compliant is False
    assert probe_before.video_codec == "mpeg4"

    res = process_media_for_status(
        src,
        ws,
        preferred_filename="vertical_clip.avi",
        status_mode=True,
        keep_original=False,
        ffmpeg_bin=test_settings.ffmpeg_bin,
        ffprobe_bin=test_settings.ffprobe_bin,
    )
    assert res.was_reencoded is True
    assert len(res.files) == 1
    out = res.files[0]
    assert out.mime_type == "video/mp4"
    assert out.filename.endswith("_status.mp4")

    probe_after = probe_media(out.path, ffprobe_bin=test_settings.ffprobe_bin, ffmpeg_bin=test_settings.ffmpeg_bin)
    assert probe_after.is_whatsapp_status_compliant is True
    assert probe_after.video_codec == "h264"
    assert probe_after.audio_codec == "aac"
    assert probe_after.pix_fmt == "yuv420p"
    assert probe_after.width == 180
    assert probe_after.height == 320


def test_status_mode_segment_splitting_6s_into_2s_clips(tmp_path: Path, test_settings: Settings) -> None:
    ffmpeg = _require_ffmpeg(test_settings)
    ws = tmp_path / "ws_segments"
    ws.mkdir()
    src = ws / "long_status.mp4"
    # Keyframe every 1s (-g 30 at 30fps) for clean 2s cuts
    _generate_sample_video(
        ffmpeg,
        src,
        duration=6,
        size="320x240",
        vcodec="libx264",
        acodec="aac",
        extra_video_args=["-g", "30"],
    )

    res = process_media_for_status(
        src,
        ws,
        preferred_filename="long_status.mp4",
        status_mode=True,
        segment_duration=2,
        keep_original=True,
        ffmpeg_bin=test_settings.ffmpeg_bin,
        ffprobe_bin=test_settings.ffprobe_bin,
    )
    assert res.segments_created >= 3
    segments = [f for f in res.files if not f.is_original]
    originals = [f for f in res.files if f.is_original]
    assert len(segments) >= 3
    assert len(originals) == 1
    assert segments[0].filename.endswith("_part01.mp4")
    assert segments[0].segment_index == 1
    assert segments[1].filename.endswith("_part02.mp4")
    assert segments[1].segment_index == 2
    assert segments[2].filename.endswith("_part03.mp4")
    assert segments[2].segment_index == 3
    for seg in segments:
        assert seg.mime_type == "video/mp4"
        assert seg.size_bytes > 0


@pytest.mark.asyncio
async def test_worker_end_to_end_status_mode_and_segments(
    auth_client: tuple[TestClient, str],
    test_settings: Settings,
    tmp_path: Path,
) -> None:
    ffmpeg = _require_ffmpeg(test_settings)
    client, csrf = auth_client

    create_resp = client.post(
        "/api/jobs",
        json={
            "url": "https://www.facebook.com/share/r/ReelTest123/",
            "format": "video",
            "quality": "720p",
            "status_mode": True,
            "segment_duration": 2,
            "keep_original": True,
        },
        headers={"X-CSRF-Token": csrf},
    )
    assert create_resp.status_code == 201, create_resp.text
    job_data = create_resp.json()
    assert job_data["platform"] == "facebook"
    assert job_data["route_id"] == "facebook_reel"
    assert job_data["status_mode"] is True
    assert job_data["segment_duration"] == 2
    assert job_data["keep_original"] is True

    class FakeVideoEngine(BaseEngine):
        name = "yt-dlp"

        def supports(self, request: EngineRequest) -> bool:
            return True

        async def version(self) -> Optional[str]:
            return "2026.10.26"

        async def health(self) -> EngineHealth:
            return EngineHealth(engine=self.name, installed_version="2026.10.26", healthy=True)

        async def download(
            self,
            request: EngineRequest,
            workspace: Path,
            progress_callback: ProgressCallback,
            cancellation_token: CancellationToken,
        ) -> EngineResult:
            out = workspace / "fb_reel.avi"
            _generate_sample_video(ffmpeg, out, duration=4, size="180x320", vcodec="mpeg4", acodec="libmp3lame")
            return EngineResult(
                engine=self.name,
                engine_version="2026.10.26",
                title="Facebook Reel Status",
                duration_seconds=4.0,
                files=[
                    DownloadedFileInfo(
                        path=out,
                        filename="fb_reel.avi",
                        mime_type=detect_mime_from_file(out),
                        size_bytes=out.stat().st_size,
                        sha256=compute_sha256(out),
                    )
                ],
            )

    conn = get_connection(test_settings)
    try:
        with transaction(conn):
            conn.execute(
                "UPDATE jobs SET status = 'resolving', lease_owner = 'test', lease_expires_at = ? WHERE id = ?",
                (utc_now_iso(), job_data["id"]),
            )
        job_row = conn.execute("SELECT * FROM jobs WHERE id = ?", (job_data["id"],)).fetchone()
        assert job_row is not None
        router = EngineRouter(test_settings, engines={"yt-dlp": FakeVideoEngine()})
        await process_job(conn, job_row, test_settings, router, "test-worker")
    finally:
        conn.close()

    done = client.get(f"/api/jobs/{job_data['id']}")
    assert done.status_code == 200
    payload = done.json()
    assert payload["status"] == "ready"
    assert len(payload["files"]) >= 3  # at least 2 segments + 1 original
    originals = [f for f in payload["files"] if f["is_original"]]
    segments = [f for f in payload["files"] if not f["is_original"]]
    assert len(originals) == 1
    assert len(segments) >= 2
    assert segments[0]["mime_type"] == "video/mp4"

    # Download first segment authenticated
    dl = client.get(segments[0]["download_url"])
    assert dl.status_code == 200
    assert "attachment;" in dl.headers.get("content-disposition", "")


def test_phase2_settings_and_engine_releases_api(
    auth_client: tuple[TestClient, str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client, csrf = auth_client

    # Default Phase 2 settings present
    get_res = client.get("/api/settings")
    assert get_res.status_code == 200
    s_data = get_res.json()
    assert s_data["preferred_execution_mode"] == "auto"
    assert s_data["status_mode_enabled"] is False
    assert s_data["status_max_duration_seconds"] == 30
    assert s_data["status_keep_original"] is False
    assert s_data["auto_update_engines"] is True
    assert s_data["notifications_enabled"] is True

    # Update Phase 2 settings
    patch_res = client.patch(
        "/api/settings",
        json={
            "preferred_execution_mode": "local",
            "status_mode_enabled": True,
            "status_max_duration_seconds": 60,
            "status_keep_original": True,
            "auto_update_engines": False,
            "notifications_enabled": False,
        },
        headers={"X-CSRF-Token": csrf},
    )
    assert patch_res.status_code == 200
    updated = patch_res.json()
    assert updated["preferred_execution_mode"] == "local"
    assert updated["status_mode_enabled"] is True
    assert updated["status_max_duration_seconds"] == 60
    assert updated["status_keep_original"] is True
    assert updated["auto_update_engines"] is False
    assert updated["notifications_enabled"] is False

    # Engine releases & platform capability matrix endpoint
    async def fake_pypi(package_name: str, *, force_refresh: bool = False):
        return "2099.01.01" if package_name == "yt-dlp" else "2.0.0"

    monkeypatch.setattr("app.api.engines._fetch_pypi_latest_version", fake_pypi)
    rel_res = client.get("/api/engines/releases")
    assert rel_res.status_code == 200
    rel_data = rel_res.json()
    engine_map = {e["engine"]: e for e in rel_data["engines"]}
    assert "yt-dlp" in engine_map
    assert "gallery-dl" in engine_map
    assert "cobalt" in engine_map
    assert engine_map["yt-dlp"]["latest_version"] == "2099.01.01"
    assert engine_map["yt-dlp"]["update_available"] is True

    platforms = {c["platform"] for c in rel_data["capabilities"]}
    assert {"instagram", "facebook", "tiktok", "youtube", "twitter", "reddit", "pinterest"}.issubset(platforms)
