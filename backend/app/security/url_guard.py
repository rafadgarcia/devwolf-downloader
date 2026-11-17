from __future__ import annotations

import ipaddress
import re
import socket
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional
from urllib.parse import parse_qsl, urlencode, urljoin, urlsplit, urlunsplit

import httpx
import yaml

from app.config import Settings, get_settings


class UrlValidationError(ValueError):
    """Raised when a URL fails syntax, allowlist, or SSRF validation."""

    def __init__(self, message: str, code: str = "invalid_url") -> None:
        super().__init__(message)
        self.code = code


DEFAULT_ROUTES_DICT: dict[str, Any] = {
    "version": 1,
    "limits": {"max_redirect_hops": 5, "max_carousel_items": 50},
    "tracking_params_strip": [
        "igsh",
        "igshid",
        "utm_source",
        "utm_medium",
        "utm_campaign",
        "utm_term",
        "utm_content",
        "utm_id",
        "utm_name",
        "fbclid",
        "si",
        "feature",
        "ref",
        "ref_src",
        "ref_url",
        "sender_device",
        "is_from_webapp",
        "_r",
        "_t",
        "share_id",
    ],
    "preserved_params": ["v", "t", "list", "index", "story_fbid", "id", "fbid", "set"],
    "short_domains": {
        "youtu.be": "youtube",
        "instagr.am": "instagram",
        "vm.tiktok.com": "tiktok",
        "vt.tiktok.com": "tiktok",
        "t.co": "twitter",
        "fb.watch": "facebook",
        "redd.it": "reddit",
        "pin.it": "pinterest",
    },
    "routes": [
        {
            "id": "instagram_reel",
            "platform": "instagram",
            "label": "Instagram Reel",
            "domains": ["instagram.com", "www.instagram.com", "m.instagram.com", "instagr.am"],
            "path_prefixes": ["/reel/", "/reels/"],
            "media_types": ["video"],
            "primary_engine": "yt-dlp",
            "fallback_engines": ["gallery-dl", "cobalt"],
            "experimental": False,
            "allow_playlists": False,
        },
        {
            "id": "instagram_post",
            "platform": "instagram",
            "label": "Instagram Post / TV",
            "domains": ["instagram.com", "www.instagram.com", "m.instagram.com", "instagr.am"],
            "path_prefixes": ["/p/", "/tv/"],
            "media_types": ["image", "carousel", "video"],
            "primary_engine": "gallery-dl",
            "fallback_engines": ["cobalt", "yt-dlp"],
            "engine_constraints": {"yt-dlp": {"video_only": True}},
            "experimental": False,
            "allow_playlists": False,
        },
        {
            "id": "instagram_stories",
            "platform": "instagram",
            "label": "Instagram Stories (experimental)",
            "domains": ["instagram.com", "www.instagram.com", "m.instagram.com"],
            "path_prefixes": ["/stories/"],
            "media_types": ["image", "video"],
            "primary_engine": "gallery-dl",
            "fallback_engines": [],
            "experimental": True,
            "public_only": True,
            "allow_playlists": False,
        },
        {
            "id": "youtube",
            "platform": "youtube",
            "label": "YouTube",
            "domains": [
                "youtube.com",
                "www.youtube.com",
                "m.youtube.com",
                "music.youtube.com",
                "youtu.be",
            ],
            "path_prefixes": ["/watch", "/shorts/", "/live/", "/embed/", "/"],
            "disallow_path_prefixes": ["/playlist", "/channel/", "/c/", "/@", "/feed/", "/results"],
            "media_types": ["video", "audio"],
            "primary_engine": "yt-dlp",
            "fallback_engines": ["cobalt"],
            "experimental": False,
            "allow_playlists": False,
        },
        {
            "id": "tiktok",
            "platform": "tiktok",
            "label": "TikTok",
            "domains": ["tiktok.com", "www.tiktok.com", "m.tiktok.com", "vm.tiktok.com", "vt.tiktok.com"],
            "path_prefixes": ["/"],
            "media_types": ["video", "image", "carousel"],
            "primary_engine": "cobalt",
            "fallback_engines": ["yt-dlp", "gallery-dl"],
            "experimental": False,
            "allow_playlists": False,
        },
        {
            "id": "twitter",
            "platform": "twitter",
            "label": "X / Twitter",
            "domains": [
                "x.com",
                "www.x.com",
                "mobile.x.com",
                "twitter.com",
                "www.twitter.com",
                "mobile.twitter.com",
                "t.co",
            ],
            "path_prefixes": ["/"],
            "media_types": ["image", "carousel", "video"],
            "primary_engine": "gallery-dl",
            "fallback_engines": ["cobalt", "yt-dlp"],
            "engine_constraints": {"yt-dlp": {"video_only": True}},
            "experimental": False,
            "allow_playlists": False,
        },
        {
            "id": "facebook",
            "platform": "facebook",
            "label": "Facebook",
            "domains": ["facebook.com", "www.facebook.com", "m.facebook.com", "web.facebook.com", "fb.watch"],
            "path_prefixes": ["/"],
            "media_types": ["video"],
            "primary_engine": "yt-dlp",
            "fallback_engines": ["cobalt"],
            "experimental": False,
            "allow_playlists": False,
        },
        {
            "id": "reddit",
            "platform": "reddit",
            "label": "Reddit",
            "domains": ["reddit.com", "www.reddit.com", "old.reddit.com", "new.reddit.com", "redd.it", "v.redd.it"],
            "path_prefixes": ["/"],
            "media_types": ["video", "image", "carousel"],
            "primary_engine": "yt-dlp",
            "fallback_engines": ["cobalt", "gallery-dl"],
            "experimental": False,
            "allow_playlists": False,
        },
        {
            "id": "pinterest",
            "platform": "pinterest",
            "label": "Pinterest",
            "domains": ["pinterest.com", "www.pinterest.com", "br.pinterest.com", "pin.it"],
            "path_prefixes": ["/"],
            "media_types": ["image", "video"],
            "primary_engine": "gallery-dl",
            "fallback_engines": ["cobalt", "yt-dlp"],
            "engine_constraints": {"yt-dlp": {"video_only": True}},
            "experimental": False,
            "allow_playlists": False,
        },
    ],
}


