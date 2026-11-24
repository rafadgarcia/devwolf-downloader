import { beforeEach, describe, expect, it } from 'vitest';
import {
  APP_UPDATE_AUTO_CHECK_INTERVAL_MS,
  shouldAutoCheckAppUpdate,
} from '../components/AppUpdatesSection';
import { DEFAULT_USER_SETTINGS, loadLocalSettings } from '../services/settingsStorage';

describe('Atualizações do app', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('verifica automaticamente quando nunca verificou ou a última consulta passou de 6 h', () => {
    const now = 1_000_000_000;
    expect(shouldAutoCheckAppUpdate(null, now)).toBe(true);
    expect(shouldAutoCheckAppUpdate(now - 60_000, now)).toBe(false);
    expect(shouldAutoCheckAppUpdate(now - APP_UPDATE_AUTO_CHECK_INTERVAL_MS, now)).toBe(true);
  });

  it('inclui versões de teste por padrão e preserva a escolha salva', () => {
    expect(DEFAULT_USER_SETTINGS.include_prerelease_updates).toBe(true);
    expect(loadLocalSettings().include_prerelease_updates).toBe(true);

    window.localStorage.setItem(
      'devwolf_user_settings_v3',
      JSON.stringify({ ...DEFAULT_USER_SETTINGS, include_prerelease_updates: false })
    );
    expect(loadLocalSettings().include_prerelease_updates).toBe(false);
  });
});
