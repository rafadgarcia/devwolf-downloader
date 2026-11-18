import {
  ExternalLink,
  Film,
  Image as ImageIcon,
  MessageCircle,
  Music,
  RefreshCw,
  Share2,
  X,
} from 'lucide-react';
import { nativeBridge } from '../services/nativeBridge';
import type { CarouselItemState, JobResponse, PlatformId } from '../types/api';
import { useModalDialog } from '../utils/dialogHelpers';
import {
  formatBytes,
  formatDisplayEngine,
  formatDuration,
  formatResolution,
  PLATFORM_META,
} from '../utils/format';
import { MediaThumbnail } from './MediaThumbnail';

interface HeroActionsModalProps {
  open: boolean;
  job: JobResponse | null;
  items: CarouselItemState[];
  onClose: () => void;
  onRepeat: (url: string) => void;
}

export function HeroActionsModal({
  open,
  job,
  items,
  onClose,
  onRepeat,
}: HeroActionsModalProps) {
  const dialogRef = useModalDialog(open, onClose);

  if (!job) return null;

  const platformMeta =
    PLATFORM_META[(job.platform as PlatformId) in PLATFORM_META ? (job.platform as PlatformId) : 'instagram'];
  const displayEngine = formatDisplayEngine(job.current_engine || job.selected_engine, 'local', job.platform);
  const readyItems = items.filter((item) => Boolean(item.mediaUri || item.filePath));
  const hasVideo = readyItems.some((item) => item.type === 'video');

  const handleOpenItem = async (item: CarouselItemState) => {
    await nativeBridge.openMedia({
      uri: item.mediaUri || undefined,
      filePath: item.filePath || undefined,
      mimeType: item.mimeType,
    });
  };

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

    await nativeBridge.shareMedia({
      uris: list.map((f) => f.mediaUri).filter((u): u is string => Boolean(u)),
      filePaths: list.map((f) => f.filePath).filter((p): p is string => Boolean(p)),
      mimeType: list.length === 1 ? list[0].mimeType : '*/*',
      title: job.title || 'Devwolf Downloader',
      targetWhatsApp,
    });
  };

  return (
    <dialog
      ref={dialogRef}
      aria-labelledby="hero-actions-title"
      {...({ closedby: 'any' } as Record<string, string>)}
      className="w-[calc(100%-2rem)] max-w-md rounded-3xl border border-[#7C5CFF]/25 bg-[#F5F3FF] p-0 text-[#1E1940] shadow-2xl dark:border-[#9382FF]/25 dark:bg-[#191538] dark:text-[#F5F3FF]"
    >
      <div className="p-5">
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-1.5">
              <span className={`inline-flex rounded-md border px-2 py-0.5 text-[11px] font-medium ${platformMeta.badgeClass}`}>
                {platformMeta.label}
              </span>
              <span className="rounded-md border border-[#7C5CFF]/25 bg-[#7C5CFF]/10 px-2 py-0.5 text-[11px] font-medium text-[#5E43F3] dark:text-[#C4B5FD]">
                {displayEngine}
              </span>
            </div>
            <h2 id="hero-actions-title" className="mt-2 line-clamp-2 text-sm font-bold">
              {job.title || job.normalized_url || 'Mídia baixada'}
            </h2>
          </div>

          <button
            type="button"
            onClick={onClose}
            aria-label="Fechar opções"
            className="inline-flex min-h-[44px] min-w-[44px] shrink-0 items-center justify-center rounded-xl text-[#5B5482] transition hover:bg-[#7C5CFF]/10 dark:text-[#A5A1C8] dark:hover:bg-[#7C5CFF]/15"
          >
            <X className="h-5 w-5" aria-hidden="true" />
          </button>
        </div>

        {items.length > 0 && (
          <ul className="mt-4 max-h-72 space-y-2 overflow-y-auto pr-0.5" aria-label="Itens da mídia">
            {items.map((item) => {
              const ItemIcon =
                item.type === 'image' ? ImageIcon : item.type === 'audio' ? Music : Film;
              const resolution = formatResolution(item.width, item.height);
              const sizeText = formatBytes(item.sizeBytes);
              const canAct = Boolean(item.mediaUri || item.filePath);
              const label = item.isOriginal
                ? 'Original'
                : item.isSegment && item.segmentIndex
                ? `Parte ${item.segmentIndex}`
                : `${item.type === 'image' ? 'Foto' : item.type === 'audio' ? 'Áudio' : 'Vídeo'} ${item.index}/${items.length}`;

              return (
                <li
                  key={item.id}
                  className="dw-glass-subtle flex items-center gap-2.5 rounded-2xl p-2.5 text-xs"
                >
                  <div className="h-12 w-12 shrink-0 overflow-hidden rounded-xl">
                    <MediaThumbnail
                      thumbnailDataUrl={item.thumbnailDataUrl || job.thumbnail_data_url}
                      mediaUri={item.mediaUri}
                      filePath={item.filePath}
                      mediaType={item.type}
                      platform={job.platform}
                      compact
                    />
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <span className="inline-flex items-center gap-1 font-semibold">
                        <ItemIcon className="h-3.5 w-3.5 text-[#7C5CFF] dark:text-[#A78BFA]" aria-hidden="true" />
                        <span>{label}</span>
                      </span>
                      {resolution && (
                        <span className="rounded bg-[#7C5CFF]/15 px-1.5 py-0.5 font-mono text-[10px] text-[#5E43F3] dark:text-[#C4B5FD]">
                          {resolution}
                        </span>
                      )}
                    </div>
                    <p className="mt-0.5 truncate text-[11px] text-[#5B5482] dark:text-[#A5A1C8]">
                      {item.fileName}
                      {sizeText ? ` • ${sizeText}` : ''}
                      {item.durationSeconds ? ` • ${formatDuration(item.durationSeconds)}` : ''}
                    </p>
                  </div>

                  {canAct && (
                    <div className="flex shrink-0 items-center gap-1">
                      <button
                        type="button"
                        onClick={() => void handleOpenItem(item)}
                        aria-label={`Abrir ${label}`}
                        className="inline-flex min-h-[38px] items-center justify-center rounded-xl border border-[#7C5CFF]/25 px-2.5 text-xs font-medium transition hover:bg-[#7C5CFF]/10"
                      >
                        <ExternalLink className="h-3.5 w-3.5" aria-hidden="true" />
                      </button>
                      <button
                        type="button"
                        onClick={() => void handleShareItem(item, false)}
                        aria-label={`Compartilhar ${label}`}
                        className="inline-flex min-h-[38px] items-center justify-center rounded-xl bg-[#7C5CFF]/20 px-2.5 text-xs font-semibold text-[#5E43F3] transition hover:bg-[#7C5CFF]/30 dark:text-[#C4B5FD]"
                      >
                        <Share2 className="h-3.5 w-3.5" aria-hidden="true" />
                      </button>
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        )}

        <div className="mt-4 space-y-2">
          {readyItems.length > 0 && hasVideo && (
            <button
              type="button"
              onClick={() => void handleShareAll(true)}
              className="dw-btn-primary inline-flex min-h-[46px] w-full items-center justify-center gap-2 rounded-2xl px-4 text-xs font-bold"
            >
              <MessageCircle className="h-4 w-4" aria-hidden="true" />
              <span>Compartilhar no Status / WhatsApp</span>
            </button>
          )}

          <div className="flex gap-2">
            {readyItems.length > 0 && (
              <button
                type="button"
                onClick={() => void handleShareAll(false)}
                className="inline-flex min-h-[44px] flex-1 items-center justify-center gap-1.5 rounded-2xl border border-[#7C5CFF]/25 bg-[#7C5CFF]/10 px-3 text-xs font-semibold text-[#5E43F3] transition hover:bg-[#7C5CFF]/20 dark:text-[#C4B5FD]"
              >
                <Share2 className="h-4 w-4" aria-hidden="true" />
                <span>{readyItems.length > 1 ? 'Compartilhar todos' : 'Compartilhar'}</span>
              </button>
            )}
            <button
              type="button"
              onClick={() => {
                onClose();
                onRepeat(job.normalized_url || job.original_url);
              }}
              className="inline-flex min-h-[44px] flex-1 items-center justify-center gap-1.5 rounded-2xl border border-[#7C5CFF]/25 bg-[#7C5CFF]/10 px-3 text-xs font-semibold text-[#5E43F3] transition hover:bg-[#7C5CFF]/20 dark:text-[#C4B5FD]"
            >
              <RefreshCw className="h-4 w-4" aria-hidden="true" />
              <span>Baixar novamente</span>
            </button>
          </div>
        </div>
      </div>
    </dialog>
  );
}