@dataclass(frozen=True)
class RouteRule:
    id: str
    platform: str
    label: str
    domains: frozenset[str]
    path_prefixes: tuple[str, ...]
    disallow_path_prefixes: tuple[str, ...]
    media_types: tuple[str, ...]
    primary_engine: str
    fallback_engines: tuple[str, ...]
    engine_constraints: dict[str, dict[str, Any]]
    experimental: bool = False
    public_only: bool = False
    allow_playlists: bool = False


@dataclass(frozen=True)
class RoutesConfig:
    max_redirect_hops: int
    max_carousel_items: int
    tracking_params_strip: frozenset[str]
    short_domains: dict[str, str]
    routes: tuple[RouteRule, ...]
    all_allowed_domains: frozenset[str]


@dataclass(frozen=True)
class MatchedRoute:
    normalized_url: str
    hostname: str
    route: RouteRule
    is_short_domain: bool


_SCHEME_PREFIX_RE = re.compile(r"^[a-zA-Z][a-zA-Z0-9+.-]*:")
_URL_IN_TEXT_RE = re.compile(r"https?://[^\s<>\"'`]+", re.IGNORECASE)
_CONTROL_CHAR_RE = re.compile(r"[\x00-\x1f\x7f]")
_HEX_OR_INT_IP_RE = re.compile(r"^(?:0x[0-9a-f]+|\d+|0[0-7]+)$", re.IGNORECASE)


