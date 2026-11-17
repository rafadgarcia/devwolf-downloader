import {
  AlertCircle,
  CheckCircle2,
  Download,
  Loader2,
  RefreshCw,
  Share2,
  XCircle,
} from 'lucide-react';
import { getJobFileDownloadUrl } from '../services/api';
import { triggerBrowserDownload } from '../hooks/useJobProgress';
import type { JobResponse, PlatformId } from '../types/api';
import {
  formatBytes,
  formatDuration,
  getPhaseLabel,
  PLATFORM_META,
} from '../utils/format';

interface JobCardProps {
  job: JobResponse;
  engineSwitchNotice: string | null;
  connectionMode: 'sse' | 'polling' | 'idle';
  onCancel: (jobId: string) => void;
  onRetry: (url: string) => void;
}

const ACTIVE_STATUSES = new Set(['queued', 'resolving', 'downloading', 'processing']);

export function JobCard({
  job,
  engineSwitchNotice,
  connectionMode,
  onCancel,
  onRetry,
}: JobCardProps) {
  const isActive = ACTIVE_STATUSES.has(job.status);
  const phaseLabel = job.phase_label || getPhaseLabel(job.status);
  const platformMeta =
    PLATFORM_META[(job.platform as PlatformId) in PLATFORM_META ? (job.platform as PlatformId) : 'instagram'];
  const activeEngine = job.current_engine || job.selected_engine;
  const primaryFile = job.files?.[0];
  const durationSeconds = primaryFile?.duration_seconds;

  const hasKnownTotal =
    !job.is_indeterminate &&
    job.total_bytes !== null &&
    job.total_bytes !== undefined &&
    job.total_bytes > 0;

  const percentValue =
    hasKnownTotal && job.percent !== null && job.percent !== undefined
      ? Math.min(100, Math.max(0, Math.round(job.percent)))
      : null;

  const handleNativeShare = async () => {
    if (!primaryFile || typeof navigator === 'undefined' || typeof navigator.share !== 'function') {
      return;
    }
    const url = `${window.location.origin}${getJobFileDownloadUrl(job.id, primaryFile.id)}`;
    try {
      await navigator.share({
        title: job.title || primaryFile.filename || 'DropLink',
        url,
      });
    } catch {
      // Usuário cancelou compartilhamento
    }
  };

  return (
    <section
      aria-live="polite"
      className="rounded-2xl border border-zinc-200 bg-white p-4 shadow-sm dark:border-zinc-800 dark:bg-[#1C1C20]"
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <span
            className={`inline-flex items-center rounded-lg border px-2 py-0.5 text-xs font-medium ${platformMeta.badgeClass}`}
          >
            {platformMeta.label}
          </span>
          {job.format && job.format !== 'auto' && (
            <span className="text-xs font-medium uppercase tracking-wide text-zinc-500 dark:text-zinc-400">
              {job.format}
            </span>
          )}
          {primaryFile?.is_archive && (
            <span className="rounded-md bg-zinc-200 px-1.5 py-0.5 text-[11px] font-semibold text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300">
              ZIP{primaryFile.item_count ? ` (${primaryFile.item_count} itens)` : ''}
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
            {isActive && activeEngine ? ` via ${activeEngine}…` : ''}
          </span>
        </div>

        {isActive && connectionMode !== 'idle' && (
          <span className="text-[11px] uppercase tracking-wider text-zinc-400">
            {connectionMode === 'sse' ? 'SSE' : 'Sync'}
          </span>
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
            </span>
            <span>
              {job.bytes_downloaded > 0 ? formatBytes(job.bytes_downloaded) : ''}
              {hasKnownTotal ? ` / ${formatBytes(job.total_bytes)}` : ''}
            </span>
          </div>
        </div>
      )}

      {job.status === 'failed' && job.error_message && (
        <div
          role="alert"
          className="mt-3 rounded-xl border border-red-500/30 bg-red-500/10 px-3 py-2.5 text-xs text-red-600 dark:text-red-300"
        >
          {job.error_message}
        </div>
      )}

      {job.status === 'ready' && job.files?.length > 0 && (
        <div className="mt-3 space-y-2.5">
          <p className="text-xs text-zinc-500 dark:text-zinc-400">
            Arquivo pronto no servidor ({formatBytes(primaryFile.size_bytes)}
            {job.selected_engine ? ` • ${job.selected_engine}` : ''}). Toque abaixo se o download
            automático não iniciar no navegador.
          </p>

          {primaryFile.missing_items ? (
            <p className="text-xs text-amber-600 dark:text-amber-300">
              Aviso: {primaryFile.missing_items} item(ns) do carrossel não puderam ser baixados.
            </p>
          ) : null}

          <div className="flex flex-wrap gap-2">
            {job.files.map((file) => (
              <button
                key={file.id}
                type="button"
                onClick={() => triggerBrowserDownload(job.id, file.id, file.filename)}
                className="inline-flex min-h-[44px] flex-1 items-center justify-center gap-2 rounded-xl bg-[#A3E635] px-4 text-sm font-semibold text-zinc-950 transition hover:bg-[#84CC16]"
              >
                <Download className="h-4 w-4" aria-hidden="true" />
                <span>
                  {job.files.length > 1 ? `Baixar ${file.filename}` : 'Baixar arquivo'}
                </span>
              </button>
            ))}

            {typeof navigator !== 'undefined' && typeof navigator.share === 'function' && (
              <button
                type="button"
                onClick={() => void handleNativeShare()}
                className="inline-flex min-h-[44px] items-center justify-center gap-2 rounded-xl border border-zinc-300 bg-zinc-100 px-4 text-sm font-medium text-zinc-800 transition hover:bg-zinc-200 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200 dark:hover:bg-zinc-800"
              >
                <Share2 className="h-4 w-4" aria-hidden="true" />
                <span>Compartilhar</span>
              </button>
            )}
          </div>
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
