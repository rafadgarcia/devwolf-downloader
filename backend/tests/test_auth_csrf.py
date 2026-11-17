from __future__ import annotations

from pathlib import Path

from fastapi.testclient import TestClient

from app.auth.security import ensure_initial_admin
from app.config import Settings
from app.db import get_connection, run_migrations
from app.main import create_app


def test_login_me_logout_and_csrf_enforcement(client: TestClient) -> None:
    # Unauthenticated /me
    me_unauth = client.get("/api/auth/me")
    assert me_unauth.status_code == 200
    assert me_unauth.json()["authenticated"] is False

    # Protected routes fail without session
    assert client.get("/api/settings").status_code == 401
    assert client.get("/api/history").status_code == 401

    # Wrong password returns 401
    bad = client.post("/api/auth/login", json={"username": "admin", "password": "wrong"})
    assert bad.status_code == 401

    # Valid login sets HttpOnly, SameSite=Lax cookie
    ok = client.post("/api/auth/login", json={"username": "admin", "password": "StrongTestPassword!99"})
    assert ok.status_code == 200
    body = ok.json()
    assert body["authenticated"] is True
    assert body["user"]["username"] == "admin"
    csrf_token = body["csrf_token"]
    assert csrf_token

    set_cookie = ok.headers.get("set-cookie", "").lower()
    assert "httponly" in set_cookie
    assert "samesite=lax" in set_cookie

    # Mutating endpoint without CSRF fails 403
    no_csrf = client.patch("/api/settings", json={"auto_download": False})
    assert no_csrf.status_code == 403

    # Mutating endpoint with wrong Origin fails 403
    bad_origin = client.patch(
        "/api/settings",
        json={"auto_download": False},
        headers={"X-CSRF-Token": csrf_token, "Origin": "https://evil.example"},
    )
    assert bad_origin.status_code == 403

    # Valid CSRF succeeds
    good = client.patch(
        "/api/settings",
        json={"auto_download": False, "theme": "light"},
        headers={"X-CSRF-Token": csrf_token},
    )
    assert good.status_code == 200
    assert good.json()["auto_download"] is False
    assert good.json()["theme"] == "light"

    # Logout revokes session
    out = client.post("/api/auth/logout", headers={"X-CSRF-Token": csrf_token})
    assert out.status_code == 200
    assert client.get("/api/auth/me").json()["authenticated"] is False
    assert client.get("/api/settings").status_code == 401


def test_login_rate_limiting(test_settings: Settings) -> None:
    limited_cfg = test_settings.model_copy(update={"login_rate_limit_max": 3, "login_rate_limit_window_seconds": 60})
    app = create_app(limited_cfg)
    with TestClient(app) as c:
        for _ in range(3):
            r = c.post("/api/auth/login", json={"username": "admin", "password": "wrong"})
            assert r.status_code == 401
        blocked = c.post("/api/auth/login", json={"username": "admin", "password": "wrong"})
        assert blocked.status_code == 429


def test_initial_admin_without_hardcoded_password(tmp_path: Path) -> None:
    cfg = Settings(
        ADMIN_USERNAME="admin",
        ADMIN_PASSWORD=None,
        DATABASE_PATH=str(tmp_path / "data" / "droplink.db"),
        STORAGE_DIR=str(tmp_path / "storage"),
        TMP_DIR=str(tmp_path / "tmp"),
        COOKIE_SECURE=False,
    )
    run_migrations(cfg)
    conn = get_connection(cfg)
    try:
        ensure_initial_admin(conn, cfg)
        token_file = Path(cfg.resolved_storage_dir()) / ".initial_setup_token"
        assert token_file.exists()
        content = token_file.read_text(encoding="utf-8")
        assert "initial_password=" in content
        assert "123456" not in content
        assert "password=admin" not in content
    finally:
        conn.close()
