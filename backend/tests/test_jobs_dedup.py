from __future__ import annotations

import uuid

from fastapi.testclient import TestClient

from app.auth.security import hash_password
from app.config import Settings
from app.db import get_connection, transaction, utc_now_iso


def test_job_creation_dedup_idempotency_and_user_isolation(
    auth_client: tuple[TestClient, str],
    test_settings: Settings,
) -> None:
    client, csrf = auth_client

    # Parse URL helper
    parsed = client.post(
        "/api/jobs/parse-url",
        json={"url": "https://www.instagram.com/reel/C12345/?igsh=tracking"},
        headers={"X-CSRF-Token": csrf},
    )
    assert parsed.status_code == 200
    assert parsed.json()["valid"] is True
    assert parsed.json()["platform"] == "instagram"
    assert parsed.json()["normalized_url"] == "https://www.instagram.com/reel/C12345/"

    # Create job
    r1 = client.post(
        "/api/jobs",
        json={
            "url": "https://www.instagram.com/reel/C12345/?igsh=tracking",
            "format": "video",
            "quality": "best",
            "idempotency_key": "idem-1",
        },
        headers={"X-CSRF-Token": csrf},
    )
    assert r1.status_code == 201
    j1 = r1.json()
    assert j1["deduplicated"] is False
    assert j1["original_url"] == "https://www.instagram.com/reel/C12345/?igsh=tracking"
    assert j1["normalized_url"] == "https://www.instagram.com/reel/C12345/"
    assert j1["status"] == "queued"
    assert j1["phase_label"] == "Link detectado"

    # Same URL within 10-minute window deduplicates automatically
    r2 = client.post(
        "/api/jobs",
        json={
            "url": "https://www.instagram.com/reel/C12345/?utm_source=abc",
            "format": "video",
            "quality": "best",
        },
        headers={"X-CSRF-Token": csrf},
    )
    assert r2.status_code == 201
    j2 = r2.json()
    assert j2["id"] == j1["id"]
    assert j2["deduplicated"] is True

    # force_manual=True creates a fresh job
    r3 = client.post(
        "/api/jobs",
        json={
            "url": "https://www.instagram.com/reel/C12345/",
            "format": "video",
            "quality": "best",
            "force_manual": True,
        },
        headers={"X-CSRF-Token": csrf},
    )
    assert r3.status_code == 201
    j3 = r3.json()
    assert j3["id"] != j1["id"]
    assert j3["deduplicated"] is False

    # History lists jobs
    hist = client.get("/api/history?platform=instagram")
    assert hist.status_code == 200
    assert hist.json()["total"] == 2

    # Second user cannot access first user's job
    conn = get_connection(test_settings)
    try:
        uid2 = str(uuid.uuid4())
        now = utc_now_iso()
        with transaction(conn):
            conn.execute(
                "INSERT INTO users (id, username, password_hash, role, is_active, created_at, updated_at) VALUES (?, ?, ?, 'user', 1, ?, ?)",
                (uid2, "otheruser", hash_password("OtherPass!123"), now, now),
            )
    finally:
        conn.close()

    client.post("/api/auth/logout", headers={"X-CSRF-Token": csrf})
    login2 = client.post("/api/auth/login", json={"username": "otheruser", "password": "OtherPass!123"})
    assert login2.status_code == 200
    csrf2 = login2.json()["csrf_token"]

    assert client.get(f"/api/jobs/{j1['id']}").status_code == 404
    assert client.post(f"/api/jobs/{j1['id']}/cancel", headers={"X-CSRF-Token": csrf2}).status_code == 404
    assert client.delete(f"/api/history/{j1['id']}", headers={"X-CSRF-Token": csrf2}).status_code == 404


def test_share_target_flow_keeps_auth_and_hides_url_from_query(client: TestClient) -> None:
    # OS share_target POST before login
    share_resp = client.post(
        "/share-target",
        data={
            "title": "Reel legal",
            "text": "Veja isso https://www.instagram.com/reel/Share999/?igsh=secret_share",
            "url": "",
        },
        follow_redirects=False,
    )
    assert share_resp.status_code == 303
    location = share_resp.headers["location"]
    assert location == "/?shared=1"
    assert "instagram.com" not in location

    # Pending share endpoint requires authentication
    assert client.get("/api/share-target/pending").status_code == 401

    # User logs in
    login = client.post("/api/auth/login", json={"username": "admin", "password": "StrongTestPassword!99"})
    assert login.status_code == 200

    # Pending share is returned once and consumed
    pending = client.get("/api/share-target/pending")
    assert pending.status_code == 200
    pdata = pending.json()
    assert pdata["has_pending"] is True
    assert pdata["url"] == "https://www.instagram.com/reel/Share999/"
    assert pdata["platform"] == "instagram"

    # Second read sees no pending share
    pending2 = client.get("/api/share-target/pending")
    assert pending2.status_code == 200
    assert pending2.json()["has_pending"] is False
