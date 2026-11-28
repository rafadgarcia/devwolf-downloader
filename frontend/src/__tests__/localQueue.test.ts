import { beforeEach, describe, expect, it } from 'vitest';
import {
  applyLocalProgressToJob,
  cleanNullableString,
  clearAllLocalJobs,
  createInitialLocalJob,
  extractJobMediaLocations,
  filterLocalHistory,
  findExistingReadyJobByNormalizedUrl,
  getLocalJobs,
  mapLocalFilesToCarouselItems,
  snapshotToJobResponse,
  syncLocalSnapshots,
  upsertLocalJob,
} from '../services/localQueue';

describe('mensagens de erro específicas do nativo', () => {
  const base = {
    jobId: 'story-1',
    url: 'https://www.instagram.com/stories/alice/3002/',
    format: 'auto',
    quality: 'best',
    statusMode: false,
    status: 'failed' as const,
    phaseLabel: 'Falhou',
    engine: 'dnr',
    platform: 'instagram',
    bytesDownloaded: 0,
    totalBytes: null,
    percent: null,
    isIndeterminate: false,
    speedBytesPerSec: null,
    etaSeconds: null,
    errorCode: 'MEDIA_UNAVAILABLE',
    error: 'Este story expirou ou não está mais disponível (stories duram 24 h).',
  };

  it('mostra a mensagem curada quando o nativo marca errorMessageSpecific', () => {
    const job = snapshotToJobResponse({ ...base, errorMessageSpecific: true });
    expect(job.error_message).toBe('Este story expirou ou não está mais disponível (stories duram 24 h).');
  });

  it('mantém a mensagem padrão do código quando não é específica', () => {
    const job = snapshotToJobResponse({ ...base, errorMessageSpecific: false });
    expect(job.error_message).toBe('Publicação removida, indisponível ou link expirado.');
  });
});

