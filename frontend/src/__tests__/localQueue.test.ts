import { beforeEach, describe, expect, it } from 'vitest';
import {
  applyLocalProgressToJob,
  createInitialLocalJob,
  filterLocalHistory,
  getLocalJobs,
  mapLocalFilesToCarouselItems,
  syncLocalSnapshots,
  upsertLocalJob,
} from '../services/localQueue';

describe('localQueue, individual carousel items & local history', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('persiste e atualiza progresso real de job local com velocidade, métricas e motor DNR', () => {
    const initial = createInitialLocalJob({
      jobId: 'local-1',
      url: 'https://www.instagram.com/reel/XYZ',
      normalizedUrl: 'https://www.instagram.com/reel/XYZ',
      platform: 'instagram',
      format: 'video',
      quality: 'best',
      statusMode: true,
      segmentDuration: 30,
      keepOriginal: true,
      primaryEngine: 'dnr',
    });

    upsertLocalJob(initial);
    expect(getLocalJobs()).toHaveLength(1);
    expect(getLocalJobs()[0].display_engine).toBe('Local • DNR (Instagram)');

    const updated = applyLocalProgressToJob(initial, {
      jobId: 'local-1',
      status: 'downloading',
      phaseLabel: 'Baixando',
      engine: 'dnr',
      engineVersion: '3.0.0',
      strategyUsed: 'EmbedCaptioned',
      bytesDownloaded: 1_048_576,
      totalBytes: 2_097_152,
      percent: 50,
      isIndeterminate: false,
      speedBytesPerSec: 524_288,
      etaSeconds: 2,
      title: 'Meu Reel',
      metrics: {
        resolutionMs: 320,
        ttfbMs: 95,
        httpRequestCount: 1,
      },
    });

    upsertLocalJob(updated);
    const saved = getLocalJobs()[0];
    expect(saved.percent).toBe(50);
    expect(saved.speed_bytes_per_sec).toBe(524_288);
    expect(saved.display_engine).toBe('Local • DNR (Instagram)');
    expect(saved.strategy_used).toBe('EmbedCaptioned');
    expect(saved.metrics?.resolutionMs).toBe(320);
    expect(saved.status_mode).toBe(true);
  });

  it('representa e ordena cada item de carrossel misto individualmente (sem ZIP)', () => {
    const mapped = mapLocalFilesToCarouselItems(
      [
        {
          id: 'item-3',
          index: 3,
          type: 'image',
          fileName: 'post_03.jpg',
          mimeType: 'image/jpeg',
          width: 1080,
          height: 1080,
          sizeBytes: 300_000,
          bytesDownloaded: 300_000,
          percent: 100,
          status: 'ready',
          mediaUri: 'content://media/external/images/media/3',
          displayPath: 'Pictures/Devwolf Downloader/post_03.jpg',
        },
        {
          id: 'item-1',
          index: 1,
          type: 'image',
          fileName: 'post_01.jpg',
          mimeType: 'image/jpeg',
          width: 1080,
          height: 1350,
          sizeBytes: 420_000,
          bytesDownloaded: 420_000,
          percent: 100,
          status: 'ready',
          mediaUri: 'content://media/external/images/media/1',
          displayPath: 'Pictures/Devwolf Downloader/post_01.jpg',
        },
        {
          id: 'item-2',
          index: 2,
          type: 'video',
          fileName: 'post_02.mp4',
          mimeType: 'video/mp4',
          width: 1080,
          height: 1920,
          sizeBytes: 2_500_000,
          bytesDownloaded: 1_250_000,
          percent: 50,
          status: 'downloading',
          mediaUri: null,
          displayPath: null,
        },
      ],
      'downloading'
    );

    expect(mapped.map((m) => m.index)).toEqual([1, 2, 3]);
    expect(mapped[0]).toMatchObject({
      index: 1,
      type: 'image',
      width: 1080,
      height: 1350,
      status: 'ready',
    });
    expect(mapped[1]).toMatchObject({
      index: 2,
      type: 'video',
      width: 1080,
      height: 1920,
      percent: 50,
      status: 'downloading',
    });
    expect(mapped[2]).toMatchObject({
      index: 3,
      type: 'image',
      status: 'ready',
    });
  });

  it('recupera fila interrompida e carrosséis ao sincronizar snapshots nativos', () => {
    const merged = syncLocalSnapshots([
      {
        jobId: 'local-recovered',
        url: 'https://www.instagram.com/p/CAR123',
        format: 'auto',
        quality: 'best',
        statusMode: false,
        status: 'ready',
        phaseLabel: 'Pronto para baixar',
        engine: 'dnr',
        bytesDownloaded: 1500,
        totalBytes: 1500,
        percent: 100,
        isIndeterminate: false,
        speedBytesPerSec: null,
        etaSeconds: null,
        title: 'Carrossel recuperado',
        platform: 'instagram',
        items: [
          {
            id: 'f1',
            index: 1,
            type: 'image',
            fileName: 'foto1.jpg',
            filePath: '/storage/emulated/0/Pictures/Devwolf Downloader/foto1.jpg',
            mediaUri: 'content://media/external/images/media/99',
            displayPath: 'Pictures/Devwolf Downloader/foto1.jpg',
            mimeType: 'image/jpeg',
            width: 1080,
            height: 1350,
            sizeBytes: 500,
            status: 'ready',
          },
          {
            id: 'f2',
            index: 2,
            type: 'video',
            fileName: 'video2.mp4',
            filePath: '/storage/emulated/0/Movies/Devwolf Downloader/video2.mp4',
            mediaUri: 'content://media/external/video/media/100',
            displayPath: 'Movies/Devwolf Downloader/video2.mp4',
            mimeType: 'video/mp4',
            width: 1080,
            height: 1920,
            sizeBytes: 1000,
            status: 'ready',
          },
        ],
      },
    ]);

    expect(merged).toHaveLength(1);
    expect(merged[0].items).toHaveLength(2);
    expect(merged[0].items?.[0].mediaUri).toBe('content://media/external/images/media/99');
    expect(merged[0].items?.[1].type).toBe('video');
  });

  it('aplica código de erro estruturado com mensagem pt-BR amigável', () => {
    const initial = createInitialLocalJob({
      jobId: 'local-err',
      url: 'https://www.instagram.com/p/PRIV123',
      normalizedUrl: 'https://www.instagram.com/p/PRIV123',
      platform: 'instagram',
      format: 'auto',
      quality: 'best',
      statusMode: false,
    });

    const failed = applyLocalProgressToJob(initial, {
      jobId: 'local-err',
      status: 'failed',
      phaseLabel: 'Falhou',
      engine: 'dnr',
      bytesDownloaded: 0,
      totalBytes: null,
      percent: null,
      isIndeterminate: true,
      speedBytesPerSec: null,
      etaSeconds: null,
      errorCode: 'AUTH_REQUIRED',
      error: 'Login required raw stacktrace',
    });

    expect(failed.error_code).toBe('AUTH_REQUIRED');
    expect(failed.error_message).toBe(
      'Esta publicação exige login no Instagram. Você pode conectar uma sessão opcional nos Ajustes.'
    );
  });

  it('filtra histórico 100% local por plataforma e termo de busca', () => {
    const ig = createInitialLocalJob({
      jobId: 'local-ig',
      url: 'https://www.instagram.com/reel/LOCAL9',
      normalizedUrl: 'https://www.instagram.com/reel/LOCAL9',
      platform: 'instagram',
      format: 'auto',
      quality: 'best',
      statusMode: false,
      primaryEngine: 'dnr',
    });
    ig.title = 'Reel Local';
    ig.created_at = '2026-11-17T12:00:00Z';

    const pin = createInitialLocalJob({
      jobId: 'local-pin',
      url: 'https://pin.it/abc123',
      normalizedUrl: 'https://pin.it/abc123',
      platform: 'pinterest',
      format: 'image',
      quality: 'best',
      statusMode: false,
      primaryEngine: 'gallery-dl',
    });
    pin.title = 'Poster Minimalista';
    pin.created_at = '2026-11-17T11:00:00Z';

    const all = filterLocalHistory([ig, pin]);
    expect(all).toHaveLength(2);
    expect(all[0].display_engine).toBe('Local • DNR (Instagram)');
    expect(all[1].display_engine).toBe('Local • gallery-dl');

    const onlyPin = filterLocalHistory([ig, pin], { platform: 'pinterest' });
    expect(onlyPin).toHaveLength(1);
    expect(onlyPin[0].id).toBe('local-pin');

    const byQuery = filterLocalHistory([ig, pin], { q: 'minimalista' });
    expect(byQuery).toHaveLength(1);
    expect(byQuery[0].id).toBe('local-pin');
  });
});
