import { describe, expect, it } from 'vitest';
import {
  getPlatformEngineRoute,
  isTerminalPlatformError,
  PLATFORM_ROUTING_TABLE,
  selectExecutionPlan,
} from '../services/hybridOrchestrator';
import { parseAndValidateUrl } from '../utils/url';

describe('hybridOrchestrator (Exclusively Local Android Routing)', () => {
  it('roteia todas as plataformas localmente no Android sem nenhuma dependência de VPS', () => {
    const cases = [
      { url: 'https://www.instagram.com/reel/ABC123/', expectedPrimary: 'dnr' },
      { url: 'https://www.instagram.com/p/POST123/', expectedPrimary: 'dnr' },
      { url: 'https://www.threads.net/@user/post/TH123/', expectedPrimary: 'dnr' },
      { url: 'https://youtu.be/dQw4w9WgXcQ', expectedPrimary: 'yt-dlp' },
      { url: 'https://vm.tiktok.com/ZM123/', expectedPrimary: 'yt-dlp' },
      { url: 'https://www.facebook.com/share/r/12345/', expectedPrimary: 'yt-dlp' },
      { url: 'https://x.com/user/status/999', expectedPrimary: 'yt-dlp' },
      { url: 'https://pin.it/xyz123', expectedPrimary: 'gallery-dl' },
      { url: 'https://redd.it/abc123', expectedPrimary: 'gallery-dl' },
    ];

    for (const item of cases) {
      const parsed = parseAndValidateUrl(item.url)!;
      expect(parsed).not.toBeNull();
      const plan = selectExecutionPlan({
        isNativeAndroid: true,
        parsed,
        format: 'auto',
      });
      expect(plan.order).toEqual(['local']);
      expect(plan.localSupported).toBe(true);
      expect(plan.primaryEngine).toBe(item.expectedPrimary);
      expect(plan.reason.toLowerCase()).not.toContain('vps');
    }
  });

  it('mantém roteamento local para formato image (fotos/carrosséis/Pinterest/Reddit)', () => {
    const pin = parseAndValidateUrl('https://www.pinterest.com/pin/123456/')!;
    const pinPlan = selectExecutionPlan({
      isNativeAndroid: true,
      parsed: pin,
      format: 'image',
    });
    expect(pinPlan.order).toEqual(['local']);
    expect(pinPlan.localSupported).toBe(true);

    const igPost = parseAndValidateUrl('https://www.instagram.com/p/PHOTO123/')!;
    const igPlan = selectExecutionPlan({
      isNativeAndroid: true,
      parsed: igPost,
      format: 'image',
    });
    expect(igPlan.order).toEqual(['local']);
    expect(igPlan.primaryEngine).toBe('dnr');
    expect(igPlan.fallbackEngines).toEqual(['yt-dlp']);
  });

  it('expõe tabela centralizada de roteamento para todas as 8 plataformas com yt-dlp como motor universal inicial', () => {
    expect(PLATFORM_ROUTING_TABLE).toHaveLength(8);
    expect(PLATFORM_ROUTING_TABLE[0].platform).toBe('youtube');
    expect(PLATFORM_ROUTING_TABLE[0].primaryEngine).toBe('yt-dlp');
    expect(getPlatformEngineRoute('youtube').primaryEngine).toBe('yt-dlp');
    expect(getPlatformEngineRoute('tiktok').primaryEngine).toBe('yt-dlp');
    expect(getPlatformEngineRoute('instagram').routeDescription).toBe('DNR (fallback yt-dlp)');
    const unknown = getPlatformEngineRoute('vimeo');
    expect(unknown.platform).toBe('vimeo');
    expect(unknown.localSupported).toBe(false);
    expect(getPlatformEngineRoute('threads').routeDescription).toBe('DNR (fallback yt-dlp)');
    expect(getPlatformEngineRoute('pinterest').routeDescription).toBe('gallery-dl/nativo');
    expect(getPlatformEngineRoute('reddit').routeDescription).toBe('gallery-dl/yt-dlp');
  });

  it('informa claramente no preview web que o motor local exige o APK Android (sem pedir VPS)', () => {
    const parsed = parseAndValidateUrl('https://www.instagram.com/reel/ABC123/')!;
    const webPlan = selectExecutionPlan({
      isNativeAndroid: false,
      parsed,
      format: 'auto',
    });
    expect(webPlan.order).toEqual([]);
    expect(webPlan.reason.toLowerCase()).not.toContain('vps');
  });

  it('identifica erros terminais de plataforma via contrato estruturado', () => {
    expect(isTerminalPlatformError('PRIVATE_MEDIA', null)).toBe(true);
    expect(isTerminalPlatformError('AUTH_REQUIRED', null)).toBe(true);
    expect(isTerminalPlatformError('TERMINAL_AUTH', null)).toBe(true);
    expect(isTerminalPlatformError('TERMINAL_RATE_LIMIT', null)).toBe(true);
    expect(isTerminalPlatformError('EXTRACTION_FAILED', null)).toBe(false);
    expect(isTerminalPlatformError('EXTRACTOR_OUTDATED', null)).toBe(false);
    expect(isTerminalPlatformError('NETWORK_ERROR', null)).toBe(false);
  });
});