def load_routes_config(settings: Settings | None = None) -> RoutesConfig:
    cfg = settings or get_settings()
    path: Path = cfg.resolved_routes_config_path()
    data: dict[str, Any]
    if path.exists():
        loaded = yaml.safe_load(path.read_text(encoding="utf-8"))
        data = loaded if isinstance(loaded, dict) else DEFAULT_ROUTES_DICT
    else:
        data = DEFAULT_ROUTES_DICT

    limits = data.get("limits") or {}
    tracking = frozenset(str(p).lower() for p in (data.get("tracking_params_strip") or []))
    short_domains = {str(k).lower(): str(v).lower() for k, v in (data.get("short_domains") or {}).items()}
    rules: list[RouteRule] = []
    all_domains: set[str] = set(short_domains.keys())

    for r in data.get("routes") or []:
        domains = frozenset(str(d).lower().strip() for d in (r.get("domains") or []))
        all_domains.update(domains)
        rules.append(
            RouteRule(
                id=str(r["id"]),
                platform=str(r["platform"]).lower(),
                label=str(r.get("label") or r["platform"]),
                domains=domains,
                path_prefixes=tuple(str(p) for p in (r.get("path_prefixes") or ["/"])),
                disallow_path_prefixes=tuple(str(p) for p in (r.get("disallow_path_prefixes") or [])),
                media_types=tuple(str(m) for m in (r.get("media_types") or [])),
                primary_engine=str(r["primary_engine"]),
                fallback_engines=tuple(str(e) for e in (r.get("fallback_engines") or [])),
                engine_constraints=dict(r.get("engine_constraints") or {}),
                experimental=bool(r.get("experimental", False)),
                public_only=bool(r.get("public_only", False)),
                allow_playlists=bool(r.get("allow_playlists", False)),
            )
        )

    return RoutesConfig(
        max_redirect_hops=int(limits.get("max_redirect_hops", 5)),
        max_carousel_items=int(limits.get("max_carousel_items", 50)),
        tracking_params_strip=tracking,
        short_domains=short_domains,
        routes=tuple(rules),
        all_allowed_domains=frozenset(all_domains),
    )


def _is_ip_literal(hostname: str) -> bool:
    cleaned = hostname.strip("[]")
    try:
        ipaddress.ip_address(cleaned)
        return True
    except ValueError:
        pass
    # Catch integer / hex / octal IPv4 obfuscation (e.g. 2130706433, 0x7f000001, 0177.0.0.1)
    labels = cleaned.split(".")
    if all(_HEX_OR_INT_IP_RE.match(lbl) for lbl in labels if lbl):
        return True
    return False


