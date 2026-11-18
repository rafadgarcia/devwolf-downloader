import { useCallback, useEffect, useState } from 'react';
import {
  Copy,
  Edit3,
  ExternalLink,
  Film,
  Image as ImageIcon,
  MessageCircle,
  Music,
  RefreshCw,
  Search,
  Share2,
  Trash2,
} from 'lucide-react';
import { filterLocalHistory, removeLocalJob } from '../services/localQueue';
import { nativeBridge } from '../services/nativeBridge';
import type { CarouselItemState, HistoryItem, JobFile, JobResponse, PlatformId } from '../types/api';
import { getUserFacingErrorMessage } from '../utils/errorContract';
import {
  formatBytes,
  formatDateTime,
  formatDisplayEngine,
  formatResolution,
  getPhaseLabel,
  PLATFORM_META,
} from '../utils/format';

interface HistoryViewProps {
  localJobs: JobResponse[];
  onRepeatDownload: (url: string) => Promise<void>;
  onEditUrl: (url: string) => void;
  onLocalJobDeleted?: (jobId: string) => void;
}

const PLATFORM_FILTERS: { id: string; label: string }[] = [
  { id: 'all', label: 'Todas' },
  { id: 'instagram', label: 'Instagram' },
  { id: 'threads', label: 'Threads' },
  { id: 'tiktok', label: 'TikTok' },
  { id: 'youtube', label: 'YouTube' },
  { id: 'twitter', label: 'X / Twitter' },
  { id: 'facebook', label: 'Facebook' },
  { id: 'reddit', label: 'Reddit' },
  { id: 'pinterest', label: 'Pinterest' },
];

function toCarouselItem(file: JobFile, idx: number, status: HistoryItem['status']): CarouselItemState {
  const mime = (file.mime_type || '').toLowerCase();
  const type =
    file.type ??
    (mime.startsWith('image/') ? 'image' : mime.startsWith('audio/') ? 'audio' : 'video');
  return {
    id: file.id,
    index: file.index ?? idx + 1,
    type,
    fileName: file.filename,
    mimeType: file.mime_type,
    width: file.width ?? null,
    height: file.height ?? null,
    sizeBytes: file.size_bytes ?? 0,
    bytesDownloaded: file.bytes_downloaded ?? file.size_bytes ?? 0,
    percent: file.percent ?? (status === 'ready' ? 100 : null),
    status: file.status ?? (status === 'ready' ? 'ready' : 'failed'),
    mediaUri: file.media_uri ?? null,
    displayPath: file.display_path ?? null,
    filePath: file.file_path ?? null,
    durationSeconds: file.duration_seconds ?? null,
    isSegment: file.is_segment,
    segmentIndex: file.segment_index ?? null,
    isOriginal: file.is_original,
  };
}

