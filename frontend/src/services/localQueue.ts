import type {
  CarouselItemState,
  CarouselItemStatus,
  CarouselMediaType,
  FormatOption,
  HistoryItem,
  JobFile,
  JobResponse,
  JobStatus,
  QualityOption,
} from '../types/api';
import { getUserFacingErrorMessage } from '../utils/errorContract';
import { formatDisplayEngine, getPhaseLabel } from '../utils/format';
import type {
  LocalDownloadProgressEvent,
  LocalDownloadSnapshot,
  LocalOutputFile,
} from './nativeBridge';

const LOCAL_QUEUE_STORAGE_KEY = 'devwolf_local_jobs_v3';
const LEGACY_LOCAL_QUEUE_STORAGE_KEY = 'devwolf_local_jobs_v2';
const MAX_LOCAL_HISTORY_ITEMS = 80;

function readStoredJobs(): JobResponse[] {
  try {
    const raw =
      window.localStorage.getItem(LOCAL_QUEUE_STORAGE_KEY) ??
      window.localStorage.getItem(LEGACY_LOCAL_QUEUE_STORAGE_KEY);
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

function inferMediaType(
  explicitType?: CarouselMediaType,
  mimeType?: string | null,
  fileName?: string | null
): CarouselMediaType {
  if (explicitType === 'image' || explicitType === 'video' || explicitType === 'audio') {
    return explicitType;
  }
  const mime = (mimeType ?? '').toLowerCase();
  if (mime.startsWith('image/')) return 'image';
  if (mime.startsWith('audio/')) return 'audio';
  if (mime.startsWith('video/')) return 'video';
  const name = (fileName ?? '').toLowerCase();
  if (/\.(jpe?g|png|webp|gif|heic|heif|avif)$/.test(name)) return 'image';
  if (/\.(mp3|m4a|aac|opus|ogg|wav|flac)$/.test(name)) return 'audio';
  return 'video';
}

function normalizeItemStatus(
  rawStatus?: CarouselItemStatus,
  hasMedia?: boolean,
  jobStatus?: JobStatus
): CarouselItemStatus {
  if (
    rawStatus === 'queued' ||
    rawStatus === 'downloading' ||
    rawStatus === 'ready' ||
    rawStatus === 'failed'
  ) {
    return rawStatus;
  }
  if (hasMedia || jobStatus === 'ready') return 'ready';
  if (jobStatus === 'failed' || jobStatus === 'cancelled') return 'failed';
  if (jobStatus === 'downloading' || jobStatus === 'processing') return 'downloading';
  return 'queued';
}

export function mapLocalFilesToCarouselItems(
  files?: LocalOutputFile[],
  jobStatus?: JobStatus
): CarouselItemState[] {
  if (!Array.isArray(files)) return [];

  return files
    .map((f, idx) => {
      const index = typeof f.index === 'number' && f.index > 0 ? f.index : idx + 1;
      const mimeType = f.mimeType || 'application/octet-stream';
      const type = inferMediaType(f.type, mimeType, f.fileName);
      const hasMedia = Boolean(f.mediaUri || f.filePath);
      const status = normalizeItemStatus(f.status, hasMedia, jobStatus);
      const sizeBytes = f.sizeBytes ?? 0;
      const bytesDownloaded = f.bytesDownloaded ?? (status === 'ready' ? sizeBytes : 0);
      const percent =
        f.percent !== undefined && f.percent !== null
          ? f.percent
          : status === 'ready'
          ? 100
          : sizeBytes > 0 && bytesDownloaded > 0
          ? Math.min(100, Math.round((bytesDownloaded / sizeBytes) * 100))
          : null;

      return {
        id: f.id || `item-${index}`,
        index,
        type,
        fileName: f.fileName,
        mimeType,
        width: f.width ?? null,
        height: f.height ?? null,
        sizeBytes,
        bytesDownloaded,
        percent,
        status,
        mediaUri: f.mediaUri ?? null,
        displayPath: f.displayPath ?? null,
        filePath: f.filePath ?? null,
        durationSeconds: f.durationSeconds ?? null,
        isSegment: Boolean(f.isSegment),
        segmentIndex: f.segmentIndex ?? null,
        isOriginal: Boolean(f.isOriginal),
      };
    })
    .sort((a, b) => a.index - b.index);
}

export function mapLocalFilesToJobFiles(
  files?: LocalOutputFile[],
  jobStatus?: JobStatus
): JobFile[] {
  return mapLocalFilesToCarouselItems(files, jobStatus).map((item) => ({
    id: item.id,
    index: item.index,
    type: item.type,
    filename: item.fileName,
    mime_type: item.mimeType,
    width: item.width,
    height: item.height,
    size_bytes: item.sizeBytes,
    bytes_downloaded: item.bytesDownloaded,
    percent: item.percent,
    status: item.status,
    duration_seconds: item.durationSeconds ?? null,
    file_path: item.filePath ?? null,
    media_uri: item.mediaUri,
    display_path: item.displayPath,
    is_segment: item.isSegment,
    segment_index: item.segmentIndex ?? null,
    is_original: item.isOriginal,
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
  primaryEngine?: string;
}): JobResponse {
  const now = new Date().toISOString();
  const initialEngine = params.primaryEngine ?? (params.platform === 'instagram' ? 'dnr' : 'yt-dlp');
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
    current_engine: initialEngine,
    selected_engine: initialEngine,
    execution_origin: 'local',
    display_engine: formatDisplayEngine(initialEngine, 'local', params.platform),
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
    items: [],
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
  const rawList = event.items ?? event.files;
  const items = rawList ? mapLocalFilesToCarouselItems(rawList, status) : existing?.items ?? [];
  const files = rawList ? mapLocalFilesToJobFiles(rawList, status) : existing?.files ?? [];
  const engine = event.engine || existing?.current_engine || existing?.selected_engine || 'yt-dlp';
  const platform = event.platform ?? existing?.platform ?? 'instagram';
  const errorCode = event.errorCode ?? existing?.error_code ?? null;
  const errorMessage =
    status === 'failed' || errorCode || event.error
      ? getUserFacingErrorMessage(errorCode, event.error ?? existing?.error_message, platform)
      : null;

  return {
    id: event.jobId,
    original_url: existing?.original_url ?? '',
    normalized_url: existing?.normalized_url ?? '',
    platform,
    title: event.title ?? existing?.title ?? null,
    format: existing?.format ?? 'auto',
    quality: existing?.quality ?? 'best',
    status,
    phase_label: event.phaseLabel || getPhaseLabel(status),
    current_engine: engine,
    selected_engine: engine,
    execution_origin: 'local',
    display_engine: formatDisplayEngine(engine, 'local', platform),
    engine_version: event.engineVersion ?? existing?.engine_version ?? null,
    strategy_used: event.strategyUsed ?? existing?.strategy_used ?? null,
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
    error_code: errorCode,
    error_message: errorMessage,
    technical_reason: event.technicalReason ?? existing?.technical_reason ?? null,
    metrics: event.metrics ?? existing?.metrics ?? null,
    files,
    items,
    created_at: existing?.created_at ?? now,
    updated_at: now,
    completed_at:
      status === 'ready' || status === 'failed' || status === 'cancelled'
        ? now
        : existing?.completed_at ?? null,
  };
}

export function snapshotToJobResponse(snapshot: LocalDownloadSnapshot): JobResponse {
  const now = new Date().toISOString();
  const rawList = snapshot.items ?? snapshot.files;
  const items = mapLocalFilesToCarouselItems(rawList, snapshot.status);
  const files = mapLocalFilesToJobFiles(rawList, snapshot.status);
  const platform = snapshot.platform ?? 'instagram';
  const engine = snapshot.engine || (platform === 'instagram' || platform === 'threads' ? 'dnr' : 'yt-dlp');
  const errorCode = snapshot.errorCode ?? null;
  const errorMessage =
    snapshot.status === 'failed' || errorCode || snapshot.error
      ? getUserFacingErrorMessage(errorCode, snapshot.error, platform)
      : null;

  return {
    id: snapshot.jobId,
    original_url: snapshot.url,
    normalized_url: snapshot.url,
    platform,
    title: snapshot.title ?? null,
    format: snapshot.format,
    quality: snapshot.quality,
    status: snapshot.status,
    phase_label: snapshot.phaseLabel || getPhaseLabel(snapshot.status),
    current_engine: engine,
    selected_engine: engine,
    execution_origin: 'local',
    display_engine: formatDisplayEngine(engine, 'local', platform),
    engine_version: snapshot.engineVersion ?? null,
    strategy_used: snapshot.strategyUsed ?? null,
    bytes_downloaded: snapshot.bytesDownloaded ?? 0,
    total_bytes: snapshot.totalBytes ?? null,
    percent: snapshot.percent ?? null,
    is_indeterminate: snapshot.isIndeterminate,
    speed_bytes_per_sec: snapshot.speedBytesPerSec ?? null,
    eta_seconds: snapshot.etaSeconds ?? null,
    status_mode: snapshot.statusMode,
    segment_duration: snapshot.segmentDurationSeconds ?? null,
    keep_original: snapshot.keepOriginal ?? false,
    error_code: errorCode,
    error_message: errorMessage,
    technical_reason: snapshot.technicalReason ?? null,
    metrics: snapshot.metrics ?? null,
    files,
    items,
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
    selected_engine: job.selected_engine ?? 'yt-dlp',
    execution_origin: 'local',
    display_engine: job.display_engine ?? formatDisplayEngine(job.selected_engine, 'local', job.platform),
    status_mode: job.status_mode,
    error_code: job.error_code ?? null,
    error_message: job.error_message,
    created_at: job.created_at,
    completed_at: job.completed_at,
    file: primaryFile,
    files: job.files,
    items: job.items,
    has_active_file: Boolean(primaryFile && job.status === 'ready'),
  };
}

export function filterLocalHistory(
  localJobs: JobResponse[],
  filter?: { q?: string; platform?: string }
): HistoryItem[] {
  const query = (filter?.q ?? '').trim().toLowerCase();
  const platform = filter?.platform ?? 'all';

  return localJobs
    .map(localJobToHistoryItem)
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
