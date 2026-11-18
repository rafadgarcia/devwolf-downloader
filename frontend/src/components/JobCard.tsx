import {
  AlertCircle,
  ArrowLeft,
  CheckCircle2,
  ExternalLink,
  Film,
  Image as ImageIcon,
  KeyRound,
  Loader2,
  MessageCircle,
  Music,
  RefreshCw,
  Share2,
  XCircle,
} from 'lucide-react';
import { nativeBridge } from '../services/nativeBridge';
import type { CarouselItemState, JobFile, JobResponse, PlatformId } from '../types/api';
import { getUserFacingErrorMessage, normalizeErrorCode } from '../utils/errorContract';
import {
  formatBytes,
  formatDisplayEngine,
  formatDuration,
  formatResolution,
  formatSpeed,
  getPhaseLabel,
  PLATFORM_META,
} from '../utils/format';

interface JobCardProps {
  job: JobResponse;
  engineSwitchNotice: string | null;
  connectionMode?: 'local' | 'idle';
  isNativeAndroid: boolean;
  onCancel: (jobId: string) => void;
  onRetry: (url: string) => void;
  onReturnToSourceApp?: () => Promise<void>;
  onOpenSettings?: () => void;
  onOpenAccountSettings?: (platform: string) => void;
}

const ACTIVE_STATUSES = new Set(['queued', 'resolving', 'downloading', 'processing']);

function fileToCarouselItem(
  file: JobFile,
  idx: number,
  jobStatus: JobResponse['status']
): CarouselItemState {
  const mime = (file.mime_type || '').toLowerCase();
  const inferredType =
    file.type ??
    (mime.startsWith('image/') ? 'image' : mime.startsWith('audio/') ? 'audio' : 'video');
  const status =
    file.status ??
    (file.media_uri || file.file_path || jobStatus === 'ready'
      ? 'ready'
      : jobStatus === 'failed' || jobStatus === 'cancelled'
      ? 'failed'
      : 'downloading');
  return {
    id: file.id,
    index: file.index ?? idx + 1,
    type: inferredType,
    fileName: file.filename,
    mimeType: file.mime_type,
    width: file.width ?? null,
    height: file.height ?? null,
    sizeBytes: file.size_bytes ?? 0,
    bytesDownloaded: file.bytes_downloaded ?? (status === 'ready' ? file.size_bytes ?? 0 : 0),
    percent: file.percent ?? (status === 'ready' ? 100 : null),
    status,
    mediaUri: file.media_uri ?? null,
    displayPath: file.display_path ?? null,
    filePath: file.file_path ?? null,
    thumbnailDataUrl: file.thumbnail_data_url ?? null,
    durationSeconds: file.duration_seconds ?? null,
    isSegment: file.is_segment,
    segmentIndex: file.segment_index ?? null,
    isOriginal: file.is_original,
  };
}

function getItemLabel(item: CarouselItemState, totalCount: number): string {
  if (item.isOriginal) return 'Original';
  if (item.isSegment && item.segmentIndex) return `Parte ${item.segmentIndex}`;
  const kind = item.type === 'image' ? 'Foto' : item.type === 'audio' ? 'Áudio' : 'Vídeo';
  return `${kind} ${item.index}/${totalCount}`;
}

function getItemStatusLabel(status: CarouselItemState['status'], percent: number | null): string {
  switch (status) {
    case 'ready':
      return 'Pronto';
    case 'failed':
      return 'Falhou';
    case 'downloading':
      return percent !== null ? `${Math.round(percent)}%` : 'Baixando';
    case 'queued':
    default:
      return 'Na fila';
  }
}

function getAuthActionLabel(platform?: string | null): string {
  switch ((platform ?? '').trim().toLowerCase()) {
    case 'reddit':
      return 'Autenticar no Reddit';
    case 'threads':
      return 'Autenticar no Threads';
    case 'instagram':
      return 'Autenticar no Instagram';
    default:
      return 'Abrir Contas e Sessões';
  }
}

