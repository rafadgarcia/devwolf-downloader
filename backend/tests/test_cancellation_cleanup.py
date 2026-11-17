from __future__ import annotations

import asyncio
from datetime import timedelta
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.db import get_connection, transaction, utc_now, utc_now_iso
from app.engines.base import BaseEngine, CancellationToken, EngineHealth, EngineRequest, EngineResult
from app.engines.router import EngineRouter
from app.services.cleanup_service import recover_expired_leases, run_cleanup_cycle
from app.worker.runner import acquire_next_job, process_job


def test_cancel_queued_job_via_api(auth_client: tuple[TestClient, str]) -> None:
    client, csrf = auth_client
    created = client.post(
        "/api/jobs",
        json={"url": "https://www.instagram.com/reel/CancelMe1/", "format": "video", "quality": "best"},
        headers={"X-CSRF-Token": csrf},
    )
    assert created.status_code == 201
    job_id = created.json()["id"]

    cancelled = client.post(f"/api/jobs/{job_id}/cancel", headers={"X-CSRF-Token": csrf})
    assert cancelled.status_code == 200
    assert cancelled.json()["status"] == "cancelled"
    assert cancelled.json()["phase_label"] == "Cancelado"


@pytest.mark.asyncio
async def test_inflight_cancellation_terminates_worker_job(
    auth_client: tuple[TestClient, str],
    test_settings: Settings,
) -> None:
    client, csrf = auth_client
    created = client.post(
        "/api/jobs",
        json={"url": "https://www.instagram.com/reel/CancelInflight1/", "format": "video", "quality": "best"},
        headers={"X-CSRF-Token": csrf},
    )
    job_id = created.json()["id"]

    class SlowEngine(BaseEngine):
        name = "yt-dlp"

        def supports(self, request: EngineRequest) -> bool:
            return True

        async def version(self):
            return "1.0"

        async def health(self):
            return EngineHealth(engine="yt-dlp", installed_version="1.0", healthy=True)

        async def download(self, request, workspace, progress_callback, cancellation_token: CancellationToken) -> EngineResult:
            # Create partial file then wait for cancellation
            (workspace / "partial.mp4.part").write_bytes(b"partial")
            for _ in range(40):
                cancellation_token.raise_if_cancelled()
                await asyncio.sleep(0.1)
            raise AssertionError("Should have been cancelled")

    conn = get_connection(test_settings)
    try:
        job_row = acquire_next_job(conn, "test-worker", 60)
        assert job_row is not None and job_row["id"] == job_id

        router = EngineRouter(test_settings, engines={"yt-dlp": SlowEngine()})

        async def _trigger_cancel():
            await asyncio.sleep(0.25)
            client.post(f"/api/jobs/{job_id}/cancel", headers={"X-CSRF-Token": csrf})

        await asyncio.gather(
            process_job(conn, job_row, test_settings, router, "test-worker"),
            _trigger_cancel(),
        )

        final = client.get(f"/api/jobs/{job_id}").json()
        assert final["status"] == "cancelled"
        assert not (test_settings.resolved_tmp_dir() / job_id).exists()
    finally:
        conn.close()


def test_lease_recovery_and_cleanup_respects_active_leases(
    auth_client: tuple[TestClient, str],
    test_settings: Settings,
) -> None:
    client, csrf = auth_client
    created = client.post(
        "/api/jobs",
        json={"url": "https://www.instagram.com/reel/Recover1/", "format": "video", "quality": "best"},
        headers={"X-CSRF-Token": csrf},
    )
    job_id = created.json()["id"]

    conn = get_connection(test_settings)
    try:
        # Simulate crashed worker with expired lease
        expired_ts = (utc_now() - timedelta(minutes=5)).isoformat()
        with transaction(conn):
            conn.execute(
                "UPDATE jobs SET status = 'downloading', lease_owner = 'dead-worker', lease_expires_at = ?, attempt_count = 1 WHERE id = ?",
                (expired_ts, job_id),
            )
        recovered = recover_expired_leases(conn, test_settings)
        assert recovered == 1

        row = conn.execute("SELECT status, lease_owner FROM jobs WHERE id = ?", (job_id,)).fetchone()
        assert row["status"] == "queued"
        assert row["lease_owner"] is None

        # Create an expired file & an active-lease file to verify cleanup respects active leases
        storage = test_settings.resolved_storage_dir()
        f_exp_path = storage / job_id / "expired.mp4"
        f_exp_path.parent.mkdir(parents=True, exist_ok=True)
        f_exp_path.write_bytes(b"\x00\x00\x00\x18ftypmp42\x00\x00\x00\x00mp42isom")

        user_id = conn.execute("SELECT user_id FROM jobs WHERE id = ?", (job_id,)).fetchone()["user_id"]
        with transaction(conn):
            conn.execute("UPDATE jobs SET status = 'ready' WHERE id = ?", (job_id,))
            conn.execute(
                """
                INSERT INTO files (id, job_id, user_id, filename, storage_path, mime_type, size_bytes, created_at, expires_at)
                VALUES ('f1', ?, ?, 'expired.mp4', ?, 'video/mp4', 24, ?, ?)
                """,
                (job_id, user_id, f"{job_id}/expired.mp4", utc_now_iso(), expired_ts),
            )

        summary = run_cleanup_cycle(conn, test_settings)
        assert summary["expired_files_removed"] == 1
        assert not f_exp_path.exists()
        assert conn.execute("SELECT status FROM jobs WHERE id = ?", (job_id,)).fetchone()["status"] == "expired"
    finally:
        conn.close()
