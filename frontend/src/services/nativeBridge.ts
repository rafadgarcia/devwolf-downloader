import { Capacitor, registerPlugin, type PluginListenerHandle } from '@capacitor/core';
import type {
  CarouselItemStatus,
  CarouselMediaType,
  DiagnosticsReport,
  FormatOption,
  JobPerformanceMetrics,
  LocalEngineStatus,
  PlatformId,
  PlatformSessionInfo,
  QualityOption,
  UpdateChannel,
} from '../types/api';

export interface ShareIntentPayload {
  id: string;
  url: string;
  rawText?: string;
  title?: string;
  receivedAt: string;
  autoStart: boolean;
}

export interface LocalOutputFile {
  id: string;
  index?: number;
  type?: CarouselMediaType;
  fileName: string;
  filePath?: string | null;
  mediaUri: string | null;
  displayPath: string | null;
  mimeType: string;
  width?: number | null;
  height?: number | null;
  sizeBytes: number;
  bytesDownloaded?: number;
  percent?: number | null;
  status?: CarouselItemStatus;
  durationSeconds?: number | null;
  isSegment?: boolean;
  segmentIndex?: number | null;
  isOriginal?: boolean;
}

export interface LocalDownloadProgressEvent {
  jobId: string;
  status: 'queued' | 'resolving' | 'downloading' | 'processing' | 'ready' | 'failed' | 'cancelled';
  phaseLabel: string;
  engine?: string;
  engineVersion?: string | null;
  strategyUsed?: string | null;
  bytesDownloaded: number;
  totalBytes: number | null;
  percent: number | null;
  isIndeterminate: boolean;
  speedBytesPerSec: number | null;
  etaSeconds: number | null;
  title?: string | null;
  platform?: string | null;
  error?: string | null;
  errorCode?: string | null;
  technicalReason?: string | null;
  metrics?: JobPerformanceMetrics | null;
  files?: LocalOutputFile[];
  items?: LocalOutputFile[];
}

export interface LocalDownloadSnapshot {
  jobId: string;
  url: string;
  format: FormatOption | string;
  quality: QualityOption | string;
  statusMode: boolean;
  segmentDurationSeconds?: number | null;
  keepOriginal?: boolean;
  status: LocalDownloadProgressEvent['status'];
  phaseLabel: string;
  engine?: string | null;
  engineVersion?: string | null;
  strategyUsed?: string | null;
  bytesDownloaded: number;
  totalBytes: number | null;
  percent: number | null;
  isIndeterminate: boolean;
  speedBytesPerSec: number | null;
  etaSeconds: number | null;
  title?: string | null;
  platform?: string | null;
  error?: string | null;
  errorCode?: string | null;
  technicalReason?: string | null;
  metrics?: JobPerformanceMetrics | null;
  files?: LocalOutputFile[];
  items?: LocalOutputFile[];
  createdAt?: string;
  updatedAt?: string;
}