export function HistoryView({
  localJobs,
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

  const loadHistory = useCallback(() => {
    setLoading(true);
    setError(null);
    const filtered = filterLocalHistory(localJobs, {
      q: query.trim() || undefined,
      platform,
    });
    setItems(filtered);
    setLoading(false);
  }, [localJobs, platform, query]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      loadHistory();
    }, 120);
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
      const filesToDelete = item.files && item.files.length > 0 ? item.files : item.file ? [item.file] : [];
      for (const f of filesToDelete) {
        if (f.media_uri || f.file_path) {
          await nativeBridge
            .deleteMedia({
              uri: f.media_uri || undefined,
              filePath: f.file_path || undefined,
            })
            .catch(() => false);
        }
      }
      removeLocalJob(item.id);
      onLocalJobDeleted?.(item.id);
      setItems((prev) => prev.filter((entry) => entry.id !== item.id));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Não foi possível remover o item.');
    }
  };

  const handleShareAllItems = async (
    title: string,
    carouselItems: CarouselItemState[],
    targetWhatsApp: boolean
  ) => {
    const ready = carouselItems.filter((c) => Boolean(c.mediaUri || c.filePath));
    const list = ready.filter((c) => !c.isOriginal);
    const finalList = list.length > 0 ? list : ready;
    if (finalList.length === 0) return;

    await nativeBridge.shareMedia({
      uris: finalList.map((c) => c.mediaUri).filter((u): u is string => Boolean(u)),
      filePaths: finalList.map((c) => c.filePath).filter((p): p is string => Boolean(p)),
      mimeType: finalList.length === 1 ? finalList[0].mimeType : '*/*',
      title,
      targetWhatsApp,
    });
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-base font-semibold text-zinc-900 dark:text-zinc-100">Histórico</h2>
        <button
          type="button"
          onClick={loadHistory}
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
            const carouselItems: CarouselItemState[] = (
              item.items && item.items.length > 0
                ? item.items
                : (item.files ?? (item.file ? [item.file] : [])).map((f, idx) =>
                    toCarouselItem(f, idx, item.status)
                  )
            )
              .filter((c) => !c.fileName.toLowerCase().endsWith('.zip'))
              .slice()
              .sort((a, b) => a.index - b.index);

            const readyCarousel = carouselItems.filter((c) => Boolean(c.mediaUri || c.filePath));
            const primaryItem = readyCarousel[0] ?? null;
            const isMulti = carouselItems.length > 1;
            const hasVideo = readyCarousel.some((c) => c.type === 'video');
            const engineBadge =
              item.display_engine || formatDisplayEngine(item.selected_engine, 'local', item.platform);

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
                        item.status === 'ready'
                          ? 'text-[#4d7c0f] dark:text-[#A3E635]'
                          : item.status === 'failed'
                          ? 'text-red-500 dark:text-red-400'
                          : 'text-zinc-500 dark:text-zinc-400'
                      }`}
                    >
                      {item.phase_label || getPhaseLabel(item.status)}
                    </span>
                  </div>
                </div>

                <p className="mt-2 line-clamp-2 text-sm font-medium text-zinc-900 dark:text-zinc-100">
                  {item.title || item.normalized_url || item.original_url}
                </p>

                <p className="mt-0.5 truncate text-xs text-zinc-500 dark:text-zinc-400">
                  {item.normalized_url || item.original_url}
                </p>

                {item.status === 'failed' && (item.error_code || item.error_message) && (
                  <p className="mt-1.5 text-xs text-red-500 dark:text-red-400">
                    {getUserFacingErrorMessage(item.error_code, item.error_message, item.platform)}
                  </p>
                )}

                {isMulti && readyCarousel.length > 0 && (
                  <ul className="mt-3 space-y-1.5">
                    {carouselItems.map((c) => {
                      const kindLabel =
                        c.isOriginal
                          ? 'Original'
                          : c.isSegment && c.segmentIndex
                          ? `Parte ${c.segmentIndex}`
                          : `${c.type === 'image' ? 'Foto' : c.type === 'audio' ? 'Áudio' : 'Vídeo'} ${c.index}/${carouselItems.length}`;
                      const resolution = formatResolution(c.width, c.height);
                      const ItemIcon =
                        c.type === 'image' ? ImageIcon : c.type === 'audio' ? Music : Film;
                      const canAct = Boolean(c.mediaUri || c.filePath);

                      return (
                        <li
                          key={c.id}
                          className="flex items-center justify-between gap-2 rounded-xl border border-zinc-200 bg-zinc-50 px-3 py-2 text-xs dark:border-zinc-800 dark:bg-[#101012]"
                        >
                          <div className="min-w-0">
                            <div className="flex flex-wrap items-center gap-1.5">
                              <span className="inline-flex items-center gap-1 font-medium text-zinc-800 dark:text-zinc-200">
                                <ItemIcon className="h-3.5 w-3.5 text-[#65a30d] dark:text-[#A3E635]" />
                                <span>{kindLabel}</span>
                              </span>
                              {resolution && (
                                <span className="rounded bg-zinc-200 px-1.5 py-0.5 font-mono text-[10px] text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300">
                                  {resolution}
                                </span>
                              )}
                            </div>
                            <p className="truncate text-[11px] text-zinc-500 dark:text-zinc-400">
                              {c.fileName}
                              {c.sizeBytes ? ` • ${formatBytes(c.sizeBytes)}` : ''}
                            </p>
                          </div>

                          {canAct && (
                            <div className="flex shrink-0 items-center gap-1.5">
                              <button
                                type="button"
                                onClick={() =>
                                  void nativeBridge.openMedia({
                                    uri: c.mediaUri || undefined,
                                    filePath: c.filePath || undefined,
                                    mimeType: c.mimeType,
                                  })
                                }
                                className="min-h-[36px] rounded-lg border border-zinc-300 bg-white px-2.5 text-xs font-medium text-zinc-700 dark:border-zinc-700 dark:bg-[#1C1C20] dark:text-zinc-200"
                              >
                                Abrir
                              </button>
                              <button
                                type="button"
                                onClick={() =>
                                  void nativeBridge.shareMedia({
                                    uris: c.mediaUri ? [c.mediaUri] : undefined,
                                    filePaths: c.filePath ? [c.filePath] : undefined,
                                    mimeType: c.mimeType,
                                    title: item.title || c.fileName,
                                  })
                                }
                                className="min-h-[36px] rounded-lg bg-[#A3E635]/20 px-2.5 text-xs font-semibold text-[#4d7c0f] dark:text-[#A3E635]"
                              >
                                Compartilhar
                              </button>
                            </div>
                          )}
                        </li>
                      );
                    })}
                  </ul>
                )}

                <div className="mt-3 flex flex-wrap gap-1.5">
                  {readyCarousel.length > 0 && hasVideo && (
                    <button
                      type="button"
                      onClick={() =>
                        void handleShareAllItems(
                          item.title || 'Devwolf Downloader',
                          carouselItems,
                          true
                        )
                      }
                      className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl bg-[#A3E635] px-3 text-xs font-semibold text-zinc-950 transition hover:bg-[#84CC16]"
                    >
                      <MessageCircle className="h-3.5 w-3.5" aria-hidden="true" />
                      <span>Status / WhatsApp</span>
                    </button>
                  )}

                  {isMulti && readyCarousel.length > 0 ? (
                    <button
                      type="button"
                      onClick={() =>
                        void handleShareAllItems(
                          item.title || 'Devwolf Downloader',
                          carouselItems,
                          false
                        )
                      }
                      className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl border border-zinc-300 bg-zinc-50 px-3 text-xs font-medium text-zinc-800 transition hover:bg-zinc-200 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200 dark:hover:bg-zinc-800"
                    >
                      <Share2 className="h-3.5 w-3.5" aria-hidden="true" />
                      <span>Compartilhar todos</span>
                    </button>
                  ) : (
                    primaryItem && (
                      <>
                        <button
                          type="button"
                          onClick={() =>
                            void nativeBridge.openMedia({
                              uri: primaryItem.mediaUri || undefined,
                              filePath: primaryItem.filePath || undefined,
                              mimeType: primaryItem.mimeType,
                            })
                          }
                          className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl border border-zinc-300 bg-zinc-50 px-3 text-xs font-medium text-zinc-800 transition hover:bg-zinc-200 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200 dark:hover:bg-zinc-800"
                        >
                          <ExternalLink className="h-3.5 w-3.5" aria-hidden="true" />
                          <span>
                            Abrir
                            {primaryItem.sizeBytes ? ` (${formatBytes(primaryItem.sizeBytes)})` : ''}
                          </span>
                        </button>
                        <button
                          type="button"
                          onClick={() =>
                            void nativeBridge.shareMedia({
                              uris: primaryItem.mediaUri ? [primaryItem.mediaUri] : undefined,
                              filePaths: primaryItem.filePath ? [primaryItem.filePath] : undefined,
                              mimeType: primaryItem.mimeType,
                              title: item.title || primaryItem.fileName,
                            })
                          }
                          className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl border border-zinc-300 bg-zinc-50 px-3 text-xs font-medium text-zinc-800 transition hover:bg-zinc-200 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200 dark:hover:bg-zinc-800"
                        >
                          <Share2 className="h-3.5 w-3.5" aria-hidden="true" />
                          <span>Compartilhar</span>
                        </button>
                      </>
                    )
                  )}

                  <button
                    type="button"
                    onClick={() => void onRepeatDownload(item.normalized_url || item.original_url)}
                    className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl border border-zinc-300 bg-zinc-50 px-3 text-xs font-medium text-zinc-800 transition hover:bg-zinc-200 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200 dark:hover:bg-zinc-800"
                  >
                    <RefreshCw className="h-3.5 w-3.5" aria-hidden="true" />
                    <span>Repetir</span>
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
