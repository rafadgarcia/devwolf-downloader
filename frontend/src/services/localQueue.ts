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

export function cleanNullableString(value?: string | null): string | null {
  if (value === undefined || value === null) return null;
  const trimmed = value.trim();
  if (!trimmed || trimmed.toLowerCase() === 'null' || trimmed.toLowerCase() === 'undefined') {
    return null;
  }
  return value;
}

export function getDefaultEngineForPlatform(platform?: string | null): string {
  const normalized = (platform ?? '').trim().toLowerCase();
  if (normalized === 'instagram' || normalized === 'threads') return 'dnr';
  if (normalized === 'pinterest' || normalized === 'reddit') return 'gallery-dl';
  return 'yt-dlp';
}

function normalizeEngineForPlatform(engine?: string | null, platform?: string | null): string {
  const cleaned = cleanNullableString(engine);
  const normalizedPlatform = (platform ?? '').trim().toLowerCase();
  if (!cleaned) {
    return getDefaultEngineForPlatform(normalizedPlatform);
  }
  if (
    cleaned.toLowerCase() === 'dnr' &&
    normalizedPlatform &&
    normalizedPlatform !== 'instagram' &&
    normalizedPlatform !== 'threads'
  ) {
    return getDefaultEngineForPlatform(normalizedPlatform);
  }
  return cleaned;
}

function sanitizeStoredJob(job: JobResponse): JobResponse {
  const platform = cleanNullableString(job.platform) ?? 'instagram';
  const engine = normalizeEngineForPlatform(
    job.current_engine ?? job.selected_engine,
    platform
  );
  return {
    ...job,
    platform,
    title: cleanNullableString(job.title),
    phase_label: cleanNullableString(job.phase_label) ?? getPhaseLabel(job.status),
    current_engine: engine,
    selected_engine: normalizeEngineForPlatform(job.selected_engine ?? engine, platform),
    display_engine: formatDisplayEngine(engine, 'local', platform),
    engine_version: cleanNullableString(job.engine_version),
    strategy_used: cleanNullableString(job.strategy_used),
    error_code: cleanNullableString(job.error_code),
    error_message: cleanNullableString(job.error_message),
    technical_reason: cleanNullableString(job.technical_reason),
    thumbnail_data_url: cleanNullableString(job.thumbnail_data_url),
  };
}

