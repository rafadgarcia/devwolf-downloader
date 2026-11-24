export type PlatformId =
  | 'instagram'
  | 'threads'
  | 'youtube'
  | 'tiktok'
  | 'twitter'
  | 'facebook'
  | 'reddit'
  | 'pinterest';

export type EngineName =
  | 'dnr'
  | 'yt-dlp'
  | 'gallery-dl'
  | 'local-ytdlp'
  | 'local-pinterest'
  | 'local-reddit';

export type EngineOrigin = 'local';
export type UpdateChannel = 'stable' | 'nightly';
export type StatusSegmentDuration = 0 | 30 | 45 | 60;

export type DevwolfErrorCode =
  | 'AUTH_REQUIRED'
  | 'PRIVATE_MEDIA'
  | 'RATE_LIMITED'
  | 'CAPTCHA_REQUIRED'
  | 'MEDIA_UNAVAILABLE'
  | 'UNSUPPORTED_MEDIA'
  | 'CANCELLED'
  | 'STORAGE_ERROR'
  | 'EXTRACTOR_OUTDATED'
  | 'EXTRACTION_FAILED'
  | 'NETWORK_ERROR';

export type JobStatus =
  | 'queued'
  | 'resolving'
  | 'downloading'
  | 'processing'
  | 'ready'
  | 'failed'
  | 'cancelled'
  | 'expired';

export type CarouselMediaType = 'video' | 'image' | 'audio';
export type CarouselItemStatus = 'queued' | 'downloading' | 'ready' | 'failed';

export type QualityOption = 'best' | '1080p' | '720p' | '480p' | 'audio';
export type FormatOption = 'auto' | 'video' | 'audio' | 'image';
export type ThemeMode = 'dark' | 'light';

export interface CarouselItemState {
  id: string;
  index: number;
  type: CarouselMediaType;
  fileName: string;
  mimeType: string;
  width: number | null;
  height: number | null;
  sizeBytes: number;
  bytesDownloaded: number;
  percent: number | null;
  status: CarouselItemStatus;
  mediaUri: string | null;
  displayPath: string | null;
  filePath?: string | null;
  thumbnailDataUrl?: string | null;
  durationSeconds?: number | null;
  isSegment?: boolean;
  segmentIndex?: number | null;
  isOriginal?: boolean;
}

export interface JobFile {
  id: string;
  index?: number;
  type?: CarouselMediaType;
  filename: string;
  mime_type: string;
  width?: number | null;
  height?: number | null;
  size_bytes: number;
  bytes_downloaded?: number;
  percent?: number | null;
  status?: CarouselItemStatus;
  duration_seconds?: number | null;
  is_archive?: boolean;
  item_count?: number;
  missing_items?: number;
  file_path?: string | null;
  media_uri?: string | null;
  display_path?: string | null;
  thumbnail_data_url?: string | null;
  is_segment?: boolean;
  segment_index?: number | null;
  is_original?: boolean;
}

export interface StrategyAttemptInfo {
  strategyName: string;
  durationMs: number;
  httpRequests: number;
  success: boolean;
  errorCode?: string | null;
  note?: string | null;
}

export interface JobPerformanceMetrics {
  platform?: string | null;
  engineUsed?: string | null;
  engineVersion?: string | null;
  strategyUsed?: string | null;
  mediaTypeIdentified?: string | null;
  resolutionMs?: number | null;
  ttfbMs?: number | null;
  transferMs?: number | null;
  processingMs?: number | null;
  galleryPublishMs?: number | null;
  totalJobMs?: number | null;
  httpRequestCount?: number | null;
  httpStatus?: number | null;
  exitCode?: number | null;
  rawErrorSanitized?: string | null;
  errorCode?: string | null;
  strategyAttempts?: StrategyAttemptInfo[];
}

export interface DiagnosticsReport {
  platform?: string | null;
  engineUsed: string | null;
  engineVersion: string | null;
  lastUpdateAt: string | null;
  strategyUsed: string | null;
  strategyAttempts?: StrategyAttemptInfo[];
  httpStatus?: number | null;
  exitCode?: number | null;
  rawErrorSanitized?: string | null;
  mediaTypeIdentified?: string | null;
  resolutionMs: number | null;
  ttfbMs: number | null;
  transferMs: number | null;
  processingMs: number | null;
  galleryPublishMs: number | null;
  totalJobMs: number | null;
  httpRequestCount: number | null;
  errorCode: DevwolfErrorCode | string | null;
  technicalReason: string | null;
  sanitizedLog: string | null;
  generatedAt?: string | null;
}

