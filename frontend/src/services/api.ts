import type {
  AuthMeResponse,
  CreateJobPayload,
  EngineReleasesResponse,
  EnginesResponse,
  HistoryResponse,
  JobResponse,
  PendingSharePayload,
  UserSettings,
} from '../types/api';
import {
  loadVpsBearerToken,
  normalizeVpsUrl,
  saveVpsBearerToken,
} from './settingsStorage';

export class ApiError extends Error {
  status: number;
  code?: string;

  constructor(message: string, status: number, code?: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

let csrfToken: string | null = null;
let bearerToken: string | null = typeof window !== 'undefined' ? loadVpsBearerToken() : null;
let vpsBaseUrl = '';
let unauthorizedHandler: (() => void) | null = null;

export function setCsrfToken(token: string | null): void {
  csrfToken = token;
}

export function getCsrfToken(): string | null {
  return csrfToken;
}

export function setBearerToken(token: string | null): void {
  bearerToken = token;
  if (typeof window !== 'undefined') {
    saveVpsBearerToken(token);
  }
}

export function getBearerToken(): string | null {
  return bearerToken;
}

export function setVpsBaseUrl(url: string): void {
  vpsBaseUrl = normalizeVpsUrl(url);
}

export function getVpsBaseUrl(): string {
  return vpsBaseUrl;
}

export function buildApiUrl(path: string): string {
  if (/^https?:\/\//i.test(path)) {
    return path;
  }
  const normalizedPath = path.startsWith('/') ? path : `/${path}`;
  return vpsBaseUrl ? `${vpsBaseUrl}${normalizedPath}` : normalizedPath;
}

export function isCrossOriginVps(): boolean {
  if (!vpsBaseUrl || typeof window === 'undefined') {
    return false;
  }
  try {
    return new URL(vpsBaseUrl).origin !== window.location.origin;
  } catch {
    return true;
  }
}

export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler;
}

export function getAuthHeaders(): Record<string, string> {
  const headers: Record<string, string> = {};
  if (bearerToken) {
    headers['Authorization'] = `Bearer ${bearerToken}`;
  }
  if (csrfToken) {
    headers['X-CSRF-Token'] = csrfToken;
  }
  return headers;
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase();
  const headers = new Headers(init.headers);

  if (!headers.has('Accept')) {
    headers.set('Accept', 'application/json');
  }

  if (init.body && typeof init.body === 'string' && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  if (bearerToken && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${bearerToken}`);
  }

  if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(method) && csrfToken) {
    headers.set('X-CSRF-Token', csrfToken);
  }

  const response = await fetch(buildApiUrl(path), {
    ...init,
    method,
    headers,
    credentials: 'include',
  });

  if (response.status === 401 && !path.startsWith('/api/auth/')) {
    unauthorizedHandler?.();
  }

  const contentType = response.headers.get('content-type') ?? '';
  const isJson = contentType.includes('application/json');
  const data = isJson ? await response.json().catch(() => null) : null;

  if (!response.ok) {
    const message =
      (data && (data.detail || data.message || data.error)) ||
      (response.status === 429
        ? 'Muitas tentativas seguidas. Aguarde alguns instantes.'
        : `Erro na requisição (${response.status}).`);
    throw new ApiError(
      typeof message === 'string' ? message : JSON.stringify(message),
      response.status,
      data?.code
    );
  }

  return data as T;
}

export const api = {
  async getMe(): Promise<AuthMeResponse> {
    const res = await request<AuthMeResponse>('/api/auth/me');
    if (res.csrf_token) {
      csrfToken = res.csrf_token;
    }
    const token = res.session_token ?? res.token;
    if (token) {
      setBearerToken(token);
    }
    return res;
  },

  async login(username: string, password: string): Promise<AuthMeResponse> {
    const res = await request<AuthMeResponse>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    });
    if (res.csrf_token) {
      csrfToken = res.csrf_token;
    }
    const token = res.session_token ?? res.token;
    if (token) {
      setBearerToken(token);
    }
    return res;
  },

  async logout(): Promise<void> {
    try {
      await request('/api/auth/logout', { method: 'POST' });
    } finally {
      csrfToken = null;
      setBearerToken(null);
    }
  },

  async createJob(payload: CreateJobPayload): Promise<JobResponse> {
    return request<JobResponse>('/api/jobs', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },

  async getJob(jobId: string): Promise<JobResponse> {
    return request<JobResponse>(`/api/jobs/${encodeURIComponent(jobId)}`);
  },

  async cancelJob(jobId: string): Promise<JobResponse> {
    return request<JobResponse>(`/api/jobs/${encodeURIComponent(jobId)}/cancel`, {
      method: 'POST',
    });
  },

  async getHistory(params?: {
    q?: string;
    platform?: string;
    status?: string;
    page?: number;
    page_size?: number;
  }): Promise<HistoryResponse> {
    const sp = new URLSearchParams();
    if (params?.q) sp.set('q', params.q);
    if (params?.platform && params.platform !== 'all') sp.set('platform', params.platform);
    if (params?.status && params.status !== 'all') sp.set('status', params.status);
    if (params?.page) sp.set('page', String(params.page));
    if (params?.page_size) sp.set('page_size', String(params.page_size));
    const qs = sp.toString();
    return request<HistoryResponse>(`/api/history${qs ? `?${qs}` : ''}`);
  },

  async deleteHistory(id: string): Promise<void> {
    await request<unknown>(`/api/history/${encodeURIComponent(id)}`, {
      method: 'DELETE',
    });
  },

  async getEngines(): Promise<EnginesResponse> {
    return request<EnginesResponse>('/api/engines');
  },

  async getEngineReleases(): Promise<EngineReleasesResponse> {
    return request<EngineReleasesResponse>('/api/engines/releases');
  },

  async getSettings(): Promise<UserSettings> {
    return request<UserSettings>('/api/settings');
  },

  async updateSettings(patch: Partial<UserSettings>): Promise<UserSettings> {
    // Enviar apenas campos compatíveis com VPS (sem expor vps_url local)
    // e respeitar intervalo 5..300 do backend para status_max_duration_seconds
    const { vps_url: _omitVpsUrl, ...rest } = patch;
    const serverPatch: Record<string, unknown> = { ...rest };
    if (
      typeof serverPatch.status_max_duration_seconds === 'number' &&
      serverPatch.status_max_duration_seconds < 5
    ) {
      delete serverPatch.status_max_duration_seconds;
    }
    return request<UserSettings>('/api/settings', {
      method: 'PATCH',
      body: JSON.stringify(serverPatch),
    });
  },

  async consumeServerPendingShare(): Promise<PendingSharePayload | null> {
    try {
      const res = await request<{
        has_pending?: boolean;
        url?: string | null;
        title?: string | null;
        text?: string | null;
        error?: string | null;
        share?: PendingSharePayload | null;
      }>('/api/share-target/pending');
      if (res?.share) {
        return res.share;
      }
      if (res?.has_pending && (res.url || res.title || res.text || res.error)) {
        return {
          url: res.url ?? '',
          title: res.title ?? '',
          text: res.text ?? '',
          error: res.error ?? null,
        };
      }
      return null;
    } catch {
      return null;
    }
  },
};

export function getJobFileDownloadUrl(jobId: string, fileId: string): string {
  return buildApiUrl(`/api/jobs/${encodeURIComponent(jobId)}/files/${encodeURIComponent(fileId)}`);
}
