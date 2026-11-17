import type { FormatOption, QualityOption } from '../types/api';

export const DEDUPE_WINDOW_MS = 10 * 60 * 1000; // 10 minutos
const STORAGE_KEY = 'droplink_recent_auto_downloads_v1';
const ACTIVE_KEY = 'droplink_active_fingerprints_v1';

export interface DedupeEntry {
  fingerprint: string;
  jobId: string;
  timestamp: number;
}

export function buildFingerprint(
  normalizedUrl: string,
  format: FormatOption | string,
  quality: QualityOption | string
): string {
  return `${normalizedUrl}::${format}::${quality}`;
}

function readEntries(storageKey: string): DedupeEntry[] {
  try {
    const raw = window.localStorage.getItem(storageKey);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function writeEntries(storageKey: string, entries: DedupeEntry[]): void {
  try {
    window.localStorage.setItem(storageKey, JSON.stringify(entries));
  } catch {
    // Ignorar erros de cota ou modo privado
  }
}

export function pruneExpiredEntries(now = Date.now()): DedupeEntry[] {
  const entries = readEntries(STORAGE_KEY).filter(
    (entry) => typeof entry.timestamp === 'number' && now - entry.timestamp < DEDUPE_WINDOW_MS
  );
  writeEntries(STORAGE_KEY, entries);
  return entries;
}

export function isDuplicateAutoDownload(fingerprint: string, now = Date.now()): DedupeEntry | null {
  const active = readEntries(ACTIVE_KEY);
  const activeHit = active.find((item) => item.fingerprint === fingerprint);
  if (activeHit) {
    return activeHit;
  }

  const recent = pruneExpiredEntries(now);
  return recent.find((item) => item.fingerprint === fingerprint) ?? null;
}

export function markActiveJobFingerprint(
  fingerprint: string,
  jobId: string,
  now = Date.now()
): void {
  const active = readEntries(ACTIVE_KEY).filter((item) => item.fingerprint !== fingerprint);
  active.push({ fingerprint, jobId, timestamp: now });
  writeEntries(ACTIVE_KEY, active);
}

export function clearActiveJobFingerprint(fingerprint: string): void {
  const active = readEntries(ACTIVE_KEY).filter((item) => item.fingerprint !== fingerprint);
  writeEntries(ACTIVE_KEY, active);
}

export function clearActiveJobById(jobId: string): void {
  const active = readEntries(ACTIVE_KEY).filter((item) => item.jobId !== jobId);
  writeEntries(ACTIVE_KEY, active);
}

export function markRecentAutoDownload(
  fingerprint: string,
  jobId: string,
  now = Date.now()
): void {
  const recent = pruneExpiredEntries(now).filter((item) => item.fingerprint !== fingerprint);
  recent.push({ fingerprint, jobId, timestamp: now });
  writeEntries(STORAGE_KEY, recent);
}
