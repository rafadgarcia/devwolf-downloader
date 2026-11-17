import type { PendingSharePayload } from '../types/api';

const SHARE_CACHE = 'devwolf-share-pending-v2';
const SHARE_KEY = '/__devwolf_pending_share__';
const SESSION_STORAGE_KEY = 'devwolf_pending_share_session_v3';

export function savePendingShareToSession(payload: PendingSharePayload): void {
  try {
    window.sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(payload));
  } catch {
    // Ignorar falha de sessionStorage
  }
}

export function loadPendingShareFromSession(): PendingSharePayload | null {
  try {
    const raw = window.sessionStorage.getItem(SESSION_STORAGE_KEY);
    if (!raw) return null;
    return JSON.parse(raw) as PendingSharePayload;
  } catch {
    return null;
  }
}

export function clearPendingShareFromSession(): void {
  try {
    window.sessionStorage.removeItem(SESSION_STORAGE_KEY);
  } catch {
    // Ignorar falha de sessionStorage
  }
}

export async function consumeClientShareCache(): Promise<PendingSharePayload | null> {
  if (typeof window === 'undefined' || !('caches' in window)) {
    return null;
  }
  try {
    const cache = await window.caches.open(SHARE_CACHE);
    const match = await cache.match(SHARE_KEY);
    if (!match) return null;
    const payload = (await match.json()) as PendingSharePayload;
    await cache.delete(SHARE_KEY);
    return payload;
  } catch {
    return null;
  }
}

export async function collectPendingShare(): Promise<PendingSharePayload | null> {
  const fromSw = await consumeClientShareCache();
  if (fromSw && (fromSw.url || fromSw.text || fromSw.title || fromSw.error)) {
    savePendingShareToSession(fromSw);
  }
  return loadPendingShareFromSession();
}