export interface DevwolfNativePlugin {
  readClipboard(): Promise<{ text: string | null }>;
  getPendingShareIntent(): Promise<{ intent: ShareIntentPayload | null }>;
  clearPendingShareIntent(opts: { id: string }): Promise<void>;
  returnToSourceApp(): Promise<{ returned: boolean }>;
  startLocalDownload(opts: {
    jobId: string;
    url: string;
    format: FormatOption;
    quality: QualityOption;
    statusMode: boolean;
    segmentDurationSeconds?: number | null;
    keepOriginal?: boolean;
  }): Promise<{ jobId: string; status: string }>;
  cancelLocalDownload(opts: { jobId: string }): Promise<{ cancelled: boolean }>;
  getLocalDownloadQueue(): Promise<{ jobs: LocalDownloadSnapshot[] }>;
  saveToGallery(opts: {
    filePath: string;
    mimeType: string;
    title?: string;
  }): Promise<{ mediaUri: string; displayPath: string; fileName: string; sizeBytes: number }>;
  shareMedia(opts: {
    uris?: string[];
    filePaths?: string[];
    mimeType?: string;
    title?: string;
    text?: string;
    targetPackage?: string;
  }): Promise<{ launched: boolean }>;
  openMedia(opts: { uri?: string; filePath?: string; mimeType?: string }): Promise<{ opened: boolean }>;
  deleteMedia(opts: { uri?: string; filePath?: string }): Promise<{ deleted: boolean }>;
  getLocalEngineStatus(): Promise<Omit<LocalEngineStatus, 'available'>>;
  updateLocalEngine(opts?: {
    channel?: UpdateChannel;
  }): Promise<{
    updated: boolean;
    version: string | null;
    previousVersion: string | null;
    message: string;
  }>;
  rollbackLocalEngine(): Promise<{
    rolledBack: boolean;
    version: string | null;
    message: string;
  }>;
  getDiagnosticsReport(): Promise<DiagnosticsReport>;
  clearTempCache(): Promise<{ freedBytes: number; filesRemoved: number }>;
  openInstagramLoginWebView(): Promise<{
    saved: boolean;
    platform?: string;
    updatedAt?: string | null;
    message?: string | null;
  }>;
  openPlatformLoginWebView(opts: {
    platform: 'instagram' | 'reddit' | 'threads';
    redditClientId?: string;
  }): Promise<{
    saved: boolean;
    platform?: string;
    updatedAt?: string | null;
    message?: string | null;
  }>;
  getPlatformSessions(): Promise<{
    sessions: PlatformSessionInfo[];
  }>;
  savePlatformSession(opts: {
    platform: string;
    cookieHeader?: string;
    accessToken?: string;
    clientId?: string;
    username?: string;
    authType?: string;
  }): Promise<{ saved: boolean; platform: string; updatedAt: string }>;
  revokePlatformSession(opts: {
    platform: string;
  }): Promise<{ revoked: boolean; platform: string }>;
  addListener(
    eventName: 'shareIntentReceived',
    listenerFunc: (event: ShareIntentPayload) => void
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'localDownloadProgress',
    listenerFunc: (event: LocalDownloadProgressEvent) => void
  ): Promise<PluginListenerHandle>;
}

const DevwolfNative = registerPlugin<DevwolfNativePlugin>('DevwolfNative');

export function isNativeAndroid(): boolean {
  try {
    return Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android';
  } catch {
    return false;
  }
}

export function isNativePlatform(): boolean {
  try {
    return Capacitor.isNativePlatform();
  } catch {
    return false;
  }
}

