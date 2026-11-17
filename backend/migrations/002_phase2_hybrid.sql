-- 002_phase2_hybrid.sql: Devwolf Downloader Phase 2 hybrid settings, status mode, and segments

ALTER TABLE settings ADD COLUMN preferred_execution_mode TEXT NOT NULL DEFAULT 'auto';
ALTER TABLE settings ADD COLUMN status_mode_enabled INTEGER NOT NULL DEFAULT 0;
ALTER TABLE settings ADD COLUMN status_max_duration_seconds INTEGER NOT NULL DEFAULT 30;
ALTER TABLE settings ADD COLUMN status_keep_original INTEGER NOT NULL DEFAULT 0;
ALTER TABLE settings ADD COLUMN auto_update_engines INTEGER NOT NULL DEFAULT 1;
ALTER TABLE settings ADD COLUMN notifications_enabled INTEGER NOT NULL DEFAULT 1;

ALTER TABLE jobs ADD COLUMN status_mode INTEGER NOT NULL DEFAULT 0;
ALTER TABLE jobs ADD COLUMN segment_duration INTEGER;
ALTER TABLE jobs ADD COLUMN keep_original INTEGER NOT NULL DEFAULT 0;

ALTER TABLE files ADD COLUMN is_original INTEGER NOT NULL DEFAULT 0;
ALTER TABLE files ADD COLUMN segment_index INTEGER;