export function JobCard({
  job,
  engineSwitchNotice,
  isNativeAndroid,
  onCancel,
  onRetry,
  onReturnToSourceApp,
  onOpenSettings,
  onOpenAccountSettings,
}: JobCardProps) {
  const isActive = ACTIVE_STATUSES.has(job.status);
  const phaseLabel = job.phase_label || getPhaseLabel(job.status);
  const platformMeta =
    PLATFORM_META[
      (job.platform as PlatformId) in PLATFORM_META ? (job.platform as PlatformId) : 'instagram'
    ];
  const activeEngine = job.current_engine || job.selected_engine;
  const displayEngine = formatDisplayEngine(activeEngine, 'local', job.platform);
  const speedText = formatSpeed(job.speed_bytes_per_sec);

  const items: CarouselItemState[] = (
    job.items && job.items.length > 0
      ? job.items
      : (job.files ?? []).map((f, idx) => fileToCarouselItem(f, idx, job.status))
  )
    .filter((item) => !item.fileName.toLowerCase().endsWith('.zip'))
    .slice()
    .sort((a, b) => a.index - b.index);

  const primaryItem = items[0] ?? null;
  const durationSeconds = primaryItem?.durationSeconds ?? null;
  const totalBytesSum = items.reduce((acc, item) => acc + (item.sizeBytes || 0), 0);

  const hasKnownTotal =
    !job.is_indeterminate &&
    job.total_bytes !== null &&
    job.total_bytes !== undefined &&
    job.total_bytes > 0;

  const percentValue =
    hasKnownTotal && job.percent !== null && job.percent !== undefined
      ? Math.min(100, Math.max(0, Math.round(job.percent)))
      : null;

  const readyItems = items.filter((item) => Boolean(item.mediaUri || item.filePath));
  const hasVideo = readyItems.some((item) => item.type === 'video');
  const isCarouselOrMulti = items.length > 1;
  const normalizedErrorCode = normalizeErrorCode(job.error_code);
  const errorDisplay =
    job.status === 'failed'
      ? getUserFacingErrorMessage(job.error_code, job.error_message, job.platform)
      : null;

  const handleShareItem = async (item: CarouselItemState, targetWhatsApp: boolean) => {
    await nativeBridge.shareMedia({
      uris: item.mediaUri ? [item.mediaUri] : undefined,
      filePaths: item.filePath ? [item.filePath] : undefined,
      mimeType: item.mimeType,
      title: job.title || item.fileName || 'Devwolf Downloader',
      targetWhatsApp,
    });
  };

  const handleShareAll = async (targetWhatsApp: boolean) => {
    const shareable = readyItems.filter((item) => !item.isOriginal);
    const list = shareable.length > 0 ? shareable : readyItems;
    if (list.length === 0) return;

    const uris = list.map((f) => f.mediaUri).filter((u): u is string => Boolean(u));
    const filePaths = list.map((f) => f.filePath).filter((p): p is string => Boolean(p));
    const allImages = list.every((i) => i.type === 'image');
    const allVideos = list.every((i) => i.type === 'video');

    await nativeBridge.shareMedia({
      uris: uris.length > 0 ? uris : undefined,
      filePaths: filePaths.length > 0 ? filePaths : undefined,
      mimeType:
        list.length === 1
          ? list[0].mimeType
          : allImages
          ? 'image/*'
          : allVideos
          ? 'video/*'
          : '*/*',
      title: job.title || 'Devwolf Downloader',
      targetWhatsApp,
    });
  };

  const handleOpenItem = async (item: CarouselItemState) => {
    await nativeBridge.openMedia({
      uri: item.mediaUri || undefined,
      filePath: item.filePath || undefined,
      mimeType: item.mimeType,
    });
  };

  return (
    <section aria-live="polite" className="dw-glass-card rounded-3xl p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-1.5">
          <span
            className={`inline-flex items-center rounded-lg border px-2 py-0.5 text-xs font-medium ${platformMeta.badgeClass}`}
          >
            {platformMeta.label}
          </span>
          <span className="rounded-lg border border-[#7C5CFF]/25 bg-[#7C5CFF]/10 px-2 py-0.5 text-[11px] font-medium text-[#5E43F3] dark:text-[#C4B5FD]">
            {displayEngine}
          </span>
          {job.strategy_used && (
            <span className="rounded-lg border border-[#7C5CFF]/20 bg-[#7C5CFF]/5 px-2 py-0.5 text-[11px] text-[#5B5482] dark:text-[#A5A1C8]">
              {job.strategy_used}
            </span>
          )}
          {job.status_mode && (
            <span className="rounded-lg border border-[#7C5CFF]/35 bg-[#7C5CFF]/15 px-2 py-0.5 text-[11px] font-medium text-[#5E43F3] dark:text-[#C4B5FD]">
              Modo Status
            </span>
          )}
          {isCarouselOrMulti && (
            <span className="rounded-lg border border-sky-500/30 bg-sky-500/10 px-2 py-0.5 text-[11px] font-medium text-sky-600 dark:text-sky-300">
              {items.length} itens
            </span>
          )}
        </div>

        {durationSeconds ? (
          <span className="text-xs text-[#5B5482] dark:text-[#A5A1C8]">
            {formatDuration(durationSeconds)}
          </span>
        ) : null}
      </div>

      {job.title && (
        <p className="mt-2 line-clamp-2 text-sm font-medium text-[#1E1940] dark:text-[#F5F3FF]">
          {job.title}
        </p>
      )}

      <div className="mt-3 flex items-center justify-between gap-2 text-sm">
        <div className="flex items-center gap-2">
          {isActive && (
            <Loader2
              className="h-4 w-4 animate-spin text-[#7C5CFF] dark:text-[#A78BFA]"
              aria-hidden="true"
            />
          )}
          {job.status === 'ready' && (
            <CheckCircle2
              className="h-4 w-4 text-[#7C5CFF] dark:text-[#A78BFA]"
              aria-hidden="true"
            />
          )}
          {job.status === 'failed' && (
            <AlertCircle className="h-4 w-4 text-rose-500" aria-hidden="true" />
          )}
          {job.status === 'cancelled' && (
            <XCircle className="h-4 w-4 text-[#7D75A8]" aria-hidden="true" />
          )}
          <span className="font-medium text-[#1E1940] dark:text-[#F5F3FF]">
            {phaseLabel}
            {isActive ? ` • ${displayEngine}…` : ''}
          </span>
        </div>
      </div>

      {engineSwitchNotice && (
        <div className="mt-2 rounded-xl border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-xs text-amber-700 dark:text-amber-300">
          {engineSwitchNotice}
        </div>
      )}

      {isActive && (
        <div className="mt-3">
          <div className="h-2.5 w-full overflow-hidden rounded-full bg-[#7C5CFF]/15">
            {percentValue !== null ? (
              <div
                className="h-full rounded-full bg-gradient-to-r from-[#7C5CFF] to-[#A78BFA] transition-all duration-300"
                style={{ width: `${percentValue}%` }}
              />
            ) : (
              <div className="animate-indeterminate h-full rounded-full bg-gradient-to-r from-[#7C5CFF] to-[#A78BFA]" />
            )}
          </div>
          <div className="mt-1.5 flex items-center justify-between text-xs text-[#5B5482] dark:text-[#A5A1C8]">
            <span>
              {percentValue !== null ? `${percentValue}%` : 'Tamanho total desconhecido'}
              {speedText ? ` • ${speedText}` : ''}
            </span>
            <span>
              {job.bytes_downloaded > 0 ? formatBytes(job.bytes_downloaded) : ''}
              {hasKnownTotal ? ` / ${formatBytes(job.total_bytes)}` : ''}
            </span>
          </div>
        </div>
      )}

      {errorDisplay && (
        <div
          role="alert"
          className="mt-3 space-y-2 rounded-2xl border border-rose-500/30 bg-rose-500/10 px-3 py-2.5 text-xs text-rose-700 dark:text-rose-200"
        >
          <div className="flex items-start justify-between gap-2">
            <span>{errorDisplay}</span>
            {job.error_code && (
              <span className="shrink-0 rounded bg-rose-500/15 px-1.5 py-0.5 font-mono text-[10px]">
                {job.error_code}
              </span>
            )}
          </div>
          {normalizedErrorCode === 'AUTH_REQUIRED' && (onOpenAccountSettings || onOpenSettings) && (
            <button
              type="button"
              onClick={() => {
                if (onOpenAccountSettings) {
                  onOpenAccountSettings(job.platform);
                } else {
                  onOpenSettings?.();
                }
              }}
              className="inline-flex min-h-[38px] items-center gap-1.5 rounded-xl bg-rose-500/20 px-3 text-xs font-semibold text-rose-800 transition hover:bg-rose-500/30 dark:text-rose-100"
            >
              <KeyRound className="h-3.5 w-3.5" aria-hidden="true" />
              <span>{getAuthActionLabel(job.platform)}</span>
            </button>
          )}
        </div>
      )}

      {isCarouselOrMulti && items.length > 0 && (
        <ul className="mt-3 space-y-2" aria-label="Itens do carrossel">
          {items.map((item) => {
            const label = getItemLabel(item, items.length);
            const resolution = formatResolution(item.width, item.height);
            const sizeText = formatBytes(item.sizeBytes);
            const canAct = Boolean(item.mediaUri || item.filePath) && item.status === 'ready';
            const ItemIcon =
              item.type === 'image' ? ImageIcon : item.type === 'audio' ? Music : Film;

            return (
              <li key={item.id} className="dw-glass-subtle rounded-2xl px-3 py-2.5 text-xs">
                <div className="flex items-center justify-between gap-2">
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <span className="inline-flex items-center gap-1 rounded-md bg-[#7C5CFF]/15 px-2 py-0.5 text-[11px] font-semibold text-[#5E43F3] dark:text-[#C4B5FD]">
                        <ItemIcon className="h-3 w-3" aria-hidden="true" />
                        <span>{label}</span>
                      </span>
                      {resolution && (
                        <span className="rounded-md bg-[#7C5CFF]/10 px-1.5 py-0.5 font-mono text-[10px] text-[#4C3F91] dark:text-[#C4B5FD]">
                          {resolution}
                        </span>
                      )}
                      <span className="text-[11px] font-medium text-[#5B5482] dark:text-[#A5A1C8]">
                        {getItemStatusLabel(item.status, item.percent)}
                      </span>
                    </div>
                    <p className="mt-1 truncate text-[11px] text-[#5B5482] dark:text-[#A5A1C8]">
                      {item.fileName}
                      {sizeText ? ` • ${sizeText}` : ''}
                      {item.durationSeconds ? ` • ${formatDuration(item.durationSeconds)}` : ''}
                    </p>
                  </div>

                  {canAct && (
                    <div className="flex shrink-0 items-center gap-1.5">
                      <button
                        type="button"
                        onClick={() => void handleOpenItem(item)}
                        className="min-h-[40px] rounded-xl border border-[#7C5CFF]/25 px-2.5 text-xs font-medium text-[#4C3F91] transition hover:bg-[#7C5CFF]/10 dark:text-[#E0DBFF]"
                      >
                        Abrir
                      </button>
                      <button
                        type="button"
                        onClick={() => void handleShareItem(item, false)}
                        className="min-h-[40px] rounded-xl bg-[#7C5CFF]/20 px-2.5 text-xs font-semibold text-[#5E43F3] transition hover:bg-[#7C5CFF]/30 dark:text-[#C4B5FD]"
                      >
                        Compartilhar
                      </button>
                    </div>
                  )}
                </div>

                {item.status === 'downloading' && item.percent !== null && (
                  <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-[#7C5CFF]/15">
                    <div
                      className="h-full rounded-full bg-gradient-to-r from-[#7C5CFF] to-[#A78BFA] transition-all"
                      style={{
                        width: `${Math.min(100, Math.max(0, Math.round(item.percent)))}%`,
                      }}
                    />
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}

      {job.status === 'ready' && readyItems.length > 0 && (
        <div className="mt-3 space-y-2.5">
          <p className="text-xs text-[#5B5482] dark:text-[#A5A1C8]">
            Salvo na Galeria (Devwolf Downloader
            {totalBytesSum > 0 ? ` • ${formatBytes(totalBytesSum)}` : ''} • {displayEngine}).
          </p>

          <div className="flex flex-wrap gap-2">
            {hasVideo && (
              <button
                type="button"
                onClick={() => void handleShareAll(true)}
                className="dw-btn-primary inline-flex min-h-[48px] flex-1 items-center justify-center gap-2 rounded-2xl px-4 text-sm font-semibold transition"
              >
                <MessageCircle className="h-4 w-4" aria-hidden="true" />
                <span>Compartilhar no Status / WhatsApp</span>
              </button>
            )}

            {isCarouselOrMulti ? (
              <button
                type="button"
                onClick={() => void handleShareAll(false)}
                className={`inline-flex min-h-[48px] items-center justify-center gap-2 rounded-2xl px-4 text-sm font-semibold transition ${
                  hasVideo
                    ? 'border border-[#7C5CFF]/25 bg-[#7C5CFF]/10 text-[#4C3F91] hover:bg-[#7C5CFF]/20 dark:text-[#E0DBFF]'
                    : 'dw-btn-primary flex-1'
                }`}
              >
                <Share2 className="h-4 w-4" aria-hidden="true" />
                <span>Compartilhar todos</span>
              </button>
            ) : (
              primaryItem && (
                <>
                  <button
                    type="button"
                    onClick={() => void handleOpenItem(primaryItem)}
                    className="inline-flex min-h-[48px] items-center justify-center gap-2 rounded-2xl border border-[#7C5CFF]/25 bg-[#7C5CFF]/10 px-4 text-sm font-medium text-[#4C3F91] transition hover:bg-[#7C5CFF]/20 dark:text-[#E0DBFF]"
                  >
                    <ExternalLink className="h-4 w-4" aria-hidden="true" />
                    <span>Abrir na Galeria</span>
                  </button>
                  <button
                    type="button"
                    onClick={() => void handleShareItem(primaryItem, false)}
                    className="inline-flex min-h-[44px] items-center justify-center gap-2 rounded-2xl border border-[#7C5CFF]/25 bg-[#7C5CFF]/10 px-4 text-sm font-medium text-[#4C3F91] transition hover:bg-[#7C5CFF]/20 dark:text-[#E0DBFF]"
                  >
                    <Share2 className="h-4 w-4" aria-hidden="true" />
                    <span>Compartilhar</span>
                  </button>
                </>
              )
            )}
          </div>

          {isNativeAndroid && job.from_share_intent && onReturnToSourceApp && (
            <button
              type="button"
              onClick={() => void onReturnToSourceApp()}
              className="inline-flex min-h-[44px] w-full items-center justify-center gap-1.5 rounded-2xl border border-[#7C5CFF]/25 bg-[#7C5CFF]/10 px-4 text-xs font-medium text-[#4C3F91] transition hover:bg-[#7C5CFF]/20 dark:text-[#C4B5FD]"
            >
              <ArrowLeft className="h-3.5 w-3.5" aria-hidden="true" />
              <span>Voltar ao app de origem</span>
            </button>
          )}
        </div>
      )}

      <div className="mt-3 flex flex-wrap gap-2">
        {isActive && (
          <button
            type="button"
            onClick={() => onCancel(job.id)}
            className="inline-flex min-h-[44px] items-center justify-center gap-1.5 rounded-2xl border border-[#7C5CFF]/25 px-4 text-xs font-medium text-[#4C3F91] transition hover:border-rose-500/40 hover:bg-rose-500/10 hover:text-rose-500 dark:text-[#C4B5FD] dark:hover:text-rose-300"
          >
            <XCircle className="h-4 w-4" aria-hidden="true" />
            <span>Cancelar</span>
          </button>
        )}

        {(job.status === 'failed' || job.status === 'cancelled' || job.status === 'expired') && (
          <button
            type="button"
            onClick={() => onRetry(job.normalized_url || job.original_url)}
            className="dw-btn-primary inline-flex min-h-[44px] items-center justify-center gap-1.5 rounded-2xl px-4 text-xs font-semibold transition"
          >
            <RefreshCw className="h-4 w-4" aria-hidden="true" />
            <span>Tentar novamente</span>
          </button>
        )}
      </div>
    </section>
  );
}
