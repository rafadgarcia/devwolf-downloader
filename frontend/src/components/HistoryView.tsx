import { useCallback, useEffect, useState } from 'react';
import {
  Copy,
  Download,
  Edit3,
  ExternalLink,
  MessageCircle,
  RefreshCw,
  Search,
  Trash2,
} from 'lucide-react';
import { triggerBrowserDownload } from '../hooks/useJobProgress';
import { api } from '../services/api';
import { mergeUnifiedHistory, removeLocalJob } from '../services/localQueue';
import { nativeBridge } from '../services/nativeBridge';
import type { HistoryItem, JobResponse, PlatformId } from '../types/api';
import {
  formatBytes,
  formatDateTime,
  formatDisplayEngine,
  getPhaseLabel,
  PLATFORM_META,
} from '../utils/format';

interface HistoryViewProps {
  localJobs: JobResponse[];
  vpsAuthenticated: boolean;
  onRepeatDownload: (url: string) => Promise<void>;
  onEditUrl: (url: string) => void;
  onLocalJobDeleted?: (jobId: string) => void;
}

const PLATFORM_FILTERS: { id: string; label: string }[] = [
  { id: 'all', label: 'Todas' },
  { id: 'instagram', label: 'Instagram' },
  { id: 'tiktok', label: 'TikTok' },
  { id: 'youtube', label: 'YouTube' },
  { id: 'twitter', label: 'X / Twitter' },
  { id: 'facebook', label: 'Facebook' },
  { id: 'reddit', label: 'Reddit' },
  { id: 'pinterest', label: 'Pinterest' },
];

