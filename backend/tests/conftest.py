from __future__ import annotations

import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

# Ensure backend/ is on sys.path
BACKEND_DIR = Path(__file__).resolve().parent.parent
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from app.auth.security import job_rate_limiter, login_rate_limiter  # noqa: E402
from app.config import Settings, reset_settings_cache  # noqa: E402
from app.db import get_connection, run_migrations  # noqa: E402
from app.main import create_app  # noqa: E402


@pytest.fixture()
def test_settings(tmp_path: Path) -> Settings:
    reset_settings_cache()
    login_rate_limiter.clear()
    job_rate_limiter.clear()

    repo_root = BACKEND_DIR.parent
    routes_path = repo_root / "config" / "routes.yaml"

    return Settings(
        APP_ENV="development",
        APP_URL="http://testserver",
        ADMIN_USERNAME="admin",
        ADMIN_PASSWORD="StrongTestPassword!99",
        SESSION_SECRET="test-session-secret-0123456789abcdef",
        COOKIE_SECURE=False,
        DATABASE_PATH=str(tmp_path / "data" / "droplink.db"),
        STORAGE_DIR=str(tmp_path / "storage"),
        TMP_DIR=str(tmp_path / "tmp"),
        ROUTES_CONFIG_PATH=str(routes_path) if routes_path.exists() else "",
        MIN_FREE_DISK_MB=1,
        FILE_RETENTION_HOURS=24,
        HISTORY_RETENTION_DAYS=30,
        DEDUP_WINDOW_SECONDS=600,
        CIRCUIT_BREAKER_THRESHOLD=3,
        CIRCUIT_BREAKER_COOLDOWN_SECONDS=300,
        EMBEDDED_WORKER=False,
    )


@pytest.fixture()
def db_conn(test_settings: Settings):
    run_migrations(test_settings)
    conn = get_connection(test_settings)
    try:
        yield conn
    finally:
        conn.close()


@pytest.fixture()
def client(test_settings: Settings):
    app = create_app(test_settings)
    with TestClient(app) as c:
        yield c


@pytest.fixture()
def auth_client(client: TestClient) -> tuple[TestClient, str]:
    resp = client.post(
        "/api/auth/login",
        json={"username": "admin", "password": "StrongTestPassword!99"},
    )
    assert resp.status_code == 200, resp.text
    csrf_token = resp.json()["csrf_token"]
    assert csrf_token
    return client, csrf_token