function readStoredJobs(): JobResponse[] {
  try {
    const raw =
      window.localStorage.getItem(LOCAL_QUEUE_STORAGE_KEY) ??
      window.localStorage.getItem(LEGACY_LOCAL_QUEUE_STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? (parsed as JobResponse[]).map(sanitizeStoredJob) : [];
  } catch {
    return [];
  }
}

function writeStoredJobs(jobs: JobResponse[]): void {
  const trimmed = jobs
    .slice()
    .sort((a, b) => new Date(b.created_at).getTime() - new Date(a.created_at).getTime())
    .slice(0, MAX_LOCAL_HISTORY_ITEMS);
  try {
    window.localStorage.setItem(LOCAL_QUEUE_STORAGE_KEY, JSON.stringify(trimmed));
  } catch {
    // Se exceder quota por conta de thumbnails em base64, preservar thumbnails apenas nos 12 itens mais recentes
    try {
      const compacted = trimmed.map((job, idx) => {
        if (idx < 12) return job;
        return {
          ...job,
          thumbnail_data_url: null,
          files: (job.files ?? []).map((f) => ({ ...f, thumbnail_data_url: null })),
          items: (job.items ?? []).map((it) => ({ ...it, thumbnailDataUrl: null })),
        };
      });
      window.localStorage.setItem(LOCAL_QUEUE_STORAGE_KEY, JSON.stringify(compacted));
    } catch {
      // Ignorar falha de quota no localStorage
    }
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
        fileName: cleanNullableString(f.fileName) ?? `media_${index}`,
        mimeType,
        width: f.width ?? null,
        height: f.height ?? null,
        sizeBytes,
        bytesDownloaded,
        percent,
        status,
        mediaUri: cleanNullableString(f.mediaUri),
        displayPath: cleanNullableString(f.displayPath),
        filePath: cleanNullableString(f.filePath),
        thumbnailDataUrl: cleanNullableString(f.thumbnailDataUrl),
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
    thumbnail_data_url: item.thumbnailDataUrl ?? null,
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
  const initialEngine = normalizeEngineForPlatform(
    params.primaryEngine ?? getDefaultEngineForPlatform(params.platform),
    params.platform
  );
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
  const platform = cleanNullableString(event.platform) ?? existing?.platform ?? 'instagram';
  const engine = normalizeEngineForPlatform(
    event.engine ?? existing?.current_engine ?? existing?.selected_engine,
    platform
  );
  const errorCode = cleanNullableString(event.errorCode) ?? cleanNullableString(existing?.error_code);
  const rawEventError = cleanNullableString(event.error) ?? cleanNullableString(existing?.error_message);
  const errorMessage =
    status === 'failed' || errorCode || rawEventError
      ? getUserFacingErrorMessage(errorCode, rawEventError, platform)
      : null;

  return {
    id: event.jobId,
    original_url: existing?.original_url ?? '',
    normalized_url: existing?.normalized_url ?? '',
    platform,
    title: cleanNullableString(event.title) ?? cleanNullableString(existing?.title),
    format: existing?.format ?? 'auto',
    quality: existing?.quality ?? 'best',
    status,
    phase_label: cleanNullableString(event.phaseLabel) || getPhaseLabel(status),
    current_engine: engine,
    selected_engine: engine,
    execution_origin: 'local',
    display_engine: formatDisplayEngine(engine, 'local', platform),
    engine_version:
      cleanNullableString(event.engineVersion) ?? cleanNullableString(existing?.engine_version),
    strategy_used:
      cleanNullableString(event.strategyUsed) ?? cleanNullableString(existing?.strategy_used),
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
    error_message: cleanNullableString(errorMessage),
    technical_reason:
      cleanNullableString(event.technicalReason) ??
      cleanNullableString(existing?.technical_reason),
    metrics: event.metrics ?? existing?.metrics ?? null,
    thumbnail_data_url:
      cleanNullableString(event.thumbnailDataUrl) ??
      items.find((i) => Boolean(i.thumbnailDataUrl))?.thumbnailDataUrl ??
      cleanNullableString(existing?.thumbnail_data_url),
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
  const platform = cleanNullableString(snapshot.platform) ?? 'instagram';
  const engine = normalizeEngineForPlatform(snapshot.engine, platform);
  const errorCode = cleanNullableString(snapshot.errorCode);
  const rawError = cleanNullableString(snapshot.error);
  const errorMessage =
    snapshot.status === 'failed' || errorCode || rawError
      ? getUserFacingErrorMessage(errorCode, rawError, platform)
      : null;

  return {
    id: snapshot.jobId,
    original_url: snapshot.url,
    normalized_url: snapshot.url,
    platform,
    title: cleanNullableString(snapshot.title),
    format: snapshot.format,
    quality: snapshot.quality,
    status: snapshot.status,
    phase_label: cleanNullableString(snapshot.phaseLabel) || getPhaseLabel(snapshot.status),
    current_engine: engine,
    selected_engine: engine,
    execution_origin: 'local',
    display_engine: formatDisplayEngine(engine, 'local', platform),
    engine_version: cleanNullableString(snapshot.engineVersion),
    strategy_used: cleanNullableString(snapshot.strategyUsed),
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
    error_message: cleanNullableString(errorMessage),
    technical_reason: cleanNullableString(snapshot.technicalReason),
    metrics: snapshot.metrics ?? null,
    thumbnail_data_url:
      cleanNullableString(snapshot.thumbnailDataUrl) ??
      items.find((i) => Boolean(i.thumbnailDataUrl))?.thumbnailDataUrl ??
      null,
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
  const engine = normalizeEngineForPlatform(job.selected_engine, job.platform);
  return {
    id: job.id,
    original_url: job.original_url,
    normalized_url: job.normalized_url,
    platform: job.platform,
    title: cleanNullableString(job.title),
    format: job.format,
    quality: job.quality,
    status: job.status,
    phase_label: cleanNullableString(job.phase_label) || getPhaseLabel(job.status),
    selected_engine: engine,
    execution_origin: 'local',
    display_engine: formatDisplayEngine(engine, 'local', job.platform),
    status_mode: job.status_mode,
    error_code: cleanNullableString(job.error_code),
    error_message: cleanNullableString(job.error_message),
    created_at: job.created_at,
    completed_at: job.completed_at,
    thumbnail_data_url:
      cleanNullableString(job.thumbnail_data_url) ??
      cleanNullableString(job.items?.find((i) => Boolean(cleanNullableString(i.thumbnailDataUrl)))?.thumbnailDataUrl) ??
      cleanNullableString(primaryFile?.thumbnail_data_url),
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
