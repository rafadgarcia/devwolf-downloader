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
  connectionMode: 'local' | 'idle';
  isNativeAndroid: boolean;
  onCancel: (jobId: string) => void;
  onRetry: (url: string) => void;
  onReturnToSourceApp?: () => Promise<void>;
  onOpenSettings?: () => void;
}

const ACTIVE_STATUSES = new Set(['queued', 'resolving', 'downloading', 'processing']);

function fileToCarouselItem(file: JobFile, idx: number, jobStatus: JobResponse['status']): CarouselItemState {
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

export function JobCard({
  job,
  engineSwitchNotice,
  connectionMode,
  isNativeAndroid,
  onCancel,
  onRetry,
  onReturnToSourceApp,
  onOpenSettings,
}: JobCardProps) {
  const isActive = ACTIVE_STATUSES.has(job.status);
  const phaseLabel = job.phase_label || getPhaseLabel(job.status);
  const platformMeta =
    PLATFORM_META[(job.platform as PlatformId) in PLATFORM_META ? (job.platform as PlatformId) : 'instagram'];
  const activeEngine = job.current_engine || job.selected_engine;
  const displayEngine = job.display_engine || formatDisplayEngine(activeEngine, 'local');
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
      ? getUserFacingErrorMessage(job.error_code, job.error_message)
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
      mimeType: list.length === 1 ? list[0].mimeType : allImages ? 'image/*' : allVideos ? 'video/*' : '*/*',
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
    <section
      aria-live="polite"
      className="rounded-2xl border border-zinc-200 bg-white p-4 shadow-sm dark:border-zinc-800 dark:bg-[#1C1C20]"
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-1.5">
          <span
            className={`inline-flex items-center rounded-lg border px-2 py-0.5 text-xs font-medium ${platformMeta.badgeClass}`}
          >
            {platformMeta.label}
          </span>
          <span className="rounded-lg border border-zinc-200 bg-zinc-50 px-2 py-0.5 text-[11px] font-medium text-zinc-700 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-300">
            {displayEngine}
          </span>
          {job.strategy_used && (
            <span className="rounded-lg border border-zinc-200 bg-zinc-50 px-2 py-0.5 text-[11px] text-zinc-500 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-400">
              {job.strategy_used}
            </span>
          )}
          {job.status_mode && (
            <span className="rounded-lg border border-[#A3E635]/30 bg-[#A3E635]/10 px-2 py-0.5 text-[11px] font-medium text-[#4d7c0f] dark:text-[#A3E635]">
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
          <span className="text-xs text-zinc-500 dark:text-zinc-400">
            {formatDuration(durationSeconds)}
          </span>
        ) : null}
      </div>

      {job.title && (
        <p className="mt-2 line-clamp-2 text-sm font-medium text-zinc-900 dark:text-zinc-100">
          {job.title}
        </p>
      )}

      <div className="mt-3 flex items-center justify-between gap-2 text-sm">
        <div className="flex items-center gap-2">
          {isActive && (
            <Loader2
              className="h-4 w-4 animate-spin text-[#65a30d] dark:text-[#A3E635]"
              aria-hidden="true"
            />
          )}
          {job.status === 'ready' && (
            <CheckCircle2
              className="h-4 w-4 text-[#65a30d] dark:text-[#A3E635]"
              aria-hidden="true"
            />
          )}
          {job.status === 'failed' && (
            <AlertCircle className="h-4 w-4 text-red-500" aria-hidden="true" />
          )}
          {job.status === 'cancelled' && (
            <XCircle className="h-4 w-4 text-zinc-400" aria-hidden="true" />
          )}
          <span className="font-medium text-zinc-900 dark:text-zinc-100">
            {phaseLabel}
            {isActive ? ` • ${displayEngine}…` : ''}
          </span>
        </div>

        {isActive && connectionMode !== 'idle' && (
          <span className="text-[11px] uppercase tracking-wider text-zinc-400">Local</span>
        )}
      </div>

      {engineSwitchNotice && (
        <div className="mt-2 rounded-xl border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-xs text-amber-700 dark:text-amber-300">
          {engineSwitchNotice}
        </div>
      )}

      {isActive && (
        <div className="mt-3">
          <div className="h-2.5 w-full overflow-hidden rounded-full bg-zinc-200 dark:bg-zinc-800">
            {percentValue !== null ? (
              <div
                className="h-full rounded-full bg-[#A3E635] transition-all duration-300"
                style={{ width: `${percentValue}%` }}
              />
            ) : (
              <div className="animate-indeterminate h-full rounded-full bg-[#A3E635]" />
            )}
          </div>
          <div className="mt-1.5 flex items-center justify-between text-xs text-zinc-500 dark:text-zinc-400">
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
          className="mt-3 space-y-2 rounded-xl border border-red-500/30 bg-red-500/10 px-3 py-2.5 text-xs text-red-600 dark:text-red-300"
        >
          <div className="flex items-start justify-between gap-2">
            <span>{errorDisplay}</span>
            {job.error_code && (
              <span className="shrink-0 rounded bg-red-500/15 px-1.5 py-0.5 font-mono text-[10px]">
                {job.error_code}
              </span>
            )}
          </div>
          {normalizedErrorCode === 'AUTH_REQUIRED' && onOpenSettings && (
            <button
              type="button"
              onClick={onOpenSettings}
              className="inline-flex min-h-[36px] items-center gap-1.5 rounded-lg bg-red-500/20 px-2.5 text-xs font-semibold text-red-700 transition hover:bg-red-500/30 dark:text-red-200"
            >
              <KeyRound className="h-3.5 w-3.5" aria-hidden="true" />
              <span>Conectar sessão opcional nos Ajustes</span>
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
            const ItemIcon = item.type === 'image' ? ImageIcon : item.type === 'audio' ? Music : Film;

            return (
              <li
                key={item.id}
                className="rounded-xl border border-zinc-200 bg-zinc-50 px-3 py-2.5 text-xs dark:border-zinc-800 dark:bg-[#101012]"
              >
                <div className="flex items-center justify-between gap-2">
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <span className="inline-flex items-center gap-1 rounded-md bg-[#A3E635]/15 px-2 py-0.5 text-[11px] font-semibold text-[#4d7c0f] dark:text-[#A3E635]">
                        <ItemIcon className="h-3 w-3" aria-hidden="true" />
                        <span>{label}</span>
                      </span>
                      {resolution && (
                        <span className="rounded-md bg-zinc-200/80 px-1.5 py-0.5 font-mono text-[10px] text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300">
                          {resolution}
                        </span>
                      )}
                      <span className="text-[11px] font-medium text-zinc-500 dark:text-zinc-400">
                        {getItemStatusLabel(item.status, item.percent)}
                      </span>
                    </div>
                    <p className="mt-1 truncate text-[11px] text-zinc-500 dark:text-zinc-400">
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
                        className="min-h-[40px] rounded-lg border border-zinc-300 bg-white px-2.5 text-xs font-medium text-zinc-700 transition hover:bg-zinc-100 dark:border-zinc-700 dark:bg-[#1C1C20] dark:text-zinc-200 dark:hover:bg-zinc-800"
                      >
                        Abrir
                      </button>
                      <button
                        type="button"
                        onClick={() => void handleShareItem(item, false)}
                        className="min-h-[40px] rounded-lg bg-[#A3E635]/20 px-2.5 text-xs font-semibold text-[#4d7c0f] transition hover:bg-[#A3E635]/30 dark:text-[#A3E635]"
                      >
                        Compartilhar
                      </button>
                    </div>
                  )}
                </div>

                {item.status === 'downloading' && item.percent !== null && (
                  <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-zinc-200 dark:bg-zinc-800">
                    <div
                      className="h-full rounded-full bg-[#A3E635] transition-all"
                      style={{ width: `${Math.min(100, Math.max(0, Math.round(item.percent)))}%` }}
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
          <p className="text-xs text-zinc-500 dark:text-zinc-400">
            Salvo na Galeria (Devwolf Downloader
            {totalBytesSum > 0 ? ` • ${formatBytes(totalBytesSum)}` : ''} • {displayEngine}).
          </p>

          <div className="flex flex-wrap gap-2">
            {hasVideo && (
              <button
                type="button"
                onClick={() => void handleShareAll(true)}
                className="inline-flex min-h-[48px] flex-1 items-center justify-center gap-2 rounded-xl bg-[#A3E635] px-4 text-sm font-semibold text-zinc-950 transition hover:bg-[#84CC16]"
              >
                <MessageCircle className="h-4 w-4" aria-hidden="true" />
                <span>Compartilhar no Status / WhatsApp</span>
              </button>
            )}

            {isCarouselOrMulti ? (
              <button
                type="button"
                onClick={() => void handleShareAll(false)}
                className={`inline-flex min-h-[48px] items-center justify-center gap-2 rounded-xl px-4 text-sm font-semibold transition ${
                  hasVideo
                    ? 'border border-zinc-300 bg-zinc-100 text-zinc-800 hover:bg-zinc-200 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200 dark:hover:bg-zinc-800'
                    : 'flex-1 bg-[#A3E635] text-zinc-950 hover:bg-[#84CC16]'
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
                    className="inline-flex min-h-[48px] items-center justify-center gap-2 rounded-xl border border-zinc-300 bg-zinc-100 px-4 text-sm font-medium text-zinc-800 transition hover:bg-zinc-200 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200 dark:hover:bg-zinc-800"
                  >
                    <ExternalLink className="h-4 w-4" aria-hidden="true" />
                    <span>Abrir na Galeria</span>
                  </button>
                  <button
                    type="button"
                    onClick={() => void handleShareItem(primaryItem, false)}
                    className="inline-flex min-h-[44px] items-center justify-center gap-2 rounded-xl border border-zinc-300 bg-zinc-100 px-4 text-sm font-medium text-zinc-800 transition hover:bg-zinc-200 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200 dark:hover:bg-zinc-800"
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
              className="inline-flex min-h-[44px] w-full items-center justify-center gap-1.5 rounded-xl border border-zinc-300 bg-zinc-50 px-4 text-xs font-medium text-zinc-700 transition hover:bg-zinc-200 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-300 dark:hover:bg-zinc-800"
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
            className="inline-flex min-h-[44px] items-center justify-center gap-1.5 rounded-xl border border-zinc-300 px-4 text-xs font-medium text-zinc-700 transition hover:border-red-500/40 hover:bg-red-500/10 hover:text-red-500 dark:border-zinc-700 dark:text-zinc-300 dark:hover:text-red-400"
          >
            <XCircle className="h-4 w-4" aria-hidden="true" />
            <span>Cancelar</span>
          </button>
        )}

        {(job.status === 'failed' || job.status === 'cancelled' || job.status === 'expired') && (
          <button
            type="button"
            onClick={() => onRetry(job.normalized_url || job.original_url)}
            className="inline-flex min-h-[44px] items-center justify-center gap-1.5 rounded-xl bg-[#A3E635] px-4 text-xs font-semibold text-zinc-950 transition hover:bg-[#84CC16]"
          >
            <RefreshCw className="h-4 w-4" aria-hidden="true" />
            <span>Tentar novamente</span>
          </button>
        )}
      </div>
    </section>
  );
}
