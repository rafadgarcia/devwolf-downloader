import { useCallback, useEffect, useState } from 'react';
import {
  Copy,
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
import {
  cleanNullableString,
  clearAllLocalJobs,
  filterLocalHistory,
  removeLocalJob,
} from '../services/localQueue';
import { nativeBridge } from '../services/nativeBridge';
import type { CarouselItemState, HistoryItem, JobFile, JobResponse, PlatformId } from '../types/api';
import { clearAllDedupeState } from '../utils/dedupe';
import { getUserFacingErrorMessage } from '../utils/errorContract';
import {
  formatBytes,
  formatDateTime,
  formatDisplayEngine,
  formatResolution,
  getPhaseLabel,
  PLATFORM_META,
} from '../utils/format';
import { MediaThumbnail } from './MediaThumbnail';
import { PlatformIcon } from './PlatformIcon';

interface HistoryViewProps {
  localJobs: JobResponse[];
  onRepeatDownload: (url: string) => Promise<void>;
  onLocalJobDeleted?: (jobId: string) => void;
  onClearAllHistory?: () => void;
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

function toCarouselItem(
  file: JobFile,
  idx: number,
  status: HistoryItem['status']
): CarouselItemState {
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
    thumbnailDataUrl: file.thumbnail_data_url ?? null,
    durationSeconds: file.duration_seconds ?? null,
    isSegment: file.is_segment,
    segmentIndex: file.segment_index ?? null,
    isOriginal: file.is_original,
  };
}

export function HistoryView({
  localJobs,
  onRepeatDownload,
  onLocalJobDeleted,
  onClearAllHistory,
}: HistoryViewProps) {
  const [items, setItems] = useState<HistoryItem[]>([]);
  const [query, setQuery] = useState('');
  const [platform, setPlatform] = useState('all');
  const [loading, setLoading] = useState(true);
  const [clearing, setClearing] = useState(false);
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
      const filesToDelete =
        item.files && item.files.length > 0 ? item.files : item.file ? [item.file] : [];
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

  const handleClearAllHistory = async () => {
    if (clearing) return;
    setClearing(true);
    setError(null);
    try {
      await nativeBridge.clearLocalDownloadHistory();
      clearAllLocalJobs();
      clearAllDedupeState();
      setItems([]);
      onClearAllHistory?.();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Não foi possível limpar o histórico.');
    } finally {
      setClearing(false);
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
    <div className="space-y-4 pb-8">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-base font-bold text-[#1E1940] dark:text-[#F5F3FF]">
          Histórico de downloads
        </h2>
        <div className="flex items-center gap-1.5">
          {(items.length > 0 || localJobs.length > 0) && (
            <button
              type="button"
              disabled={clearing}
              onClick={() => void handleClearAllHistory()}
              className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl border border-rose-500/25 bg-rose-500/10 px-3 text-xs font-semibold text-rose-600 transition hover:bg-rose-500/20 disabled:opacity-50 dark:text-rose-300"
            >
              <Trash2 className="h-4 w-4" aria-hidden="true" />
              <span>{clearing ? 'Limpando…' : 'Limpar histórico'}</span>
            </button>
          )}
          <button
            type="button"
            onClick={loadHistory}
            className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl px-3 text-xs font-medium text-[#5B5482] transition hover:bg-[#7C5CFF]/10 dark:text-[#C4B5FD] dark:hover:bg-[#7C5CFF]/15"
          >
            <RefreshCw className="h-4 w-4" aria-hidden="true" />
            <span>Atualizar</span>
          </button>
        </div>
      </div>

      <div className="relative">
        <Search
          className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-[#7D75A8]"
          aria-hidden="true"
        />
        <input
          type="search"
          aria-label="Buscar no histórico"
          placeholder="Buscar por título ou URL…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          className="dw-glass-card min-h-[46px] w-full rounded-2xl pl-10 pr-3.5 text-sm text-[#1E1940] placeholder-[#7D75A8] outline-none focus:border-[#7C5CFF] dark:text-[#F5F3FF] dark:placeholder-[#7A74A6]"
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
              className={`min-h-[42px] shrink-0 rounded-xl px-3.5 text-xs font-semibold transition ${
                active
                  ? 'dw-btn-primary'
                  : 'dw-glass-card text-[#5B5482] hover:border-[#7C5CFF]/40 dark:text-[#C4B5FD]'
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
          className="rounded-2xl border border-rose-500/30 bg-rose-500/10 p-3 text-xs text-rose-600 dark:text-rose-300"
        >
          {error}
        </div>
      )}

      {loading ? (
        <div className="dw-glass-card rounded-3xl p-6 text-center text-xs text-[#5B5482] dark:text-[#A5A1C8]">
          Carregando histórico…
        </div>
      ) : items.length === 0 ? (
        <div className="dw-glass-card rounded-3xl p-6 text-center text-sm text-[#5B5482] dark:text-[#A5A1C8]">
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
            const engineBadge = formatDisplayEngine(item.selected_engine, 'local', item.platform);
            const cleanTitle = cleanNullableString(item.title);
            const cleanErrorMessage = cleanNullableString(item.error_message);
            const thumb =
              cleanNullableString(item.thumbnail_data_url) ||
              carouselItems.find((c) => Boolean(cleanNullableString(c.thumbnailDataUrl)))
                ?.thumbnailDataUrl ||
              null;

            return (
              <li key={item.id} className="dw-glass-card rounded-3xl p-4">
                <div className="flex items-start gap-3">
                  <div className="h-16 w-16 shrink-0 overflow-hidden rounded-2xl border border-[#7C5CFF]/20">
                    <MediaThumbnail
                      thumbnailDataUrl={thumb}
                      mediaUri={primaryItem?.mediaUri}
                      filePath={primaryItem?.filePath}
                      mediaType={primaryItem?.type ?? 'video'}
                      platform={item.platform}
                      compact
                    />
                  </div>

                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center justify-between gap-1.5">
                      <div className="flex items-center gap-1.5">
                        <span
                          className={`inline-flex items-center gap-1 rounded-md border px-2 py-0.5 text-[11px] font-medium ${meta.badgeClass}`}
                        >
                          <PlatformIcon platform={item.platform} className="h-3 w-3 shrink-0" />
                          <span>{meta.label}</span>
                        </span>
                        <span className="rounded-md bg-[#7C5CFF]/15 px-2 py-0.5 text-[11px] font-medium text-[#5E43F3] dark:text-[#C4B5FD]">
                          {engineBadge}
                        </span>
                      </div>

                      <span className="text-[11px] text-[#5B5482] dark:text-[#A5A1C8]">
                        {formatDateTime(item.created_at)}
                      </span>
                    </div>

                    <p className="mt-1.5 line-clamp-2 text-sm font-semibold text-[#1E1940] dark:text-[#F5F3FF]">
                      {cleanTitle || item.normalized_url || item.original_url}
                    </p>

                    <div className="mt-1 flex items-center justify-between gap-2">
                      <p className="truncate text-[11px] text-[#5B5482] dark:text-[#A5A1C8]">
                        {item.normalized_url || item.original_url}
                      </p>
                      <span
                        className={`shrink-0 text-[11px] font-semibold ${
                          item.status === 'ready'
                            ? 'text-[#5E43F3] dark:text-[#A78BFA]'
                            : item.status === 'failed'
                            ? 'text-rose-500 dark:text-rose-300'
                            : 'text-[#5B5482] dark:text-[#A5A1C8]'
                        }`}
                      >
                        {cleanNullableString(item.phase_label) || getPhaseLabel(item.status)}
                      </span>
                    </div>
                  </div>
                </div>

                {item.status === 'failed' && (item.error_code || cleanErrorMessage) && (
                  <p className="mt-2 text-xs text-rose-600 dark:text-rose-300">
                    {getUserFacingErrorMessage(item.error_code, cleanErrorMessage, item.platform)}
                  </p>
                )}

                {isMulti && readyCarousel.length > 0 && (
                  <ul className="mt-3 space-y-1.5">
                    {carouselItems.map((c) => {
                      const kindLabel = c.isOriginal
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
                          className="dw-glass-subtle flex items-center justify-between gap-2 rounded-2xl px-3 py-2 text-xs"
                        >
                          <div className="min-w-0">
                            <div className="flex flex-wrap items-center gap-1.5">
                              <span className="inline-flex items-center gap-1 font-medium text-[#1E1940] dark:text-[#F5F3FF]">
                                <ItemIcon className="h-3.5 w-3.5 text-[#7C5CFF] dark:text-[#A78BFA]" />
                                <span>{kindLabel}</span>
                              </span>
                              {resolution && (
                                <span className="rounded bg-[#7C5CFF]/15 px-1.5 py-0.5 font-mono text-[10px] text-[#5E43F3] dark:text-[#C4B5FD]">
                                  {resolution}
                                </span>
                              )}
                            </div>
                            <p className="truncate text-[11px] text-[#5B5482] dark:text-[#A5A1C8]">
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
                                className="min-h-[36px] rounded-xl border border-[#7C5CFF]/25 px-2.5 text-xs font-medium text-[#4C3F91] dark:text-[#E0DBFF]"
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
                                className="min-h-[36px] rounded-xl bg-[#7C5CFF]/20 px-2.5 text-xs font-semibold text-[#5E43F3] dark:text-[#C4B5FD]"
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
                      className="dw-btn-primary inline-flex min-h-[44px] items-center gap-1.5 rounded-xl px-3 text-xs font-semibold transition"
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
                      className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl border border-[#7C5CFF]/25 bg-[#7C5CFF]/10 px-3 text-xs font-medium text-[#4C3F91] transition hover:bg-[#7C5CFF]/20 dark:text-[#E0DBFF]"
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
                          className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl border border-[#7C5CFF]/25 bg-[#7C5CFF]/10 px-3 text-xs font-medium text-[#4C3F91] transition hover:bg-[#7C5CFF]/20 dark:text-[#E0DBFF]"
                        >
                          <ExternalLink className="h-3.5 w-3.5" aria-hidden="true" />
                          <span>
                            Abrir
                            {primaryItem.sizeBytes
                              ? ` (${formatBytes(primaryItem.sizeBytes)})`
                              : ''}
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
                          className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl border border-[#7C5CFF]/25 bg-[#7C5CFF]/10 px-3 text-xs font-medium text-[#4C3F91] transition hover:bg-[#7C5CFF]/20 dark:text-[#E0DBFF]"
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
                    aria-label="Repetir download"
                    title="Repetir download"
                    className="inline-flex min-h-[44px] min-w-[44px] items-center justify-center rounded-xl border border-[#7C5CFF]/25 bg-[#7C5CFF]/10 px-3 text-xs font-medium text-[#4C3F91] transition hover:bg-[#7C5CFF]/20 dark:text-[#E0DBFF]"
                  >
                    <RefreshCw className="h-4 w-4" aria-hidden="true" />
                  </button>

                  <button
                    type="button"
                    onClick={() => void handleCopy(item)}
                    className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl border border-[#7C5CFF]/25 bg-[#7C5CFF]/10 px-3 text-xs font-medium text-[#4C3F91] transition hover:bg-[#7C5CFF]/20 dark:text-[#E0DBFF]"
                  >
                    <Copy className="h-3.5 w-3.5" aria-hidden="true" />
                    <span>{copiedId === item.id ? 'Copiado!' : 'Copiar URL'}</span>
                  </button>

                  <button
                    type="button"
                    onClick={() => void handleDelete(item)}
                    aria-label="Remover do histórico"
                    className="inline-flex min-h-[44px] min-w-[44px] items-center justify-center rounded-xl border border-[#7C5CFF]/25 px-3 text-xs font-medium text-[#5B5482] transition hover:border-rose-500/40 hover:bg-rose-500/10 hover:text-rose-500 dark:text-[#A5A1C8] dark:hover:text-rose-300"
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