describe('upsertLocalJob throttle de progresso', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('não regrava o localStorage a cada tick de progresso com o mesmo status', () => {
    const base = createInitialLocalJob({
      jobId: 'throttle-1',
      url: 'https://www.youtube.com/watch?v=abc',
      normalizedUrl: 'https://www.youtube.com/watch?v=abc',
      platform: 'youtube',
      format: 'auto',
      quality: 'best',
      statusMode: false,
    });
    const downloading = { ...base, status: 'downloading' as const, percent: 10 };
    upsertLocalJob(downloading, { throttleProgress: true, now: 1_000 });
    expect(getLocalJobs()[0].percent).toBe(10);

    upsertLocalJob({ ...downloading, percent: 20 }, { throttleProgress: true, now: 1_200 });
    expect(getLocalJobs()[0].percent).toBe(10);

    upsertLocalJob({ ...downloading, percent: 30 }, { throttleProgress: true, now: 2_100 });
    expect(getLocalJobs()[0].percent).toBe(30);

    upsertLocalJob({ ...downloading, status: 'ready', percent: 100 }, { throttleProgress: true, now: 2_150 });
    expect(getLocalJobs()[0].status).toBe('ready');
  });
});

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
    expect(getLocalJobs()[0].display_engine).toBe('DNR • Instagram');

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
    expect(saved.display_engine).toBe('DNR • Instagram');
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
            thumbnailDataUrl: 'data:image/jpeg;base64,/9j/4AAQSkZJRg==',
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
    expect(merged[0].thumbnail_data_url).toBe('data:image/jpeg;base64,/9j/4AAQSkZJRg==');
    expect(merged[0].items).toHaveLength(2);
    expect(merged[0].items?.[0].mediaUri).toBe('content://media/external/images/media/99');
    expect(merged[0].items?.[0].thumbnailDataUrl).toBe('data:image/jpeg;base64,/9j/4AAQSkZJRg==');
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

    const redditInitial = createInitialLocalJob({
      jobId: 'local-reddit-err',
      url: 'https://www.reddit.com/r/pics/comments/abc123/test',
      normalizedUrl: 'https://www.reddit.com/r/pics/comments/abc123/test',
      platform: 'reddit',
      format: 'auto',
      quality: 'best',
      statusMode: false,
      primaryEngine: 'gallery-dl',
    });
    const redditFailed = applyLocalProgressToJob(redditInitial, {
      jobId: 'local-reddit-err',
      status: 'failed',
      phaseLabel: 'Falhou',
      engine: 'gallery-dl',
      platform: 'reddit',
      bytesDownloaded: 0,
      totalBytes: null,
      percent: null,
      isIndeterminate: true,
      speedBytesPerSec: null,
      etaSeconds: null,
      errorCode: 'AUTH_REQUIRED',
      error: 'HTTP 403',
    });
    expect(redditFailed.error_message).toContain('Reddit');
    expect(redditFailed.error_message?.toLowerCase()).not.toContain('instagram');
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
    expect(all[0].display_engine).toBe('DNR • Instagram');
    expect(all[1].display_engine).toBe('gallery-dl');

    const onlyPin = filterLocalHistory([ig, pin], { platform: 'pinterest' });
    expect(onlyPin).toHaveLength(1);
    expect(onlyPin[0].id).toBe('local-pin');

    const byQuery = filterLocalHistory([ig, pin], { q: 'minimalista' });
    expect(byQuery).toHaveLength(1);
    expect(byQuery[0].id).toBe('local-pin');
  });

  it('sanitiza strings literais "null" vindas do Android JSONObject.optString e garante yt-dlp para YouTube/TikTok', () => {
    expect(cleanNullableString('null')).toBeNull();
    expect(cleanNullableString(' undefined ')).toBeNull();
    expect(cleanNullableString('  ')).toBeNull();
    expect(cleanNullableString('Vídeo Real')).toBe('Vídeo Real');

    const ytJob = createInitialLocalJob({
      jobId: 'local-yt-1',
      url: 'https://youtu.be/dQw4w9WgXcQ',
      normalizedUrl: 'https://youtu.be/dQw4w9WgXcQ',
      platform: 'youtube',
      format: 'auto',
      quality: 'best',
      statusMode: false,
    });
    expect(ytJob.current_engine).toBe('yt-dlp');
    expect(ytJob.display_engine).toBe('yt-dlp');

    const synced = snapshotToJobResponse({
      jobId: 'local-ig-null',
      url: 'https://www.instagram.com/reel/Df3B/',
      format: 'auto',
      quality: 'best',
      statusMode: false,
      status: 'failed',
      phaseLabel: 'Falhou',
      engine: 'null',
      engineVersion: 'null',
      strategyUsed: 'null',
      bytesDownloaded: 0,
      totalBytes: null,
      percent: null,
      isIndeterminate: true,
      speedBytesPerSec: null,
      etaSeconds: null,
      title: 'null',
      platform: 'instagram',
      error: 'null',
      errorCode: 'EXTRACTION_FAILED',
    });

    expect(synced.title).toBeNull();
    expect(synced.engine_version).toBeNull();
    expect(synced.strategy_used).toBeNull();
    expect(synced.selected_engine).toBe('dnr');
    expect(synced.error_message).toBe('Não foi possível extrair a mídia desta página.');

    const ytFromSnap = snapshotToJobResponse({
      jobId: 'local-yt-snap',
      url: 'https://youtu.be/abc123',
      format: 'auto',
      quality: 'best',
      statusMode: false,
      status: 'resolving',
      phaseLabel: 'Resolvendo publicação…',
      engine: 'dnr',
      bytesDownloaded: 0,
      totalBytes: null,
      percent: null,
      isIndeterminate: true,
      speedBytesPerSec: null,
      etaSeconds: null,
      title: 'null',
      platform: 'youtube',
    });
    expect(ytFromSnap.selected_engine).toBe('yt-dlp');
    expect(ytFromSnap.display_engine).toBe('yt-dlp');
    expect(ytFromSnap.title).toBeNull();
  });

  it('encontra job pronto existente pela URL normalizada apenas quando há mediaUri/filePath e limpa todo o histórico local', () => {
    const url = 'https://www.instagram.com/reel/READY1';
    const failedJob = createInitialLocalJob({
      jobId: 'local-failed',
      url,
      normalizedUrl: url,
      platform: 'instagram',
      format: 'auto',
      quality: 'best',
      statusMode: false,
    });
    failedJob.status = 'failed';
    upsertLocalJob(failedJob);

    expect(findExistingReadyJobByNormalizedUrl(getLocalJobs(), url)).toBeNull();

    const readyNoMedia = createInitialLocalJob({
      jobId: 'local-ready-empty',
      url,
      normalizedUrl: url,
      platform: 'instagram',
      format: 'auto',
      quality: 'best',
      statusMode: false,
    });
    readyNoMedia.status = 'ready';
    upsertLocalJob(readyNoMedia);

    expect(findExistingReadyJobByNormalizedUrl(getLocalJobs(), url)).toBeNull();

    const readyWithMedia = applyLocalProgressToJob(
      createInitialLocalJob({
        jobId: 'local-ready-media',
        url,
        normalizedUrl: url,
        platform: 'instagram',
        format: 'auto',
        quality: 'best',
        statusMode: false,
      }),
      {
        jobId: 'local-ready-media',
        status: 'ready',
        phaseLabel: 'Pronto',
        engine: 'dnr',
        bytesDownloaded: 1024,
        totalBytes: 1024,
        percent: 100,
        isIndeterminate: false,
        speedBytesPerSec: null,
        etaSeconds: null,
        items: [
          {
            id: 'item-1',
            index: 1,
            type: 'video',
            fileName: 'reel.mp4',
            mimeType: 'video/mp4',
            sizeBytes: 1024,
            mediaUri: 'content://media/external/video/media/42',
            filePath: '/storage/emulated/0/Movies/Devwolf Downloader/reel.mp4',
            displayPath: 'Movies/Devwolf Downloader/reel.mp4',
            status: 'ready',
          },
        ],
      }
    );
    upsertLocalJob(readyWithMedia);

    const found = findExistingReadyJobByNormalizedUrl(getLocalJobs(), url);
    expect(found?.id).toBe('local-ready-media');
    expect(extractJobMediaLocations(found!)).toEqual({
      uris: ['content://media/external/video/media/42'],
      filePaths: ['/storage/emulated/0/Movies/Devwolf Downloader/reel.mp4'],
    });

    clearAllLocalJobs();
    expect(getLocalJobs()).toHaveLength(0);
  });
});