export function HistoryView({
  localJobs,
  vpsAuthenticated,
  onRepeatDownload,
  onEditUrl,
  onLocalJobDeleted,
}: HistoryViewProps) {
  const [items, setItems] = useState<HistoryItem[]>([]);
  const [query, setQuery] = useState('');
  const [platform, setPlatform] = useState('all');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const loadHistory = useCallback(async () => {
    setLoading(true);
    setError(null);
    let vpsItems: HistoryItem[] = [];

    if (vpsAuthenticated) {
      try {
        const res = await api.getHistory({
          q: query.trim() || undefined,
          platform,
          page: 1,
          page_size: 40,
        });
        vpsItems = res.items ?? [];
      } catch (err) {
        if (localJobs.length === 0) {
          setError(err instanceof Error ? err.message : 'Erro ao carregar histórico da VPS.');
        }
      }
    }

    const merged = mergeUnifiedHistory(localJobs, vpsItems, {
      q: query.trim() || undefined,
      platform,
    });
    setItems(merged);
    setLoading(false);
  }, [localJobs, platform, query, vpsAuthenticated]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void loadHistory();
    }, 180);
    return () => window.clearTimeout(timer);
  }, [loadHistory]);

  const handleCopy = async (item: HistoryItem) => {
    try {
      await navigator.clipboard.writeText(item.normalized_url || item.original_url);
      setCopiedId(item.id);
      window.setTimeout(() => setCopiedId((prev) => (prev === item.id ? null : prev)), 1800);
    } catch {
      // Fallback caso clipboard write não esteja disponível
    }
  };

  const handleDelete = async (item: HistoryItem) => {
    try {
      if (item.execution_origin === 'local') {
        if (item.file?.media_uri || item.file?.file_path) {
          await nativeBridge
            .deleteMedia({
              uri: item.file.media_uri || undefined,
              filePath: item.file.file_path || undefined,
            })
            .catch(() => false);
        }
        removeLocalJob(item.id);
        onLocalJobDeleted?.(item.id);
      } else if (vpsAuthenticated) {
        await api.deleteHistory(item.id);
      }
      setItems((prev) => prev.filter((entry) => entry.id !== item.id));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Não foi possível remover o item.');
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-base font-semibold text-zinc-900 dark:text-zinc-100">Histórico</h2>
        <button
          type="button"
          onClick={() => void loadHistory()}
          className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl px-3 text-xs font-medium text-zinc-600 transition hover:bg-zinc-200/70 dark:text-zinc-300 dark:hover:bg-[#1C1C20]"
        >
          <RefreshCw className="h-4 w-4" aria-hidden="true" />
          <span>Atualizar</span>
        </button>
      </div>

      <div className="relative">
        <Search
          className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-zinc-400"
          aria-hidden="true"
        />
        <input
          type="search"
          aria-label="Buscar no histórico"
          placeholder="Buscar por título ou URL…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          className="min-h-[44px] w-full rounded-xl border border-zinc-300 bg-white pl-10 pr-3.5 text-sm text-zinc-900 placeholder-zinc-400 outline-none focus:border-[#A3E635] dark:border-zinc-800 dark:bg-[#1C1C20] dark:text-zinc-100"
        />
      </div>

      <div className="flex gap-1.5 overflow-x-auto pb-1">
        {PLATFORM_FILTERS.map((f) => {
          const active = platform === f.id;
          return (
            <button
              key={f.id}
              type="button"
              onClick={() => setPlatform(f.id)}
              className={`min-h-[44px] shrink-0 rounded-xl px-3 text-xs font-medium transition ${
                active
                  ? 'bg-[#A3E635] text-zinc-950'
                  : 'border border-zinc-200 bg-white text-zinc-600 hover:bg-zinc-100 dark:border-zinc-800 dark:bg-[#1C1C20] dark:text-zinc-300 dark:hover:bg-zinc-800'
              }`}
            >
              {f.label}
            </button>
          );
        })}
      </div>

      {error && (
        <div
          role="alert"
          className="rounded-xl border border-red-500/30 bg-red-500/10 p-3 text-xs text-red-600 dark:text-red-300"
        >
          {error}
        </div>
      )}

      {loading ? (
        <div className="rounded-2xl border border-zinc-200 bg-white p-6 text-center text-xs text-zinc-500 dark:border-zinc-800 dark:bg-[#1C1C20] dark:text-zinc-400">
          Carregando histórico…
        </div>
      ) : items.length === 0 ? (
        <div className="rounded-2xl border border-zinc-200 bg-white p-6 text-center text-sm text-zinc-500 dark:border-zinc-800 dark:bg-[#1C1C20] dark:text-zinc-400">
          Nenhum download encontrado.
        </div>
      ) : (
        <ul className="space-y-3">
          {items.map((item) => {
            const meta =
              PLATFORM_META[
                (item.platform as PlatformId) in PLATFORM_META
                  ? (item.platform as PlatformId)
                  : 'instagram'
              ];
            const isExpired = item.status === 'expired' || item.file_expired;
            const activeFile = item.file;
            const hasLocalDeviceFile = Boolean(activeFile?.media_uri || activeFile?.file_path);
            const canDownloadRemote =
              item.status === 'ready' &&
              item.has_active_file &&
              !isExpired &&
              Boolean(activeFile) &&
              !hasLocalDeviceFile;
            const engineBadge =
              item.display_engine ||
              formatDisplayEngine(item.selected_engine, item.execution_origin);

            return (
              <li
                key={item.id}
                className="rounded-2xl border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-[#1C1C20]"
              >
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="flex items-center gap-2">
                    <span
                      className={`inline-flex items-center rounded-md border px-2 py-0.5 text-[11px] font-medium ${meta.badgeClass}`}
                    >
                      {meta.label}
                    </span>
                    <span className="text-xs text-zinc-500 dark:text-zinc-400">
                      {formatDateTime(item.created_at)}
                    </span>
                  </div>

                  <div className="flex flex-wrap items-center gap-1.5">
                    <span className="rounded-md bg-zinc-100 px-2 py-0.5 text-[11px] text-zinc-600 dark:bg-[#101012] dark:text-zinc-300">
                      {engineBadge}
                    </span>
                    <span
                      className={`text-xs font-medium ${
                        item.status === 'ready' && !isExpired
                          ? 'text-[#4d7c0f] dark:text-[#A3E635]'
                          : item.status === 'failed'
                          ? 'text-red-500 dark:text-red-400'
                          : 'text-zinc-500 dark:text-zinc-400'
                      }`}
                    >
                      {isExpired ? 'Expirado' : item.phase_label || getPhaseLabel(item.status)}
                    </span>
                  </div>
                </div>

                <p className="mt-2 line-clamp-2 text-sm font-medium text-zinc-900 dark:text-zinc-100">
                  {item.title || item.normalized_url || item.original_url}
                </p>

                <p className="mt-0.5 truncate text-xs text-zinc-500 dark:text-zinc-400">
                  {item.normalized_url || item.original_url}
                </p>

                {isExpired && (
                  <p className="mt-2 text-xs text-amber-600 dark:text-amber-300">
                    Arquivo expirado no servidor. Você pode reprocessar o link novamente.
                  </p>
                )}

                {item.status === 'failed' && item.error_message && (
                  <p className="mt-1.5 text-xs text-red-500 dark:text-red-400">
                    {item.error_message}
                  </p>
                )}

                <div className="mt-3 flex flex-wrap gap-1.5">
                  {hasLocalDeviceFile && activeFile && (
                    <>
                      <button
                        type="button"
                        onClick={() =>
                          void nativeBridge.shareMedia({
                            uris: activeFile.media_uri ? [activeFile.media_uri] : undefined,
                            filePaths: activeFile.file_path ? [activeFile.file_path] : undefined,
                            mimeType: activeFile.mime_type,
                            title: item.title || activeFile.filename,
                            targetWhatsApp: true,
                          })
                        }
                        className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl bg-[#A3E635] px-3 text-xs font-semibold text-zinc-950 transition hover:bg-[#84CC16]"
                      >
                        <MessageCircle className="h-3.5 w-3.5" aria-hidden="true" />
                        <span>Status / WhatsApp</span>
                      </button>

                      <button
                        type="button"
                        onClick={() =>
                          void nativeBridge.openMedia({
                            uri: activeFile.media_uri || undefined,
                            filePath: activeFile.file_path || undefined,
                            mimeType: activeFile.mime_type,
                          })
                        }
                        className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl border border-zinc-300 bg-zinc-50 px-3 text-xs font-medium text-zinc-800 transition hover:bg-zinc-200 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200 dark:hover:bg-zinc-800"
                      >
                        <ExternalLink className="h-3.5 w-3.5" aria-hidden="true" />
                        <span>
                          Abrir
                          {activeFile.size_bytes ? ` (${formatBytes(activeFile.size_bytes)})` : ''}
                        </span>
                      </button>
                    </>
                  )}

                  {canDownloadRemote && activeFile && (
                    <button
                      type="button"
                      onClick={() =>
                        triggerBrowserDownload(item.id, activeFile.id, activeFile.filename)
                      }
                      className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl bg-[#A3E635] px-3 text-xs font-semibold text-zinc-950 transition hover:bg-[#84CC16]"
                    >
                      <Download className="h-3.5 w-3.5" aria-hidden="true" />
                      <span>
                        Baixar novamente
                        {activeFile.size_bytes ? ` (${formatBytes(activeFile.size_bytes)})` : ''}
                      </span>
                    </button>
                  )}

                  <button
                    type="button"
                    onClick={() => void onRepeatDownload(item.normalized_url || item.original_url)}
                    className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl border border-zinc-300 bg-zinc-50 px-3 text-xs font-medium text-zinc-800 transition hover:bg-zinc-200 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200 dark:hover:bg-zinc-800"
                  >
                    <RefreshCw className="h-3.5 w-3.5" aria-hidden="true" />
                    <span>{isExpired ? 'Reprocessar' : 'Repetir'}</span>
                  </button>

                  <button
                    type="button"
                    onClick={() => void handleCopy(item)}
                    className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl border border-zinc-300 bg-zinc-50 px-3 text-xs font-medium text-zinc-800 transition hover:bg-zinc-200 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200 dark:hover:bg-zinc-800"
                  >
                    <Copy className="h-3.5 w-3.5" aria-hidden="true" />
                    <span>{copiedId === item.id ? 'Copiado!' : 'Copiar URL'}</span>
                  </button>

                  <button
                    type="button"
                    onClick={() => onEditUrl(item.normalized_url || item.original_url)}
                    className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl border border-zinc-300 bg-zinc-50 px-3 text-xs font-medium text-zinc-800 transition hover:bg-zinc-200 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200 dark:hover:bg-zinc-800"
                  >
                    <Edit3 className="h-3.5 w-3.5" aria-hidden="true" />
                    <span>Editar URL</span>
                  </button>

                  <button
                    type="button"
                    onClick={() => void handleDelete(item)}
                    aria-label="Remover do histórico"
                    className="inline-flex min-h-[44px] min-w-[44px] items-center justify-center rounded-xl border border-zinc-300 px-3 text-xs font-medium text-zinc-600 transition hover:border-red-500/40 hover:bg-red-500/10 hover:text-red-500 dark:border-zinc-700 dark:text-zinc-400 dark:hover:text-red-400"
                  >
                    <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
                  </button>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