export interface JobResponse {
  id: string;
  original_url: string;
  normalized_url: string;
  platform: PlatformId | string;
  route_id?: string;
  title?: string | null;
  format: FormatOption | string;
  quality: QualityOption | string;
  status: JobStatus;
  phase_label: string;
  current_engine?: EngineName | string | null;
  selected_engine?: EngineName | string | null;
  execution_origin?: EngineOrigin;
  display_engine?: string | null;
  engine_version?: string | null;
  strategy_used?: string | null;
  bytes_downloaded: number;
  total_bytes: number | null;
  percent: number | null;
  is_indeterminate: boolean;
  speed_bytes_per_sec?: number | null;
  eta_seconds?: number | null;
  status_mode?: boolean;
  segment_duration?: number | null;
  keep_original?: boolean;
  source_share_id?: string | null;
  from_share_intent?: boolean;
  fallback_notice?: string | null;
  error_code?: DevwolfErrorCode | string | null;
  error_message?: string | null;
  technical_reason?: string | null;
  metrics?: JobPerformanceMetrics | null;
  deduplicated?: boolean;
  thumbnail_data_url?: string | null;
  files: JobFile[];
  items?: CarouselItemState[];
  created_at: string;
  updated_at: string;
  started_at?: string | null;
  completed_at?: string | null;
}

export interface HistoryItem {
  id: string;
  original_url: string;
  normalized_url: string;
  platform: PlatformId | string;
  title?: string | null;
  format: FormatOption | string;
  quality: QualityOption | string;
  status: JobStatus;
  phase_label: string;
  selected_engine?: EngineName | string | null;
  execution_origin?: EngineOrigin;
  display_engine?: string | null;
  status_mode?: boolean;
  error_code?: DevwolfErrorCode | string | null;
  error_message?: string | null;
  created_at: string;
  completed_at?: string | null;
  thumbnail_data_url?: string | null;
  file?: JobFile | null;
  files?: JobFile[];
  items?: CarouselItemState[];
  has_active_file: boolean;
}

export interface LocalEngineStatus {
  available: boolean;
  ytdlpVersion: string | null;
  wrapperVersion?: string | null;
  latestYtdlpVersion: string | null;
  updateAvailable: boolean;
  updateChannel?: UpdateChannel;
  ffmpegVersion: string | null;
  gallerydlVersion?: string | null;
  gallerydlAvailable?: boolean;
  dnrAvailable?: boolean;
  dnrStrategies?: string[];
  dnrAvgResolutionMs?: number | null;
  previousVersion: string | null;
  lastCheckAt: string | null;
  lastUpdateAt: string | null;
  activeDownloads: number;
  canUpdate: boolean;
  lastError: string | null;
}

export interface PlatformSessionInfo {
  platform: PlatformId | string;
  configured: boolean;
  updatedAt: string | null;
  authType?: 'webview_cookie' | 'oauth_token' | 'cookie' | string | null;
  username?: string | null;
  clientId?: string | null;
  expiresAtEpochMs?: number | null;
  source?: string | null;
  hasCookie?: boolean;
  hasAccessToken?: boolean;
  hasSessionId?: boolean;
}

export interface UserSettings {
  auto_download: boolean;
  default_quality: QualityOption;
  data_saver: boolean;
  theme: ThemeMode;
  status_mode_enabled: boolean;
  status_max_duration_seconds: StatusSegmentDuration;
  status_keep_original: boolean;
  auto_update_engines: boolean;
  update_channel: UpdateChannel;
  notifications_enabled: boolean;
  clipboard_bubble_enabled: boolean;
  /** Atualizações do app: considerar releases de teste (pre-release / rc). */
  include_prerelease_updates: boolean;
}

export interface PendingSharePayload {
  id?: string;
  title: string;
  text: string;
  url: string;
  error?: string | null;
  receivedAt?: number;
  autoStart?: boolean;
  fromNativeIntent?: boolean;
}
