import type { UserSettings } from '../types/api';

const LOCAL_SETTINGS_KEY = 'devwolf_user_settings_v2';
const BEARER_TOKEN_KEY = 'devwolf_vps_bearer_token_v2';

export const DEFAULT_USER_SETTINGS: UserSettings = {
  auto_download: false,
  default_quality: 'best',
  data_saver: false,
  file_retention_hours: 24,
  theme: 'dark',
  enabled_engines: ['yt-dlp', 'gallery-dl', 'cobalt'],
  preferred_execution_mode: 'auto',
  status_mode_enabled: false,
  status_max_duration_seconds: 0,
  status_keep_original: false,
  auto_update_engines: true,
  notifications_enabled: true,
  vps_url: '',
};

export function normalizeVpsUrl(raw: string): string {
  const trimmed = (raw || '').trim().replace(/\/+$/, '');
  if (!trimmed) return '';
  try {
    const parsed = new URL(trimmed);
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      return '';
    }
    return parsed.toString().replace(/\/+$/, '');
  } catch {
    return '';
  }
}

export function loadLocalSettings(): UserSettings {
  try {
    const raw = window.localStorage.getItem(LOCAL_SETTINGS_KEY);
    if (!raw) return { ...DEFAULT_USER_SETTINGS };
    const parsed = JSON.parse(raw) as Partial<UserSettings>;
    return {
      ...DEFAULT_USER_SETTINGS,
      ...parsed,
      enabled_engines:
        Array.isArray(parsed.enabled_engines) && parsed.enabled_engines.length > 0
          ? parsed.enabled_engines
          : DEFAULT_USER_SETTINGS.enabled_engines,
      vps_url: normalizeVpsUrl(parsed.vps_url ?? ''),
    };
  } catch {
    return { ...DEFAULT_USER_SETTINGS };
  }
}

export function saveLocalSettings(settings: UserSettings): void {
  try {
    window.localStorage.setItem(LOCAL_SETTINGS_KEY, JSON.stringify(settings));
  } catch {
    // Ignorar falha de localStorage
  }
}

export function loadVpsBearerToken(): string | null {
  try {
    const token = window.localStorage.getItem(BEARER_TOKEN_KEY);
    return token && token.trim() ? token.trim() : null;
  } catch {
    return null;
  }
}

export function saveVpsBearerToken(token: string | null): void {
  try {
    if (token && token.trim()) {
      window.localStorage.setItem(BEARER_TOKEN_KEY, token.trim());
    } else {
      window.localStorage.removeItem(BEARER_TOKEN_KEY);
    }
  } catch {
    // Ignorar falha de localStorage
  }
}
