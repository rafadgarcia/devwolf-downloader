from __future__ import annotations

import socket
from pathlib import Path

import pytest

from app.security.file_guard import (
    FileSecurityError,
    create_safe_zip,
    detect_mime_from_file,
    ensure_within_dir,
    sanitize_filename,
)
from app.security.url_guard import (
    UrlValidationError,
    extract_first_supported_url,
    load_routes_config,
    resolve_redirects_safely,
    validate_and_match_url,
    validate_external_media_url,
    verify_host_public_dns,
)


def test_valid_routes_and_tracking_param_stripping() -> None:
    cfg = load_routes_config()

    reel = validate_and_match_url(
        "https://www.instagram.com/reel/ABC123xyz/?igsh=track123&utm_source=ig_web_copy_link",
        cfg,
    )
    assert reel.route.id == "instagram_reel"
    assert reel.route.primary_engine == "yt-dlp"
    assert "igsh=" not in reel.normalized_url
    assert "utm_source=" not in reel.normalized_url
    assert reel.normalized_url == "https://www.instagram.com/reel/ABC123xyz/"

    yt = validate_and_match_url(
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ&si=tracking123&t=42",
        cfg,
    )
    assert yt.route.id == "youtube"
    assert "v=dQw4w9WgXcQ" in yt.normalized_url
    assert "t=42" in yt.normalized_url
    assert "si=" not in yt.normalized_url

    tk = validate_and_match_url("vm.tiktok.com/ZMj12345/", cfg)
    assert tk.route.id == "tiktok"
    assert tk.route.primary_engine == "cobalt"
    assert tk.is_short_domain is True

    x_post = validate_and_match_url("https://x.com/user/status/123456789?utm_medium=social", cfg)
    assert x_post.route.id == "twitter"
    assert x_post.route.primary_engine == "gallery-dl"


@pytest.mark.parametrize(
    "bad_url",
    [
        "https://instagram.com.evil.com/reel/123/",
        "https://evil-instagram.com/reel/123/",
        "https://user:pass@instagram.com/reel/123/",
        "http://127.0.0.1/reel/123/",
        "http://169.254.169.254/latest/meta-data",
        "http://10.0.0.1/watch?v=123",
        "http://[::1]/watch?v=123",
        "http://2130706433/watch?v=123",
        "http://0x7f000001/watch?v=123",
        "https://localhost/watch?v=123",
        "https://metadata.google.internal/computeMetadata/v1/",
        "javascript:alert(1)",
        "file:///etc/passwd",
        "ftp://instagram.com/reel/123/",
        "https://instagram.com:8443/reel/123/",
        "https://youtube.com/playlist?list=PL123",
        "https://youtube.com/@somechannel",
        "https://youtube.com/channel/UC123",
        "https://example.com/video.mp4",
    ],
)
def test_ssrf_and_impostor_urls_rejected(bad_url: str) -> None:
    cfg = load_routes_config()
    with pytest.raises(UrlValidationError):
        validate_and_match_url(bad_url, cfg)


def test_dns_rebinding_private_ip_blocked(monkeypatch: pytest.MonkeyPatch) -> None:
    def fake_getaddrinfo(host, port, type=0):
        return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("127.0.0.1", port))]

    monkeypatch.setattr(socket, "getaddrinfo", fake_getaddrinfo)
    with pytest.raises(UrlValidationError) as exc_info:
        verify_host_public_dns("instagram.com", 443)
    assert exc_info.value.code == "ssrf_blocked"

    def fake_ipv6_mapped(host, port, type=0):
        return [(socket.AF_INET6, socket.SOCK_STREAM, 6, "", ("::ffff:169.254.169.254", port, 0, 0))]

    monkeypatch.setattr(socket, "getaddrinfo", fake_ipv6_mapped)
    with pytest.raises(UrlValidationError) as exc_info6:
        verify_host_public_dns("instagram.com", 443)
    assert exc_info6.value.code == "ssrf_blocked"


def test_external_media_url_allows_only_configured_cobalt_internal_origin() -> None:
    url, is_cobalt = validate_external_media_url(
        "http://cobalt:9000/tunnel?id=abc",
        cobalt_api_url="http://cobalt:9000",
        verify_dns=False,
    )
    assert is_cobalt is True
    assert url.startswith("http://cobalt:9000/")

    with pytest.raises(UrlValidationError):
        validate_external_media_url(
            "http://169.254.169.254/latest/meta-data",
            cobalt_api_url="http://cobalt:9000",
            verify_dns=False,
        )


@pytest.mark.asyncio
async def test_redirect_to_internal_ip_is_blocked(monkeypatch: pytest.MonkeyPatch) -> None:
    import httpx

    cfg = load_routes_config()
    monkeypatch.setattr(
        "app.security.url_guard.verify_host_public_dns",
        lambda host, port=443: ["93.184.216.34"],
    )

    class FakeClient:
        def __init__(self, *args, **kwargs):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            pass

        async def head(self, url):
            return httpx.Response(302, headers={"Location": "http://169.254.169.254/latest/meta-data"})

    monkeypatch.setattr(httpx, "AsyncClient", FakeClient)

    with pytest.raises(UrlValidationError):
        await resolve_redirects_safely("https://youtu.be/dQw4w9WgXcQ", cfg, verify_dns=True)


def test_extract_first_supported_url_from_share_text() -> None:
    cfg = load_routes_config()
    text = "Olha esse vídeo incrível https://evil.com/phish e também https://www.instagram.com/reel/XYZ987/?igsh=abc"
    extracted = extract_first_supported_url(text, cfg)
    assert extracted == "https://www.instagram.com/reel/XYZ987/"


def test_file_guard_sanitization_and_security(tmp_path: Path) -> None:
    assert sanitize_filename("../../etc/passwd") == "passwd"
    assert "/" not in sanitize_filename("foo/bar\\baz.mp4")

    base = tmp_path / "workspace"
    base.mkdir()
    outside = tmp_path / "secret.txt"
    outside.write_text("secret", encoding="utf-8")

    with pytest.raises(FileSecurityError):
        ensure_within_dir(base, outside)

    html_file = base / "fake.mp4"
    html_file.write_bytes(b"<!DOCTYPE html><html><script>alert(1)</script></html>")
    with pytest.raises(FileSecurityError):
        detect_mime_from_file(html_file)

    jpg_file = base / "photo.jpg"
    jpg_file.write_bytes(b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00")
    assert detect_mime_from_file(jpg_file) == "image/jpeg"

    zip_path = base / "out.zip"
    _, count = create_safe_zip([jpg_file], zip_path, base)
    assert count == 1
    assert detect_mime_from_file(zip_path) == "application/zip"
