import type {
  FormatOption,
  HistoryItem,
  JobFile,
  JobResponse,
  JobStatus,
  QualityOption,
} from '../types/api';
import { formatDisplayEngine, getPhaseLabel } from '../utils/format';
import type {
  LocalDownloadProgressEvent,
  LocalDownloadSnapshot,
  LocalOutputFile,
} from './nativeBridge';

const LOCAL_QUEUE_STORAGE_KEY = 'devwolf_local_jobs_v2';
const MAX_LOCAL_HISTORY_ITEMS = 80;

function readStoredJobs(): JobResponse[] {
  try {
    const raw = window.localStorage.getItem(LOCAL_QUEUE_STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? (parsed as JobResponse[]) : [];
  } catch {
    return [];
  }
}

function writeStoredJobs(jobs: JobResponse[]): void {
  try {
    const trimmed = jobs
      .slice()
      .sort((a, b) => new Date(b.created_at).getTime() - new Date(a.created_at).getTime())
      .slice(0, MAX_LOCAL_HISTORY_ITEMS);
    window.localStorage.setItem(LOCAL_QUEUE_STORAGE_KEY, JSON.stringify(trimmed));
  } catch {
    // Ignorar falha de quota no localStorage
  }
}

export function mapLocalFilesToJobFiles(files?: LocalOutputFile[]): JobFile[] {
  if (!Array.isArray(files)) return [];
  return files.map((f) => ({
    id: f.id,
    filename: f.fileName,
    mime_type: f.mimeType || 'video/mp4',
    size_bytes: f.sizeBytes ?? 0,
    duration_seconds: f.durationSeconds ?? null,
    file_path: f.filePath,
    media_uri: f.mediaUri,
    display_path: f.displayPath,
    is_segment: f.isSegment,
    segment_index: f.segmentIndex,
    is_original: f.isOriginal,
  }));
}

export function createInitialLocalJob(params: {
  jobId: string;
  url: string;
  normalizedUrl: string;
  platform: string;
  format: FormatOption;
  quality: QualityOption;
  statusMode: boolean;
  segmentDuration?: number | null;
  keepOriginal?: boolean;
  fromShareIntent?: boolean;
}): JobResponse {
  const now = new Date().toISOString();
  return {
    id: params.jobId,
    original_url: params.url,
    normalized_url: params.normalizedUrl,
    platform: params.platform,
    title: null,
    format: params.format,
    quality: params.quality,
    status: 'queued',
    phase_label: 'Link detectado',
    current_engine: 'local-ytdlp',
    selected_engine: 'local-ytdlp',
    execution_origin: 'local',
    display_engine: 'Local • yt-dlp Android',
    bytes_downloaded: 0,
    total_bytes: null,
    percent: null,
    is_indeterminate: true,
    speed_bytes_per_sec: null,
    eta_seconds: null,
    status_mode: params.statusMode,
    segment_duration: params.segmentDuration ?? null,
    keep_original: params.keepOriginal ?? false,
    from_share_intent: params.fromShareIntent ?? false,
    files: [],
    created_at: now,
    updated_at: now,
  };
}

export function applyLocalProgressToJob(
  existing: JobResponse | null,
  event: LocalDownloadProgressEvent
): JobResponse {
  const now = new Date().toISOString();
  const status: JobStatus = event.status;
  const files = event.files ? mapLocalFilesToJobFiles(event.files) : existing?.files ?? [];

  return {
    id: event.jobId,
    original_url: existing?.original_url ?? '',
    normalized_url: existing?.normalized_url ?? '',
    platform: event.platform ?? existing?.platform ?? 'instagram',
    title: event.title ?? existing?.title ?? null,
    format: existing?.format ?? 'auto',
    quality: existing?.quality ?? 'best',
    status,
    phase_label: event.phaseLabel || getPhaseLabel(status),
    current_engine: 'local-ytdlp',
    selected_engine: 'local-ytdlp',
    execution_origin: 'local',
    display_engine: 'Local • yt-dlp Android',
    bytes_downloaded: event.bytesDownloaded ?? existing?.bytes_downloaded ?? 0,
    total_bytes: event.totalBytes ?? existing?.total_bytes ?? null,
    percent: event.percent ?? existing?.percent ?? null,
    is_indeterminate: event.isIndeterminate,
    speed_bytes_per_sec: event.speedBytesPerSec ?? null,
    eta_seconds: event.etaSeconds ?? null,
    status_mode: existing?.status_mode ?? false,
    segment_duration: existing?.segment_duration ?? null,
    keep_original: existing?.keep_original ?? false,
    from_share_intent: existing?.from_share_intent ?? false,
    error_code: event.errorCode ?? existing?.error_code ?? null,
    error_message: event.error ?? existing?.error_message ?? null,
    files,
    created_at: existing?.created_at ?? now,
    updated_at: now,
    completed_at: status === 'ready' || status === 'failed' || status === 'cancelled' ? now : existing?.completed_at ?? null,
  };
}

export function snapshotToJobResponse(snapshot: LocalDownloadSnapshot): JobResponse {
  const now = new Date().toISOString();
  return {
    id: snapshot.jobId,
    original_url: snapshot.url,
    normalized_url: snapshot.url,
    platform: snapshot.platform ?? 'instagram',
    title: snapshot.title ?? null,
    format: snapshot.format,
    quality: snapshot.quality,
    status: snapshot.status,
    phase_label: snapshot.phaseLabel || getPhaseLabel(snapshot.status),
    current_engine: 'local-ytdlp',
    selected_engine: 'local-ytdlp',
    execution_origin: 'local',
    display_engine: 'Local • yt-dlp Android',
    bytes_downloaded: snapshot.bytesDownloaded ?? 0,
    total_bytes: snapshot.totalBytes ?? null,
    percent: snapshot.percent ?? null,
    is_indeterminate: snapshot.isIndeterminate,
    speed_bytes_per_sec: snapshot.speedBytesPerSec ?? null,
    eta_seconds: snapshot.etaSeconds ?? null,
    status_mode: snapshot.statusMode,
    segment_duration: snapshot.segmentDurationSeconds ?? null,
    keep_original: snapshot.keepOriginal ?? false,
    error_code: snapshot.errorCode ?? null,
    error_message: snapshot.error ?? null,
    files: mapLocalFilesToJobFiles(snapshot.files),
    created_at: snapshot.createdAt ?? now,
    updated_at: snapshot.updatedAt ?? now,
  };
}

export function upsertLocalJob(job: JobResponse): JobResponse[] {
  const current = readStoredJobs();
  const idx = current.findIndex((item) => item.id === job.id);
  if (idx >= 0) {
    current[idx] = { ...current[idx], ...job };
  } else {
    current.unshift(job);
  }
  writeStoredJobs(current);
  return current;
}

export function getLocalJobs(): JobResponse[] {
  return readStoredJobs();
}

export function getLocalJobById(jobId: string): JobResponse | null {
  return readStoredJobs().find((j) => j.id === jobId) ?? null;
}

export function removeLocalJob(jobId: string): void {
  const next = readStoredJobs().filter((item) => item.id !== jobId);
  writeStoredJobs(next);
}

export function syncLocalSnapshots(snapshots: LocalDownloadSnapshot[]): JobResponse[] {
  const stored = readStoredJobs();
  const map = new Map<string, JobResponse>(stored.map((j) => [j.id, j]));
  for (const snap of snapshots) {
    const converted = snapshotToJobResponse(snap);
    const prev = map.get(snap.jobId);
    map.set(snap.jobId, prev ? { ...prev, ...converted } : converted);
  }
  const merged = Array.from(map.values()).sort(
    (a, b) => new Date(b.created_at).getTime() - new Date(a.created_at).getTime()
  );
  writeStoredJobs(merged);
  return merged;
}

export function localJobToHistoryItem(job: JobResponse): HistoryItem {
  const primaryFile = job.files?.[0] ?? null;
  return {
    id: job.id,
    original_url: job.original_url,
    normalized_url: job.normalized_url,
    platform: job.platform,
    title: job.title,
    format: job.format,
    quality: job.quality,
    status: job.status,
    phase_label: job.phase_label || getPhaseLabel(job.status),
    selected_engine: job.selected_engine ?? 'local-ytdlp',
    execution_origin: job.execution_origin ?? 'local',
    display_engine:
      job.display_engine ?? formatDisplayEngine(job.selected_engine, job.execution_origin ?? 'local'),
    status_mode: job.status_mode,
    error_message: job.error_message,
    created_at: job.created_at,
    completed_at: job.completed_at,
    file: primaryFile,
    files: job.files,
    has_active_file: Boolean(primaryFile && job.status === 'ready'),
    file_expired: false,
  };
}

export function mergeUnifiedHistory(
  localJobs: JobResponse[],
  vpsItems: HistoryItem[],
  filter?: { q?: string; platform?: string }
): HistoryItem[] {
  const localItems = localJobs.map(localJobToHistoryItem);
  const byId = new Map<string, HistoryItem>();

  for (const item of vpsItems) {
    byId.set(item.id, {
      ...item,
      execution_origin: item.execution_origin ?? 'vps',
      display_engine:
        item.display_engine ?? formatDisplayEngine(item.selected_engine, item.execution_origin ?? 'vps'),
    });
  }

  for (const local of localItems) {
    byId.set(local.id, local);
  }

  const query = (filter?.q ?? '').trim().toLowerCase();
  const platform = filter?.platform ?? 'all';

  return Array.from(byId.values())
    .filter((item) => {
      if (platform !== 'all' && item.platform !== platform) {
        return false;
      }
      if (query) {
        const hay = `${item.title ?? ''} ${item.normalized_url} ${item.original_url}`.toLowerCase();
        if (!hay.includes(query)) {
          return false;
        }
      }
      return true;
    })
    .sort((a, b) => new Date(b.created_at).getTime() - new Date(a.created_at).getTime());
}
