import { beforeEach, describe, expect, it } from 'vitest';
import {
  applyLocalProgressToJob,
  createInitialLocalJob,
  getLocalJobs,
  mergeUnifiedHistory,
  syncLocalSnapshots,
  upsertLocalJob,
} from '../services/localQueue';
import type { HistoryItem } from '../types/api';

describe('localQueue & unified history', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('persiste e atualiza progresso real de job local com velocidade e arquivos', () => {
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
    });

    upsertLocalJob(initial);
    expect(getLocalJobs()).toHaveLength(1);

    const updated = applyLocalProgressToJob(initial, {
      jobId: 'local-1',
      status: 'downloading',
      phaseLabel: 'Baixando',
      engine: 'local-ytdlp',
      bytesDownloaded: 1_048_576,
      totalBytes: 2_097_152,
      percent: 50,
      isIndeterminate: false,
      speedBytesPerSec: 524_288,
      etaSeconds: 2,
      title: 'Meu Reel',
    });

    upsertLocalJob(updated);
    const saved = getLocalJobs()[0];
    expect(saved.percent).toBe(50);
    expect(saved.speed_bytes_per_sec).toBe(524_288);
    expect(saved.display_engine).toBe('Local • yt-dlp Android');
    expect(saved.status_mode).toBe(true);
  });

  it('recupera fila interrompida ao sincronizar snapshots nativos', () => {
    const merged = syncLocalSnapshots([
      {
        jobId: 'local-recovered',
        url: 'https://youtu.be/abc',
        format: 'video',
        quality: '720p',
        statusMode: false,
        status: 'ready',
        phaseLabel: 'Pronto para baixar',
        bytesDownloaded: 1000,
        totalBytes: 1000,
        percent: 100,
        isIndeterminate: false,
        speedBytesPerSec: null,
        etaSeconds: null,
        title: 'Vídeo recuperado',
        platform: 'youtube',
        files: [
          {
            id: 'f1',
            fileName: 'video.mp4',
            filePath: '/storage/emulated/0/Movies/Devwolf Downloader/video.mp4',
            mediaUri: 'content://media/external/video/media/99',
            displayPath: 'Movies/Devwolf Downloader/video.mp4',
            mimeType: 'video/mp4',
            sizeBytes: 1000,
            durationSeconds: 15,
            isSegment: false,
            segmentIndex: null,
            isOriginal: false,
          },
        ],
      },
    ]);

    expect(merged).toHaveLength(1);
    expect(merged[0].files[0].media_uri).toBe('content://media/external/video/media/99');
  });

  it('mescla histórico local e VPS com deduplicação e filtros', () => {
    const local = createInitialLocalJob({
      jobId: 'local-9',
      url: 'https://www.instagram.com/reel/LOCAL9',
      normalizedUrl: 'https://www.instagram.com/reel/LOCAL9',
      platform: 'instagram',
      format: 'auto',
      quality: 'best',
      statusMode: false,
    });
    local.title = 'Reel Local';
    local.created_at = '2026-11-17T12:00:00Z';

    const vpsItems: HistoryItem[] = [
      {
        id: 'vps-1',
        original_url: 'https://vm.tiktok.com/ZM123',
        normalized_url: 'https://vm.tiktok.com/ZM123',
        platform: 'tiktok',
        title: 'TikTok Remoto',
        format: 'auto',
        quality: 'best',
        status: 'ready',
        phase_label: 'Pronto para baixar',
        selected_engine: 'cobalt',
        created_at: '2026-11-17T11:00:00Z',
        has_active_file: true,
        file_expired: false,
      },
    ];

    const all = mergeUnifiedHistory([local], vpsItems);
    expect(all).toHaveLength(2);
    expect(all[0].display_engine).toBe('Local • yt-dlp Android');
    expect(all[1].display_engine).toBe('VPS • Cobalt');

    const onlyTiktok = mergeUnifiedHistory([local], vpsItems, { platform: 'tiktok' });
    expect(onlyTiktok).toHaveLength(1);
    expect(onlyTiktok[0].id).toBe('vps-1');

    const byQuery = mergeUnifiedHistory([local], vpsItems, { q: 'local' });
    expect(byQuery).toHaveLength(1);
    expect(byQuery[0].id).toBe('local-9');
  });
});
