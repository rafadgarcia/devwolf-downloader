import { describe, expect, it } from 'vitest';
import {
  decorateVpsJob,
  isTerminalPlatformError,
  selectExecutionPlan,
} from '../services/hybridOrchestrator';
import type { JobResponse } from '../types/api';
import { parseAndValidateUrl } from '../utils/url';

describe('hybridOrchestrator', () => {
  it('prioriza motor local no Android em modo Automático com fallback para VPS', () => {
    const parsed = parseAndValidateUrl('https://www.instagram.com/reel/ABC123/')!;
    const plan = selectExecutionPlan({
      preferredMode: 'auto',
      isNativeAndroid: true,
      vpsAuthenticated: true,
      vpsConfigured: true,
      parsed,
      format: 'auto',
    });

    expect(plan.order).toEqual(['local', 'vps']);
  });

  it('funciona 100% offline da VPS no Android quando VPS não está configurada ou autenticada', () => {
    const parsed = parseAndValidateUrl('https://www.facebook.com/share/r/12345/')!;
    const plan = selectExecutionPlan({
      preferredMode: 'auto',
      isNativeAndroid: true,
      vpsAuthenticated: false,
      vpsConfigured: false,
      parsed,
      format: 'video',
    });

    expect(plan.order).toEqual(['local']);
    expect(plan.requiresVpsLogin).toBeUndefined();
  });

  it('prioriza VPS para imagens/Pinterest e retorna para VPS em Web/PWA', () => {
    const pin = parseAndValidateUrl('https://pin.it/xyz123')!;
    const androidPinPlan = selectExecutionPlan({
      preferredMode: 'auto',
      isNativeAndroid: true,
      vpsAuthenticated: true,
      vpsConfigured: true,
      parsed: pin,
      format: 'auto',
    });
    expect(androidPinPlan.order).toEqual(['vps']);

    const reel = parseAndValidateUrl('https://www.instagram.com/reel/ABC123/')!;
    const webPlan = selectExecutionPlan({
      preferredMode: 'auto',
      isNativeAndroid: false,
      vpsAuthenticated: true,
      vpsConfigured: true,
      parsed: reel,
      format: 'auto',
    });
    expect(webPlan.order).toEqual(['vps']);
  });

  it('respeita escolha explícita Local e Remoto (VPS)', () => {
    const parsed = parseAndValidateUrl('https://youtu.be/dQw4w9WgXcQ')!;

    const forcedLocalOnWeb = selectExecutionPlan({
      preferredMode: 'local',
      isNativeAndroid: false,
      vpsAuthenticated: true,
      vpsConfigured: true,
      parsed,
      format: 'auto',
    });
    expect(forcedLocalOnWeb.order).toEqual([]);

    const forcedVpsOnAndroid = selectExecutionPlan({
      preferredMode: 'vps',
      isNativeAndroid: true,
      vpsAuthenticated: true,
      vpsConfigured: true,
      parsed,
      format: 'auto',
    });
    expect(forcedVpsOnAndroid.order).toEqual(['vps']);
  });

  it('identifica erros terminais de plataforma sem loop de fallback', () => {
    expect(isTerminalPlatformError('PRIVATE_MEDIA', null)).toBe(true);
    expect(isTerminalPlatformError('AUTH_REQUIRED', null)).toBe(true);
    expect(isTerminalPlatformError(null, 'Sign in to confirm your age')).toBe(true);
    expect(isTerminalPlatformError(null, 'Mídia privada ou indisponível')).toBe(true);
    expect(isTerminalPlatformError('NETWORK_TIMEOUT', 'Connection timed out')).toBe(false);
  });

  it('decora job da VPS com rótulo claro do motor ativo', () => {
    const baseJob: JobResponse = {
      id: 'job-1',
      original_url: 'https://vm.tiktok.com/ZM123',
      normalized_url: 'https://vm.tiktok.com/ZM123',
      platform: 'tiktok',
      format: 'auto',
      quality: 'best',
      status: 'downloading',
      phase_label: 'Baixando',
      current_engine: 'cobalt',
      bytes_downloaded: 0,
      total_bytes: null,
      percent: null,
      is_indeterminate: true,
      files: [],
      created_at: '2026-11-17T00:00:00Z',
      updated_at: '2026-11-17T00:00:00Z',
    };

    const decorated = decorateVpsJob(baseJob, { statusMode: true, segmentDuration: 30 });
    expect(decorated.execution_origin).toBe('vps');
    expect(decorated.display_engine).toBe('VPS • Cobalt');
    expect(decorated.status_mode).toBe(true);
    expect(decorated.segment_duration).toBe(30);
  });
});
