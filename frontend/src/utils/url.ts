import type { FormatOption, PlatformId } from '../types/api';

export interface ParsedPlatformUrl {
  originalUrl: string;
  normalizedUrl: string;
  platform: PlatformId;
  platformLabel: string;
  routeLabel: string;
  hostname: string;
  pathname: string;
  isExperimental?: boolean;
  localSupported: boolean;
}

interface DomainRule {
  platform: PlatformId;
  platformLabel: string;
  domains: Set<string>;
  disallowPrefixes?: string[];
}

const TRACKING_PARAMS = new Set([
  'igsh',
  'igshid',
  'utm_source',
  'utm_medium',
  'utm_campaign',
  'utm_term',
  'utm_content',
  'utm_id',
  'utm_name',
  'fbclid',
  'si',
  'feature',
  'ref',
  'ref_src',
  'ref_url',
  'sender_device',
  'is_from_webapp',
  '_r',
  '_t',
  '_d',
  'tt_from',
  'share_app_id',
  'share_link_id',
  'sender_web_id',
  'u_code',
  'sec_uid',
  'share_id',
  'xmt',
  'slof',
  'context',
  'rdt',
  'ref_source',
]);

const PLATFORM_RULES: DomainRule[] = [
  {
    platform: 'instagram',
    platformLabel: 'Instagram',
    domains: new Set(['instagram.com', 'www.instagram.com', 'm.instagram.com', 'instagr.am']),
  },
  {
    platform: 'threads',
    platformLabel: 'Threads',
    domains: new Set(['threads.net', 'www.threads.net', 'threads.com', 'www.threads.com']),
  },
  {
    platform: 'youtube',
    platformLabel: 'YouTube',
    domains: new Set([
      'youtube.com',
      'www.youtube.com',
      'm.youtube.com',
      'music.youtube.com',
      'youtu.be',
    ]),
    disallowPrefixes: ['/playlist', '/channel/', '/c/', '/@', '/feed/', '/results'],
  },
  {
    platform: 'tiktok',
    platformLabel: 'TikTok',
    domains: new Set([
      'tiktok.com',
      'www.tiktok.com',
      'm.tiktok.com',
      'vm.tiktok.com',
      'vt.tiktok.com',
    ]),
  },
  {
    platform: 'twitter',
    platformLabel: 'X / Twitter',
    domains: new Set([
      'x.com',
      'www.x.com',
      'mobile.x.com',
      'twitter.com',
      'www.twitter.com',
      'mobile.twitter.com',
      't.co',
    ]),
  },
  {
    platform: 'facebook',
    platformLabel: 'Facebook',
    domains: new Set([
      'facebook.com',
      'www.facebook.com',
      'm.facebook.com',
      'web.facebook.com',
      'fb.watch',
    ]),
  },
  {
    platform: 'reddit',
    platformLabel: 'Reddit',
    domains: new Set([
      'reddit.com',
      'www.reddit.com',
      'old.reddit.com',
      'new.reddit.com',
      'redd.it',
      'v.redd.it',
    ]),
  },
  {
    platform: 'pinterest',
    platformLabel: 'Pinterest',
    domains: new Set([
      'pinterest.com',
      'www.pinterest.com',
      'br.pinterest.com',
      'pin.it',
    ]),
  },
];

const ALL_ALLOWED_DOMAINS = new Set<string>(
  PLATFORM_RULES.flatMap((rule) => Array.from(rule.domains))
);

function isIpLiteral(hostname: string): boolean {
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(hostname)) {
    return true;
  }
  if (hostname.includes(':') || hostname.startsWith('[')) {
    return true;
  }
  return false;
}

