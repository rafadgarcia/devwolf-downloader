-- 001_initial.sql: DropLink SQLite WAL schema

CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    username TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'admin',
    is_active INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash TEXT UNIQUE NOT NULL,
    csrf_token TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sessions_user_id ON sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_expires_at ON sessions(expires_at);

CREATE TABLE IF NOT EXISTS settings (
    user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    auto_download INTEGER NOT NULL DEFAULT 1,
    default_quality TEXT NOT NULL DEFAULT 'best',
    data_saver INTEGER NOT NULL DEFAULT 0,
    file_retention_hours INTEGER NOT NULL DEFAULT 24,
    theme TEXT NOT NULL DEFAULT 'dark',
    enabled_engines TEXT NOT NULL DEFAULT '["yt-dlp","gallery-dl","cobalt"]',
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS jobs (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    original_url TEXT NOT NULL,
    normalized_url TEXT NOT NULL,
    platform TEXT NOT NULL,
    route_id TEXT NOT NULL,
    title TEXT,
    format TEXT NOT NULL DEFAULT 'auto',
    quality TEXT NOT NULL DEFAULT 'best',
    idempotency_key TEXT,
    dedup_hash TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'queued',
    phase_label TEXT NOT NULL DEFAULT 'Link detectado',
    current_engine TEXT,
    selected_engine TEXT,
    bytes_downloaded INTEGER NOT NULL DEFAULT 0,
    total_bytes INTEGER,
    percent REAL,
    is_indeterminate INTEGER NOT NULL DEFAULT 1,
    error_code TEXT,
    error_message TEXT,
    cancel_requested INTEGER NOT NULL DEFAULT 0,
    lease_owner TEXT,
    lease_expires_at TEXT,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    started_at TEXT,
    completed_at TEXT,
    expires_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_jobs_user_created ON jobs(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_jobs_user_status ON jobs(user_id, status);
CREATE INDEX IF NOT EXISTS idx_jobs_dedup ON jobs(user_id, dedup_hash, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_jobs_idempotency ON jobs(user_id, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_jobs_lease ON jobs(status, lease_expires_at);

CREATE TABLE IF NOT EXISTS job_attempts (
    id TEXT PRIMARY KEY,
    job_id TEXT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL,
    engine TEXT NOT NULL,
    status TEXT NOT NULL,
    error_category TEXT,
    error_code TEXT,
    error_message TEXT,
    started_at TEXT NOT NULL,
    ended_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_job_attempts_job_id ON job_attempts(job_id, attempt_number);

CREATE TABLE IF NOT EXISTS files (
    id TEXT PRIMARY KEY,
    job_id TEXT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    filename TEXT NOT NULL,
    storage_path TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    duration_seconds REAL,
    is_archive INTEGER NOT NULL DEFAULT 0,
    item_count INTEGER NOT NULL DEFAULT 1,
    missing_items INTEGER NOT NULL DEFAULT 0,
    sha256 TEXT,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    deleted_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_files_job_id ON files(job_id);
CREATE INDEX IF NOT EXISTS idx_files_expires_at ON files(expires_at);

CREATE TABLE IF NOT EXISTS engine_status (
    engine TEXT PRIMARY KEY,
    installed_version TEXT,
    health TEXT NOT NULL DEFAULT 'unknown',
    circuit_state TEXT NOT NULL DEFAULT 'closed',
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    circuit_opened_at TEXT,
    circuit_retry_at TEXT,
    last_success_at TEXT,
    last_error_at TEXT,
    last_error_message TEXT,
    last_checked_at TEXT,
    maintenance_mode INTEGER NOT NULL DEFAULT 0,
    maintenance_note TEXT,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS platform_cooldowns (
    platform TEXT PRIMARY KEY,
    retry_after_at TEXT NOT NULL,
    reason TEXT,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS maintenance_events (
    id TEXT PRIMARY KEY,
    component TEXT NOT NULL,
    event_type TEXT NOT NULL,
    status TEXT NOT NULL,
    details_json TEXT,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_maintenance_component_created ON maintenance_events(component, created_at DESC);

CREATE TABLE IF NOT EXISTS share_tokens (
    token TEXT PRIMARY KEY,
    url TEXT NOT NULL,
    title TEXT,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    consumed_at TEXT
);