def _is_forbidden_ip(ip_obj: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    if isinstance(ip_obj, ipaddress.IPv6Address) and ip_obj.ipv4_mapped is not None:
        return _is_forbidden_ip(ip_obj.ipv4_mapped)
    return bool(
        ip_obj.is_private
        or ip_obj.is_loopback
        or ip_obj.is_link_local
        or ip_obj.is_multicast
        or ip_obj.is_reserved
        or ip_obj.is_unspecified
        or not ip_obj.is_global
    )


def _assert_safe_hostname_syntax(hostname: Optional[str]) -> str:
    if not hostname:
        raise UrlValidationError("URL sem domínio válido.", code="missing_host")
    host = hostname.strip().lower().rstrip(".")
    if not host or len(host) > 253:
        raise UrlValidationError("Domínio inválido.", code="invalid_host")
    if host in {"localhost", "ip6-localhost", "ip6-loopback"} or host.endswith(
        (".localhost", ".local", ".internal", ".arpa", ".lan", ".home")
    ):
        raise UrlValidationError("Host interno não permitido.", code="ssrf_blocked")
    if _is_ip_literal(host):
        raise UrlValidationError("Endereços IP literais não são permitidos.", code="ssrf_blocked")
    try:
        ascii_host = host.encode("idna").decode("ascii")
    except UnicodeError as exc:
        raise UrlValidationError("Domínio com codificação inválida.", code="invalid_host") from exc
    if ".." in ascii_host or not re.match(r"^[a-z0-9.-]+$", ascii_host):
        raise UrlValidationError("Caracteres inválidos no domínio.", code="invalid_host")
    return ascii_host


def verify_host_public_dns(hostname: str, port: int = 443) -> list[str]:
    """Resolve DNS and verify every returned IP is globally routable (anti-SSRF / DNS rebinding)."""
    try:
        addr_info = socket.getaddrinfo(hostname, port, type=socket.SOCK_STREAM)
    except socket.gaierror as exc:
        raise UrlValidationError(f"Não foi possível resolver DNS para {hostname}.", code="dns_error") from exc

    resolved_ips: list[str] = []
    for entry in addr_info:
        sockaddr = entry[4]
        raw_ip = sockaddr[0].split("%")[0]
        try:
            ip_obj = ipaddress.ip_address(raw_ip)
        except ValueError as exc:
            raise UrlValidationError("Endereço IP resolvido inválido.", code="ssrf_blocked") from exc
        if _is_forbidden_ip(ip_obj):
            raise UrlValidationError(
                "Acesso bloqueado: o destino resolve para rede privada ou reservada.",
                code="ssrf_blocked",
            )
        if raw_ip not in resolved_ips:
            resolved_ips.append(raw_ip)

    if not resolved_ips:
        raise UrlValidationError("Nenhum IP público encontrado para o domínio.", code="dns_error")
    return resolved_ips


def _parse_and_check_basic_url(
    raw_url: str,
    *,
    allow_schemeless_domains: frozenset[str] | None = None,
) -> tuple[str, str, str, str, str]:
    cleaned = raw_url.strip()
    if not cleaned or len(cleaned) > 2048 or _CONTROL_CHAR_RE.search(cleaned):
        raise UrlValidationError("URL vazia ou contém caracteres inválidos.", code="invalid_url")
    if any(ch.isspace() for ch in cleaned):
        raise UrlValidationError("URL não pode conter espaços.", code="invalid_url")

    if "://" not in cleaned:
        if _SCHEME_PREFIX_RE.match(cleaned):
            raise UrlValidationError("Esquema de URL não permitido. Use https://.", code="invalid_scheme")
        candidate_host = cleaned.split("/", 1)[0].split("?", 1)[0].split("#", 1)[0].lower()
        if allow_schemeless_domains and candidate_host in allow_schemeless_domains:
            cleaned = f"https://{cleaned}"
        else:
            raise UrlValidationError("URL deve iniciar com https:// e pertencer a uma plataforma suportada.", code="invalid_scheme")

    parsed = urlsplit(cleaned)
    scheme = parsed.scheme.lower()
    if scheme not in {"http", "https"}:
        raise UrlValidationError("Apenas URLs http/https são permitidas.", code="invalid_scheme")
    if parsed.username is not None or parsed.password is not None:
        raise UrlValidationError("Credenciais embutidas na URL não são permitidas.", code="embedded_credentials")
    try:
        port = parsed.port
    except ValueError as exc:
        raise UrlValidationError("Porta inválida na URL.", code="invalid_port") from exc
    if port is not None and port not in {80, 443}:
        raise UrlValidationError("Porta não permitida na URL.", code="invalid_port")

    host = _assert_safe_hostname_syntax(parsed.hostname)
    path = parsed.path or "/"
    return scheme, host, path, parsed.query, parsed.fragment


def strip_tracking_params(query: str, tracking_params: frozenset[str]) -> str:
    if not query:
        return ""
    kept: list[tuple[str, str]] = []
    for k, v in parse_qsl(query, keep_blank_values=True):
        kl = k.lower()
        if kl in tracking_params or kl.startswith("utm_"):
            continue
        kept.append((k, v))
    return urlencode(kept, doseq=True)


def _path_matches_prefix(path: str, prefix: str) -> bool:
    if prefix == "/":
        return True
    if prefix.endswith(("/", "@")):
        return path.startswith(prefix) or path == prefix.rstrip("/")
    return path == prefix or path.startswith(prefix + "/") or path.startswith(prefix + "?")


def validate_and_match_url(
    raw_url: str,
    routes_cfg: RoutesConfig | None = None,
    *,
    verify_dns: bool = False,
) -> MatchedRoute:
    cfg = routes_cfg or load_routes_config()
    _scheme, host, path, query, _fragment = _parse_and_check_basic_url(
        raw_url,
        allow_schemeless_domains=cfg.all_allowed_domains,
    )

    if host not in cfg.all_allowed_domains:
        raise UrlValidationError(
            f"Domínio não suportado ({host}). Apenas plataformas configuradas são aceitas.",
            code="unsupported_domain",
        )

    is_short = host in cfg.short_domains

    # Normalize query params (strip known tracking, preserve essential IDs)
    clean_query = strip_tracking_params(query, cfg.tracking_params_strip)
    normalized_scheme = "https"
    normalized_url = urlunsplit((normalized_scheme, host, path, clean_query, ""))

    # Find first matching route rule
    matched_rule: Optional[RouteRule] = None
    for rule in cfg.routes:
        if host not in rule.domains:
            continue
        if any(_path_matches_prefix(path, dis) for dis in rule.disallow_path_prefixes):
            raise UrlValidationError(
                "Playlists, canais e páginas de perfil não são suportados no MVP.",
                code="unsupported_path",
            )
        if any(_path_matches_prefix(path, pref) for pref in rule.path_prefixes):
            matched_rule = rule
            break

    if matched_rule is None:
        raise UrlValidationError(
            f"Caminho não suportado para {host}.",
            code="unsupported_path",
        )

    # Extra guard against root homepage without media identifier on non-short domains
    if not is_short and path in {"", "/"} and not clean_query:
        raise UrlValidationError(
            "Informe o link específico de um vídeo, reel ou publicação.",
            code="missing_media_id",
        )

    if verify_dns:
        verify_host_public_dns(host, 443)

    return MatchedRoute(
        normalized_url=normalized_url,
        hostname=host,
        route=matched_rule,
        is_short_domain=is_short,
    )


def validate_external_media_url(
    raw_url: str,
    *,
    cobalt_api_url: str = "",
    verify_dns: bool = True,
) -> tuple[str, bool]:
    """Validate an outbound download URL (e.g., Cobalt tunnel or external CDN redirect).

    Returns (validated_url, is_trusted_cobalt_origin).
    """
    cleaned = raw_url.strip()
    if not cleaned or len(cleaned) > 4096 or _CONTROL_CHAR_RE.search(cleaned):
        raise UrlValidationError("URL de mídia inválida.", code="invalid_media_url")

    parsed = urlsplit(cleaned)
    scheme = parsed.scheme.lower()
    if scheme not in {"http", "https"}:
        raise UrlValidationError("Esquema de mídia inválido.", code="ssrf_blocked")
    if parsed.username is not None or parsed.password is not None:
        raise UrlValidationError("Credenciais embutidas na URL de mídia.", code="ssrf_blocked")

    # Exception: exact origin of configured Cobalt instance (internal docker network/localhost allowed)
    if cobalt_api_url:
        c_parsed = urlsplit(cobalt_api_url.strip())
        if (
            scheme == c_parsed.scheme.lower()
            and (parsed.hostname or "").lower() == (c_parsed.hostname or "").lower()
            and (parsed.port or (443 if scheme == "https" else 80))
            == (c_parsed.port or (443 if c_parsed.scheme.lower() == "https" else 80))
        ):
            return cleaned, True

    port = parsed.port or (443 if scheme == "https" else 80)
    if port not in {80, 443}:
        raise UrlValidationError("Porta externa não permitida para mídia.", code="ssrf_blocked")

    host = _assert_safe_hostname_syntax(parsed.hostname)
    if verify_dns:
        verify_host_public_dns(host, port)
    return cleaned, False


def extract_first_supported_url(text: str, routes_cfg: RoutesConfig | None = None) -> Optional[str]:
    """Safely extract the first recognized platform URL from shared text (e.g. Android share_target)."""
    if not text:
        return None
    cfg = routes_cfg or load_routes_config()
    for match in _URL_IN_TEXT_RE.finditer(text):
        candidate = match.group(0).rstrip(".,;:!?)")
        try:
            matched = validate_and_match_url(candidate, cfg, verify_dns=False)
            return matched.normalized_url
        except UrlValidationError:
            continue
    return None


async def resolve_redirects_safely(
    url: str,
    routes_cfg: RoutesConfig | None = None,
    *,
    cobalt_api_url: str = "",
    require_allowed_domain: bool = True,
    verify_dns: bool = True,
    timeout_seconds: float = 10.0,
) -> str:
    """Follow HTTP redirects manually, validating SSRF & IP resolution on every hop."""
    cfg = routes_cfg or load_routes_config()
    current_url = url
    max_hops = cfg.max_redirect_hops

    async with httpx.AsyncClient(
        follow_redirects=False,
        timeout=httpx.Timeout(timeout_seconds),
        headers={"User-Agent": "DropLink/1.0"},
    ) as client:
        for _hop in range(max_hops):
            if require_allowed_domain:
                matched = validate_and_match_url(current_url, cfg, verify_dns=verify_dns)
                current_url = matched.normalized_url
            else:
                current_url, _is_cobalt = validate_external_media_url(
                    current_url,
                    cobalt_api_url=cobalt_api_url,
                    verify_dns=verify_dns,
                )

            resp = await client.head(current_url)
            if resp.status_code in {405, 501}:
                resp = await client.get(current_url)

            if resp.status_code in {301, 302, 303, 307, 308}:
                location = resp.headers.get("location")
                if not location:
                    return current_url
                next_url = urljoin(current_url, location.strip())
                current_url = next_url
                continue
            return current_url

    raise UrlValidationError("Número máximo de redirecionamentos excedido.", code="too_many_redirects")
