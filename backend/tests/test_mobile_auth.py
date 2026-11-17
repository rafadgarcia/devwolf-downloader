from __future__ import annotations

from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.security.url_guard import load_routes_config, validate_and_match_url


def test_app_rebranded_to_devwolf_downloader_v2(client: TestClient) -> None:
    spec = client.get("/openapi.json")
    assert spec.status_code == 200
    info = spec.json()["info"]
    assert info["title"] == "Devwolf Downloader API"
    assert info["version"] == "2.0.0"


def test_bearer_session_token_auth_and_capacitor_origins(test_settings: Settings) -> None:
    app = create_app(test_settings)
    with TestClient(app) as c:
        # Login from Capacitor Android https://localhost origin
        login = c.post(
            "/api/auth/login",
            json={"username": "admin", "password": "StrongTestPassword!99"},
            headers={"Origin": "https://localhost"},
        )
        assert login.status_code == 200, login.text
        data = login.json()
        assert data["authenticated"] is True
        session_token = data["session_token"]
        csrf_token = data["csrf_token"]
        assert session_token and len(session_token) > 20
        assert csrf_token

        # Clear cookies to simulate third-party cookie blocking in Capacitor WebView -> remote VPS
        c.cookies.clear()

        # Without Bearer or cookie -> 401
        assert c.get("/api/settings").status_code == 401
        assert c.get("/api/auth/me").json()["authenticated"] is False

        # Bearer token authenticates /api/auth/me and /api/settings
        me = c.get("/api/auth/me", headers={"Authorization": f"Bearer {session_token}"})
        assert me.status_code == 200
        assert me.json()["authenticated"] is True
        assert me.json()["user"]["username"] == "admin"

        # CORS preflight allows Capacitor origins and Authorization header
        preflight = c.options(
            "/api/jobs",
            headers={
                "Origin": "https://localhost",
                "Access-Control-Request-Method": "POST",
                "Access-Control-Request-Headers": "Authorization,Content-Type,X-CSRF-Token",
            },
        )
        assert preflight.status_code == 200
        assert preflight.headers.get("access-control-allow-origin") == "https://localhost"
        allow_hdrs = preflight.headers.get("access-control-allow-headers", "").lower()
        assert "authorization" in allow_hdrs
        assert "x-csrf-token" in allow_hdrs

        # Mutating endpoints work with Bearer + X-CSRF-Token from capacitor://localhost and http://localhost
        for origin in ("https://localhost", "capacitor://localhost", "http://localhost"):
            patch_res = c.patch(
                "/api/settings",
                json={"preferred_execution_mode": "vps"},
                headers={
                    "Authorization": f"Bearer {session_token}",
                    "X-CSRF-Token": csrf_token,
                    "Origin": origin,
                },
            )
            assert patch_res.status_code == 200
            assert patch_res.json()["preferred_execution_mode"] == "vps"

        # Untrusted origin still blocked with 403
        bad_origin = c.patch(
            "/api/settings",
            json={"preferred_execution_mode": "local"},
            headers={
                "Authorization": f"Bearer {session_token}",
                "X-CSRF-Token": csrf_token,
                "Origin": "https://evil.example",
            },
        )
        assert bad_origin.status_code == 403

        # Logout via Bearer revokes session token
        out = c.post(
            "/api/auth/logout",
            headers={
                "Authorization": f"Bearer {session_token}",
                "X-CSRF-Token": csrf_token,
                "Origin": "https://localhost",
            },
        )
        assert out.status_code == 200
        assert c.get("/api/settings", headers={"Authorization": f"Bearer {session_token}"}).status_code == 401


def test_mobile_origins_blocked_when_disabled(test_settings: Settings) -> None:
    restricted_cfg = test_settings.model_copy(update={"allow_mobile_origins": False})
    app = create_app(restricted_cfg)
    with TestClient(app) as c:
        res = c.post(
            "/api/auth/login",
            json={"username": "admin", "password": "StrongTestPassword!99"},
            headers={"Origin": "https://localhost"},
        )
        assert res.status_code == 403


def test_facebook_reels_and_all_supported_platforms_routing(auth_client: tuple[TestClient, str]) -> None:
    client, _csrf = auth_client
    cfg = load_routes_config()

    fb_urls = [
        ("https://www.facebook.com/reel/1234567890/?mibextid=wwXIfr", "facebook_reel"),
        ("https://www.facebook.com/share/r/1AbCdEfGh/", "facebook_reel"),
        ("https://www.facebook.com/share/v/1XyZ98765/", "facebook"),
        ("https://fb.watch/abcDEF123/", "facebook"),
    ]
    for url, expected_route in fb_urls:
        matched = validate_and_match_url(url, cfg)
        assert matched.route.platform == "facebook"
        assert matched.route.id == expected_route
        assert matched.route.primary_engine == "yt-dlp"

        api_res = client.post("/api/jobs/parse-url", json={"url": url})
        assert api_res.status_code == 200
        assert api_res.json()["valid"] is True
        assert api_res.json()["platform"] == "facebook"
        assert api_res.json()["route_id"] == expected_route

    # Verify all 7 platforms parse cleanly through API
    platform_samples = [
        ("https://www.instagram.com/reel/C9abc/?igsh=1", "instagram"),
        ("https://www.instagram.com/p/C9post/", "instagram"),
        ("https://www.facebook.com/share/r/Abc123/", "facebook"),
        ("https://www.tiktok.com/@user/video/7300000000000000000", "tiktok"),
        ("https://www.youtube.com/shorts/abc123XYZ", "youtube"),
        ("https://x.com/devwolf/status/1234567890", "twitter"),
        ("https://www.reddit.com/r/python/comments/abc123/test/", "reddit"),
        ("https://www.pinterest.com/pin/1234567890/", "pinterest"),
    ]
    for sample_url, expected_plat in platform_samples:
        r = client.post("/api/jobs/parse-url", json={"url": sample_url})
        assert r.status_code == 200
        assert r.json()["valid"] is True
        assert r.json()["platform"] == expected_plat
