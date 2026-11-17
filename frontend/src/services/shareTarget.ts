import type { PendingSharePayload } from '../types/api';
import { api } from './api';

const SHARE_CACHE = 'droplink-share-pending-v1';
const SHARE_KEY = '/__droplink_pending_share__';
const SESSION_STORAGE_KEY = 'droplink_pending_share_session_v1';

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

/**
 * Coleta compartilhamento pendente do SW cache, sessão local ou backend (quando SW não interceptou o POST),
 * preservando-o em sessionStorage enquanto o usuário autentica.
 */
export async function collectPendingShare(isAuthenticated: boolean): Promise<PendingSharePayload | null> {
  const fromSw = await consumeClientShareCache();
  if (fromSw && (fromSw.url || fromSw.text || fromSw.title || fromSw.error)) {
    savePendingShareToSession(fromSw);
  }

  if (isAuthenticated) {
    const fromServer = await api.consumeServerPendingShare();
    if (fromServer && (fromServer.url || fromServer.text || fromServer.title || fromServer.error)) {
      savePendingShareToSession(fromServer);
    }
  }

  return loadPendingShareFromSession();
}