function getRouteInfo(
  platform: PlatformId,
  hostname: string,
  pathname: string
): {
  label: string;
  experimental?: boolean;
  localSupported: boolean;
} {
  if (platform === 'instagram') {
    if (pathname.startsWith('/reel/') || pathname.startsWith('/reels/')) {
      return { label: 'Instagram Reel', localSupported: true };
    }
    if (pathname.startsWith('/p/') || pathname.startsWith('/tv/')) {
      return { label: 'Instagram Post / Carrossel', localSupported: true };
    }
    if (pathname.startsWith('/stories/')) {
      return {
        label: 'Instagram Stories (requer login)',
        localSupported: true,
      };
    }
    return { label: 'Instagram', localSupported: true };
  }
  if (platform === 'threads') {
    return { label: 'Threads', localSupported: true };
  }
  if (platform === 'youtube') {
    if (pathname.startsWith('/post/') || /\/(community|posts)$/.test(pathname.replace(/\/$/, ''))) {
      return { label: 'YouTube Post (imagens)', localSupported: true };
    }
    if (pathname.startsWith('/shorts/')) {
      return { label: 'YouTube Shorts', localSupported: true };
    }
    return { label: 'YouTube', localSupported: true };
  }
  if (platform === 'facebook') {
    if (
      pathname.startsWith('/share/r/') ||
      pathname.startsWith('/reel/') ||
      pathname.startsWith('/reels/')
    ) {
      return { label: 'Facebook Reel', localSupported: true };
    }
    if (
      hostname === 'fb.watch' ||
      pathname.startsWith('/share/v/') ||
      pathname.startsWith('/watch') ||
      pathname.includes('/videos/')
    ) {
      return { label: 'Facebook Vídeo', localSupported: true };
    }
    return { label: 'Facebook', localSupported: true };
  }
  if (platform === 'pinterest') {
    return { label: 'Pinterest', localSupported: true };
  }
  if (platform === 'twitter') {
    return { label: 'X / Twitter', localSupported: true };
  }
  const rule = PLATFORM_RULES.find((item) => item.platform === platform);
  return {
    label: rule?.platformLabel ?? platform,
    localSupported: true,
  };
}

/**
 * Todas as plataformas suportadas (incluindo fotos, carrosséis, Pinterest e Reddit)
 * são executadas 100% localmente no Android para todos os formatos (`auto`, `video`, `image`, `audio`).
 */
export function canUseLocalEngineForRequest(
  parsed: ParsedPlatformUrl,
  _format: FormatOption
): boolean {
  return parsed.localSupported;
}

export function canUseLocalYtdlpForRequest(
  parsed: ParsedPlatformUrl,
  format: FormatOption
): boolean {
  return canUseLocalEngineForRequest(parsed, format);
}

