import { beforeEach, describe, expect, it } from 'vitest';
import {
  buildFingerprint,
  clearActiveJobById,
  clearActiveJobFingerprint,
  clearAllDedupeState,
  DEDUPE_WINDOW_MS,
  isDuplicateAutoDownload,
  markActiveJobFingerprint,
  markRecentAutoDownload,
} from '../utils/dedupe';

describe('deduplicação de 10 minutos e jobs ativos', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('gera fingerprint distinto para opções diferentes e Modo Status da mesma URL', () => {
    const fpBest = buildFingerprint('https://www.instagram.com/reel/ABC', 'auto', 'best');
    const fpAudio = buildFingerprint('https://www.instagram.com/reel/ABC', 'audio', 'audio');
    const fpStatus30 = buildFingerprint('https://www.instagram.com/reel/ABC', 'auto', 'best', true, 30);
    const fpStatus60 = buildFingerprint('https://www.instagram.com/reel/ABC', 'auto', 'best', true, 60);
    expect(fpBest).not.toBe(fpAudio);
    expect(fpBest).not.toBe(fpStatus30);
    expect(fpStatus30).not.toBe(fpStatus60);
  });

  it('bloqueia auto-download duplicado dentro da janela de 10 minutos e libera após expirar', () => {
    const fp = buildFingerprint('https://www.instagram.com/reel/ABC', 'auto', 'best');
    const t0 = 1_700_000_000_000;

    expect(isDuplicateAutoDownload(fp, t0)).toBeNull();
    markRecentAutoDownload(fp, 'job-1', t0);

    // 5 minutos depois ainda é duplicado (sobrevive a refresh via localStorage)
    expect(isDuplicateAutoDownload(fp, t0 + 5 * 60 * 1000)?.jobId).toBe('job-1');

    // Após 10 minutos expira automaticamente
    expect(isDuplicateAutoDownload(fp, t0 + DEDUPE_WINDOW_MS + 1)).toBeNull();
  });

  it('bloqueia enquanto job estiver ativo e libera quando finalizado/limpo', () => {
    const fp = buildFingerprint('https://youtu.be/XYZ', 'video', '1080p');
    const t0 = 1_700_000_000_000;

    markActiveJobFingerprint(fp, 'job-active', t0);
    expect(isDuplicateAutoDownload(fp, t0 + 1000)?.jobId).toBe('job-active');

    clearActiveJobById('job-active');
    expect(isDuplicateAutoDownload(fp, t0 + 1000)).toBeNull();

    markActiveJobFingerprint(fp, 'job-active-2', t0);
    clearActiveJobFingerprint(fp);
    expect(isDuplicateAutoDownload(fp, t0 + 1000)).toBeNull();
  });

  it('limpa todo o estado de deduplicação ao limpar histórico', () => {
    const fp = buildFingerprint('https://www.instagram.com/reel/CLEAR1', 'auto', 'best');
    const t0 = 1_700_000_000_000;
    markActiveJobFingerprint(fp, 'job-clear-active', t0);
    markRecentAutoDownload(fp, 'job-clear-recent', t0);
    expect(isDuplicateAutoDownload(fp, t0 + 1000)).not.toBeNull();

    clearAllDedupeState();
    expect(isDuplicateAutoDownload(fp, t0 + 1000)).toBeNull();
  });
});
