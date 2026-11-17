from __future__ import annotations

import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.db import get_connection
from app.engines.base import BaseEngine, DownloadedFileInfo, EngineHealth, EngineRequest, EngineResult
from app.engines.router import EngineRouter
from app.security.file_guard import compute_sha256
from app.worker.runner import acquire_next_job, process_job

FAKE_MP4 = b"\x00\x00\x00\x18ftypmp42\x00\x00\x00\x00mp42isom" + b"\x11" * 128


@pytest.mark.asyncio
async def test_health_ready_sse_and_authenticated_file_download(
    auth_client: tuple[TestClient, str],
    test_settings: Settings,
) -> None:
    client, csrf = auth_client

    assert client.get("/healthz").json() == {"status": "ok"}
    assert client.get("/readyz").json() == {"status": "ready"}

    match = client.get("/api/routes/match", params={"url": "https://youtu.be/dQw4w9WgXcQ?si=123"})
    assert match.status_code == 200
    assert match.json()["valid"] is True
    assert match.json()["platform"] == "youtube"
    assert match.json()["normalized_url"] == "https://youtu.be/dQw4w9WgXcQ"

    created = client.post(
        "/api/jobs",
        json={"url": "https://youtu.be/dQw4w9WgXcQ", "format": "video", "quality": "1080p"},
        headers={"X-CSRF-Token": csrf},
    )
    assert created.status_code == 201
    job_id = created.json()["id"]

    class OkEngine(BaseEngine):
        name = "yt-dlp"

        def supports(self, request: EngineRequest) -> bool:
            return True

        async def version(self):
            return "2026.10.26"

        async def health(self):
            return EngineHealth(engine="yt-dlp", installed_version="2026.10.26", healthy=True)

        async def download(self, request, workspace: Path, progress_callback, cancellation_token) -> EngineResult:
            out = workspace / "rick_video.mp4"
            out.write_bytes(FAKE_MP4)
            return EngineResult(
                engine="yt-dlp",
                engine_version="2026.10.26",
                title="Video de Teste",
                files=[
                    DownloadedFileInfo(
                        path=out,
                        filename="Video_de_Teste.mp4",
                        mime_type="video/mp4",
                        size_bytes=len(FAKE_MP4),
                        sha256=compute_sha256(out),
                    )
                ],
            )

    conn = get_connection(test_settings)
    try:
        row = acquire_next_job(conn, "worker-sse", 60)
        assert row is not None
        await process_job(
            conn,
            row,
            test_settings,
            EngineRouter(test_settings, engines={"yt-dlp": OkEngine()}),
            "worker-sse",
        )
    finally:
        conn.close()

    # Check SSE stream emits ready job event and closes
    with client.stream("GET", f"/api/jobs/{job_id}/events") as sse_resp:
        assert sse_resp.status_code == 200
        assert "text/event-stream" in sse_resp.headers["content-type"]
        body = "".join(sse_resp.iter_text())
        assert "event: job" in body
        data_line = [ln for ln in body.splitlines() if ln.startswith("data: ")][0]
        job_payload = json.loads(data_line[len("data: ") :])
        assert job_payload["status"] == "ready"
        assert job_payload["phase_label"] == "Pronto para baixar"
        assert len(job_payload["files"]) == 1
        file_id = job_payload["files"][0]["id"]

    # Download file with safe Content-Disposition & nosniff
    dl = client.get(f"/api/jobs/{job_id}/files/{file_id}")
    assert dl.status_code == 200
    assert dl.content == FAKE_MP4
    assert "attachment" in dl.headers.get("content-disposition", "").lower()
    assert dl.headers.get("x-content-type-options") == "nosniff"