function coerceKnownSchemelessCandidate(candidate: string): string {
  const trimmed = candidate.trim();
  if (/^https?:\/\//i.test(trimmed)) {
    return trimmed;
  }
  if (/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(trimmed)) {
    return trimmed;
  }
  const hostPart = trimmed.split(/[/?#]/)[0]?.toLowerCase() ?? '';
  if (ALL_ALLOWED_DOMAINS.has(hostPart)) {
    return `https://${trimmed}`;
  }
  return trimmed;
}

function stripTrailingPunctuation(raw: string): string {
  return raw.replace(/[),.;!?\]}>'"]+$/g, '');
}

/**
 * Valida uma URL contra a allowlist estrita de domínios suportados, rejeita credenciais/IPs
 * e remove apenas parâmetros de rastreamento conhecidos preservando parâmetros essenciais.
 */
export function parseAndValidateUrl(rawInput: string): ParsedPlatformUrl | null {
  const cleaned = stripTrailingPunctuation(rawInput.trim());
  if (!cleaned || /\s/.test(cleaned)) {
    return null;
  }

  const coerced = coerceKnownSchemelessCandidate(cleaned);

  let parsed: URL;
  try {
    parsed = new URL(coerced);
  } catch {
    return null;
  }

  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    return null;
  }

  if (parsed.username || parsed.password) {
    return null;
  }

  if (parsed.port && parsed.port !== '80' && parsed.port !== '443') {
    return null;
  }

  const hostname = parsed.hostname.toLowerCase().replace(/\.$/, '');
  if (!hostname || isIpLiteral(hostname) || hostname === 'localhost') {
    return null;
  }

  const matchedRule = PLATFORM_RULES.find((rule) => rule.domains.has(hostname));
  if (!matchedRule) {
    return null;
  }

  const pathname = parsed.pathname || '/';
  if (pathname === '/' && !parsed.search) {
    return null;
  }

  // Posts da comunidade do YouTube (`/@canal/community?lb=<id>`, `/channel/<id>/posts?lb=<id>`)
  // são publicações com imagens, não páginas de canal.
  const isYouTubeCommunityPost =
    matchedRule.platform === 'youtube' &&
    (pathname.startsWith('/post/') ||
      (/\/(community|posts)\/?$/.test(pathname) && Boolean(parsed.searchParams.get('lb'))));

  if (
    !isYouTubeCommunityPost &&
    matchedRule.disallowPrefixes?.some((prefix) => pathname.startsWith(prefix))
  ) {
    return null;
  }

  // Validação específica para YouTube: /watch exige parâmetro v
  if (matchedRule.platform === 'youtube' && hostname !== 'youtu.be') {
    if (pathname === '/watch' && !parsed.searchParams.get('v')) {
      return null;
    }
  }

  const normalized = new URL(parsed.toString());
  normalized.protocol = 'https:';
  normalized.hostname = hostname;
  normalized.port = '';
  normalized.hash = '';

  const keysToDelete: string[] = [];
  normalized.searchParams.forEach((_value, key) => {
    const lowerKey = key.toLowerCase();
    if (TRACKING_PARAMS.has(lowerKey) || lowerKey.startsWith('utm_')) {
      keysToDelete.push(key);
    }
  });
  for (const key of keysToDelete) {
    normalized.searchParams.delete(key);
  }

  const sortedParams = Array.from(normalized.searchParams.entries()).sort(([a], [b]) =>
    a.localeCompare(b)
  );
  normalized.search = '';
  for (const [k, v] of sortedParams) {
    normalized.searchParams.append(k, v);
  }

  if (normalized.pathname.length > 1 && normalized.pathname.endsWith('/')) {
    normalized.pathname = normalized.pathname.slice(0, -1);
  }

  const routeInfo = getRouteInfo(matchedRule.platform, hostname, pathname);

  return {
    originalUrl: cleaned,
    normalizedUrl: normalized.toString(),
    platform: matchedRule.platform,
    platformLabel: matchedRule.platformLabel,
    routeLabel: routeInfo.label,
    hostname,
    pathname,
    isExperimental: routeInfo.experimental,
    localSupported: routeInfo.localSupported,
  };
}

export function normalizeUrl(rawInput: string): string | null {
  return parseAndValidateUrl(rawInput)?.normalizedUrl ?? null;
}

/**
 * Extrai localmente todas as URLs de plataformas suportadas a partir de um texto bruto
 * (clipboard ou compartilhamento Android), deduplicadas por normalizedUrl.
 */
export function extractSupportedUrlsFromText(text: string): ParsedPlatformUrl[] {
  if (!text || !text.trim()) {
    return [];
  }

  const seen = new Set<string>();
  const results: ParsedPlatformUrl[] = [];

  const tokenRegex = /(?:https?:\/\/[^\s<>"'`]+)|(?:(?:[a-z0-9-]+\.)+[a-z]{2,}(?:\/[^\s<>"'`]*)?)/gi;
  const matches = text.match(tokenRegex) ?? [];

  for (const rawCandidate of matches) {
    const parsed = parseAndValidateUrl(rawCandidate);
    if (parsed && !seen.has(parsed.normalizedUrl)) {
      seen.add(parsed.normalizedUrl);
      results.push(parsed);
    }
  }

  return results;
}