export const nativeBridge = {
  isNativeAndroid,
  isNativePlatform,

  async readClipboard(): Promise<string | null> {
    if (isNativePlatform()) {
      try {
        const res = await DevwolfNative.readClipboard();
        return res?.text ?? null;
      } catch {
        // Fallback para Web API se plugin falhar
      }
    }
    if (
      typeof navigator !== 'undefined' &&
      navigator.clipboard &&
      typeof navigator.clipboard.readText === 'function'
    ) {
      try {
        return await navigator.clipboard.readText();
      } catch {
        return null;
      }
    }
    return null;
  },

  async getPendingShareIntent(): Promise<ShareIntentPayload | null> {
    if (!isNativePlatform()) return null;
    try {
      const res = await DevwolfNative.getPendingShareIntent();
      return res?.intent ?? null;
    } catch {
      return null;
    }
  },

  async clearPendingShareIntent(id: string): Promise<void> {
    if (!isNativePlatform() || !id) return;
    try {
      await DevwolfNative.clearPendingShareIntent({ id });
    } catch {
      // Ignorar erro de limpeza
    }
  },

  async returnToSourceApp(): Promise<boolean> {
    if (!isNativePlatform()) return false;
    try {
      const res = await DevwolfNative.returnToSourceApp();
      return Boolean(res?.returned);
    } catch {
      return false;
    }
  },

  async startLocalDownload(opts: {
    jobId: string;
    url: string;
    format: FormatOption;
    quality: QualityOption;
    statusMode: boolean;
    segmentDurationSeconds?: number | null;
    keepOriginal?: boolean;
  }): Promise<{ jobId: string; status: string }> {
    if (!isNativePlatform()) {
      throw new Error('Motor de download local disponível no aplicativo Android do Devwolf Downloader.');
    }
    return DevwolfNative.startLocalDownload(opts);
  },

  async cancelLocalDownload(jobId: string): Promise<boolean> {
    if (!isNativePlatform()) return false;
    try {
      const res = await DevwolfNative.cancelLocalDownload({ jobId });
      return Boolean(res?.cancelled);
    } catch {
      return false;
    }
  },

  async getLocalDownloadQueue(): Promise<LocalDownloadSnapshot[]> {
    if (!isNativePlatform()) return [];
    try {
      const res = await DevwolfNative.getLocalDownloadQueue();
      return Array.isArray(res?.jobs) ? res.jobs : [];
    } catch {
      return [];
    }
  },

  async saveToGallery(opts: {
    filePath: string;
    mimeType: string;
    title?: string;
  }): Promise<{ mediaUri: string; displayPath: string; fileName: string; sizeBytes: number } | null> {
    if (!isNativePlatform()) return null;
    return DevwolfNative.saveToGallery(opts);
  },

  async shareMedia(opts: {
    uris?: string[];
    filePaths?: string[];
    mimeType?: string;
    title?: string;
    text?: string;
    targetWhatsApp?: boolean;
  }): Promise<boolean> {
    if (isNativePlatform()) {
      const res = await DevwolfNative.shareMedia({
        uris: opts.uris,
        filePaths: opts.filePaths,
        mimeType: opts.mimeType,
        title: opts.title ?? 'Devwolf Downloader',
        text: opts.text,
        targetPackage: opts.targetWhatsApp ? 'com.whatsapp' : undefined,
      });
      return Boolean(res?.launched);
    }

    if (typeof navigator !== 'undefined' && typeof navigator.share === 'function') {
      try {
        await navigator.share({
          title: opts.title ?? 'Devwolf Downloader',
          text: opts.text,
          url: opts.uris?.[0],
        });
        return true;
      } catch {
        return false;
      }
    }
    return false;
  },

  async openMedia(opts: { uri?: string; filePath?: string; mimeType?: string }): Promise<boolean> {
    if (!isNativePlatform()) {
      if (opts.uri && typeof window !== 'undefined') {
        window.open(opts.uri, '_blank', 'noopener');
        return true;
      }
      return false;
    }
    const res = await DevwolfNative.openMedia(opts);
    return Boolean(res?.opened);
  },

  async deleteMedia(opts: { uri?: string; filePath?: string }): Promise<boolean> {
    if (!isNativePlatform()) return false;
    const res = await DevwolfNative.deleteMedia(opts);
    return Boolean(res?.deleted);
  },

  async getLocalEngineStatus(): Promise<LocalEngineStatus> {
    if (!isNativePlatform()) {
      return {
        available: false,
        ytdlpVersion: null,
        wrapperVersion: null,
        latestYtdlpVersion: null,
        updateAvailable: false,
        updateChannel: 'stable',
        ffmpegVersion: null,
        gallerydlVersion: null,
        gallerydlAvailable: false,
        dnrAvailable: false,
        dnrStrategies: ['EmbedCaptioned', 'WebPageJson', 'AuthorizedWebApi'],
        dnrAvgResolutionMs: null,
        previousVersion: null,
        lastCheckAt: null,
        lastUpdateAt: null,
        activeDownloads: 0,
        canUpdate: false,
        lastError: 'Disponível no aplicativo Android nativo.',
      };
    }
    try {
      const status = await DevwolfNative.getLocalEngineStatus();
      return {
        available: true,
        ...status,
      };
    } catch (err) {
      return {
        available: false,
        ytdlpVersion: null,
        wrapperVersion: null,
        latestYtdlpVersion: null,
        updateAvailable: false,
        updateChannel: 'stable',
        ffmpegVersion: null,
        gallerydlVersion: null,
        gallerydlAvailable: false,
        dnrAvailable: false,
        dnrStrategies: [],
        dnrAvgResolutionMs: null,
        previousVersion: null,
        lastCheckAt: null,
        lastUpdateAt: null,
        activeDownloads: 0,
        canUpdate: false,
        lastError: err instanceof Error ? err.message : 'Falha ao consultar motores locais.',
      };
    }
  },

  async updateLocalEngine(channel: UpdateChannel = 'stable') {
    if (!isNativePlatform()) {
      throw new Error('Atualização do motor local disponível apenas no Android.');
    }
    return DevwolfNative.updateLocalEngine({ channel });
  },

  async rollbackLocalEngine() {
    if (!isNativePlatform()) {
      throw new Error('Rollback do motor local disponível apenas no Android.');
    }
    return DevwolfNative.rollbackLocalEngine();
  },

  async getDiagnosticsReport(): Promise<DiagnosticsReport | null> {
    if (!isNativePlatform()) return null;
    try {
      return await DevwolfNative.getDiagnosticsReport();
    } catch {
      return null;
    }
  },

  async clearTempCache(): Promise<{ freedBytes: number; filesRemoved: number }> {
    if (!isNativePlatform()) {
      return { freedBytes: 0, filesRemoved: 0 };
    }
    return DevwolfNative.clearTempCache();
  },

  async openInstagramLoginWebView(): Promise<{
    saved: boolean;
    platform?: string;
    updatedAt?: string | null;
    message?: string | null;
  }> {
    if (!isNativePlatform()) {
      throw new Error('Login via WebView oficial disponível apenas no aplicativo Android.');
    }
    return DevwolfNative.openInstagramLoginWebView();
  },

  async openPlatformLoginWebView(opts: {
    platform: 'instagram' | 'reddit' | 'threads';
    redditClientId?: string;
  }): Promise<{
    saved: boolean;
    platform?: string;
    updatedAt?: string | null;
    message?: string | null;
  }> {
    if (!isNativePlatform()) {
      throw new Error('Login via WebView/OAuth2 disponível apenas no aplicativo Android.');
    }
    return DevwolfNative.openPlatformLoginWebView(opts);
  },

  async getPlatformSessions(): Promise<PlatformSessionInfo[]> {
    if (!isNativePlatform()) return [];
    try {
      const res = await DevwolfNative.getPlatformSessions();
      return Array.isArray(res?.sessions) ? res.sessions : [];
    } catch {
      return [];
    }
  },

  async savePlatformSession(
    platform: PlatformId | string,
    cookieHeader: string
  ): Promise<{ saved: boolean; platform: string; updatedAt: string }> {
    if (!isNativePlatform()) {
      throw new Error('Cofre local de sessões disponível apenas no aplicativo Android.');
    }
    return DevwolfNative.savePlatformSession({ platform, cookieHeader });
  },

  async revokePlatformSession(platform: PlatformId | string): Promise<boolean> {
    if (!isNativePlatform()) return false;
    const res = await DevwolfNative.revokePlatformSession({ platform });
    return Boolean(res?.revoked);
  },

  async onShareIntentReceived(
    callback: (payload: ShareIntentPayload) => void
  ): Promise<() => void> {
    if (!isNativePlatform()) {
      return () => undefined;
    }
    const handle = await DevwolfNative.addListener('shareIntentReceived', callback);
    return () => {
      void handle.remove();
    };
  },

  async onLocalDownloadProgress(
    callback: (event: LocalDownloadProgressEvent) => void
  ): Promise<() => void> {
    if (!isNativePlatform()) {
      return () => undefined;
    }
    const handle = await DevwolfNative.addListener('localDownloadProgress', callback);
    return () => {
      void handle.remove();
    };
  },
};
