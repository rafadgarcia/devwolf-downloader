import type { UserSettings } from '../types/api';

const LOCAL_SETTINGS_KEY = 'devwolf_user_settings_v3';
const LEGACY_SETTINGS_KEY = 'devwolf_user_settings_v2';

export const DEFAULT_USER_SETTINGS: UserSettings = {
  auto_download: false,
  default_quality: 'best',
  data_saver: false,
  theme: 'dark',
  status_mode_enabled: false,
  status_max_duration_seconds: 0,
  status_keep_original: false,
  auto_update_engines: true,
  update_channel: 'stable',
  notifications_enabled: true,
  clipboard_bubble_enabled: false,
  include_prerelease_updates: true,
};

export function loadLocalSettings(): UserSettings {
  try {
    const raw =
      window.localStorage.getItem(LOCAL_SETTINGS_KEY) ??
      window.localStorage.getItem(LEGACY_SETTINGS_KEY);
    if (!raw) return { ...DEFAULT_USER_SETTINGS };
    const parsed = JSON.parse(raw) as Partial<UserSettings>;
    return {
      auto_download:
        typeof parsed.auto_download === 'boolean'
          ? parsed.auto_download
          : DEFAULT_USER_SETTINGS.auto_download,
      default_quality: parsed.default_quality ?? DEFAULT_USER_SETTINGS.default_quality,
      data_saver:
        typeof parsed.data_saver === 'boolean'
          ? parsed.data_saver
          : DEFAULT_USER_SETTINGS.data_saver,
      theme: parsed.theme === 'light' ? 'light' : 'dark',
      status_mode_enabled:
        typeof parsed.status_mode_enabled === 'boolean'
          ? parsed.status_mode_enabled
          : DEFAULT_USER_SETTINGS.status_mode_enabled,
      status_max_duration_seconds:
        parsed.status_max_duration_seconds === 30 ||
        parsed.status_max_duration_seconds === 45 ||
        parsed.status_max_duration_seconds === 60
          ? parsed.status_max_duration_seconds
          : 0,
      status_keep_original:
        typeof parsed.status_keep_original === 'boolean'
          ? parsed.status_keep_original
          : DEFAULT_USER_SETTINGS.status_keep_original,
      auto_update_engines:
        typeof parsed.auto_update_engines === 'boolean'
          ? parsed.auto_update_engines
          : DEFAULT_USER_SETTINGS.auto_update_engines,
      update_channel: parsed.update_channel === 'nightly' ? 'nightly' : 'stable',
      notifications_enabled:
        typeof parsed.notifications_enabled === 'boolean'
          ? parsed.notifications_enabled
          : DEFAULT_USER_SETTINGS.notifications_enabled,
      clipboard_bubble_enabled:
        typeof parsed.clipboard_bubble_enabled === 'boolean'
          ? parsed.clipboard_bubble_enabled
          : DEFAULT_USER_SETTINGS.clipboard_bubble_enabled,
      include_prerelease_updates:
        typeof parsed.include_prerelease_updates === 'boolean'
          ? parsed.include_prerelease_updates
          : DEFAULT_USER_SETTINGS.include_prerelease_updates,
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
