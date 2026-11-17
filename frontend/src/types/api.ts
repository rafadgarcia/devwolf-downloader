export type PlatformId =
  | 'instagram'
  | 'youtube'
  | 'tiktok'
  | 'twitter'
  | 'facebook'
  | 'reddit'
  | 'pinterest';

export type EngineName = 'yt-dlp' | 'gallery-dl' | 'cobalt';

export type JobStatus =
  | 'queued'
  | 'resolving'
  | 'downloading'
  | 'processing'
  | 'ready'
  | 'failed'
  | 'cancelled'
  | 'expired';

export type QualityOption = 'best' | '1080p' | '720p' | '480p' | 'audio';
export type FormatOption = 'auto' | 'video' | 'audio' | 'image';
export type ThemeMode = 'dark' | 'light';

export interface AuthUser {
  id: string;
  username: string;
  role?: string;
}

export interface AuthMeResponse {
  authenticated: boolean;
  user: AuthUser | null;
  csrf_token: string | null;
}

export interface JobFile {
  id: string;
  filename: string;
  mime_type: string;
  size_bytes: number;
  duration_seconds?: number | null;
  is_archive?: boolean;
  item_count?: number;
  missing_items?: number;
  download_url?: string;
  expires_at?: string;
  expired?: boolean;
}

export interface JobAttempt {
  id?: string;
  attempt_number: number;
  engine: EngineName | string;
  status: string;
  error_category?: string | null;
  error_code?: string | null;
  error_message?: string | null;
  started_at?: string | null;
  ended_at?: string | null;
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
  bytes_downloaded: number;
  total_bytes: number | null;
  percent: number | null;
  is_indeterminate: boolean;
  error_code?: string | null;
  error_message?: string | null;
  deduplicated?: boolean;
  files: JobFile[];
  attempts?: JobAttempt[];
  created_at: string;
  updated_at: string;
  started_at?: string | null;
  completed_at?: string | null;
  expires_at?: string | null;
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
  error_message?: string | null;
  created_at: string;
  completed_at?: string | null;
  file?: JobFile | null;
  has_active_file: boolean;
  file_expired: boolean;
}

export interface CreateJobPayload {
  url: string;
  format: FormatOption;
  quality: QualityOption;
  idempotency_key: string;
  force_manual?: boolean;
}

export interface HistoryResponse {
  items: HistoryItem[];
  total: number;
  page: number;
  page_size: number;
  has_more?: boolean;
}

export type EngineHealthState = 'online' | 'degraded' | 'maintenance' | 'offline';
export type CircuitBreakerState = 'closed' | 'open' | 'half_open';

export interface CobaltUpdaterStatus {
  maintenance: boolean;
  state: string;
  current_digest?: string | null;
  current_version?: string | null;
  previous_digest?: string | null;
  rejected_digest?: string | null;
  rejected_until?: string | null;
  last_check_at?: string | null;
  last_update_at?: string | null;
  last_rollback_at?: string | null;
  last_error?: string | null;
}

export interface EngineInfo {
  engine: EngineName;
  installed_version?: string | null;
  health: EngineHealthState | string;
  circuit_state: CircuitBreakerState | string;
  consecutive_failures?: number;
  circuit_retry_at?: string | null;
  last_success_at?: string | null;
  last_error_at?: string | null;
  last_error_message?: string | null;
  last_checked_at?: string | null;
  maintenance_mode?: boolean;
  maintenance_note?: string | null;
  updater_status?: CobaltUpdaterStatus | null;
}

export interface EnginesResponse {
  engines: EngineInfo[];
  disclaimer?: string;
}

export interface UserSettings {
  auto_download: boolean;
  default_quality: QualityOption;
  data_saver: boolean;
  file_retention_hours: number;
  theme: ThemeMode;
  enabled_engines: EngineName[];
}

export interface PendingSharePayload {
  title: string;
  text: string;
  url: string;
  error?: string | null;
  receivedAt?: number;
}
