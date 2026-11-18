import { useState } from 'react';
import {
  AlertCircle,
  ArrowLeft,
  ArrowRight,
  HelpCircle,
  Image as ImageIcon,
  KeyRound,
  Layers,
  Loader2,
  MoreVertical,
  Music,
  Play,
  Plus,
  RefreshCw,
  Repeat2,
  Share2,
  Sparkles,
  Video,
  XCircle,
} from 'lucide-react';
import { nativeBridge } from '../services/nativeBridge';
import type { CarouselItemState, JobFile, JobResponse, PlatformId } from '../types/api';
import { getUserFacingErrorMessage, normalizeErrorCode } from '../utils/errorContract';
import {
  formatBytes,
  formatDisplayEngine,
  formatDuration,
  getPhaseLabel,
  PLATFORM_META,
} from '../utils/format';
import { HeroActionsModal } from './HeroActionsModal';
import { MediaThumbnail } from './MediaThumbnail';
import { OnboardingBanner } from './OnboardingBanner';
import { PlatformIcon } from './PlatformIcon';

interface HomeViewProps {
  localJobs: JobResponse[];
  activeJob: JobResponse | null;
  engineSwitchNotice: string | null;
  isNativeAndroid: boolean;
  onboardingDone: boolean;
  showReturnToSourceApp?: boolean;
  statusNotice: string | null;
  onOnboardingChoice: (enableAuto: boolean) => void;
  onOpenAddLink: () => void;
  onOpenHistory: () => void;
  onOpenHowTo: () => void;
  onOpenSettings: () => void;
  onOpenAccountSettings: (platform: string) => void;
  onCancelJob: (jobId: string) => Promise<void>;
  onRetryJob: (url: string) => Promise<void>;
  onReturnToSourceApp?: () => Promise<void>;
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

function getJobCarouselItems(job: JobResponse): CarouselItemState[] {
  const raw =
    job.items && job.items.length > 0
      ? job.items
      : (job.files ?? []).map((f, idx) => fileToCarouselItem(f, idx, job.status));
  return raw
    .filter((item) => !item.fileName.toLowerCase().endsWith('.zip'))
    .slice()
    .sort((a, b) => a.index - b.index);
}

function getJobThumbnail(job: JobResponse, items: CarouselItemState[]): string | null {
  return (
    job.thumbnail_data_url ||
    items.find((i) => Boolean(i.thumbnailDataUrl))?.thumbnailDataUrl ||
    job.files?.find((f) => Boolean(f.thumbnail_data_url))?.thumbnail_data_url ||
    null
  );
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

export function HomeView({
  localJobs,
  activeJob,
  engineSwitchNotice,
  isNativeAndroid,
  onboardingDone,
  showReturnToSourceApp,
  statusNotice,
  onOnboardingChoice,
  onOpenAddLink,
  onOpenHistory,
  onOpenHowTo,
  onOpenSettings,
  onOpenAccountSettings,
  onCancelJob,
  onRetryJob,
  onReturnToSourceApp,
}: HomeViewProps) {
  const [detailsJob, setDetailsJob] = useState<JobResponse | null>(null);

  const heroJob = activeJob ?? localJobs[0] ?? null;
  const heroItems = heroJob ? getJobCarouselItems(heroJob) : [];
  const heroPrimaryItem = heroItems[0] ?? null;
  const heroThumb = heroJob ? getJobThumbnail(heroJob, heroItems) : null;
  const heroReadyItems = heroItems.filter((item) => Boolean(item.mediaUri || item.filePath));
  const heroIsActive = Boolean(heroJob && ACTIVE_STATUSES.has(heroJob.status));
  const heroIsFailed = heroJob?.status === 'failed' || heroJob?.status === 'cancelled';

  // "Baixado recentemente" exibe grade expandida de miniaturas (sem "Visitados frequentemente")
  const readyDownloads = localJobs.filter((job) => job.status === 'ready');
  const recentGridJobs =
    readyDownloads.length > 1
      ? readyDownloads.filter((job) => job.id !== heroJob?.id).slice(0, 9)
      : readyDownloads.slice(0, 9);

  const heroMeta =
    PLATFORM_META[
      heroJob && (heroJob.platform as PlatformId) in PLATFORM_META
        ? (heroJob.platform as PlatformId)
        : 'instagram'
    ];
  const heroEngine = heroJob
    ? formatDisplayEngine(heroJob.current_engine || heroJob.selected_engine, 'local', heroJob.platform)
    : '';
  const heroHasKnownTotal = Boolean(
    heroJob &&
      !heroJob.is_indeterminate &&
      heroJob.total_bytes !== null &&
      heroJob.total_bytes !== undefined &&
      heroJob.total_bytes > 0
  );
  const heroPercent =
    heroJob && heroHasKnownTotal && heroJob.percent !== null && heroJob.percent !== undefined
      ? Math.min(100, Math.max(0, Math.round(heroJob.percent)))
      : null;

  const handleOpenHero = async () => {
    if (!heroJob) return;
    if (heroItems.length > 1) {
      setDetailsJob(heroJob);
      return;
    }
    if (heroPrimaryItem && (heroPrimaryItem.mediaUri || heroPrimaryItem.filePath)) {
      await nativeBridge.openMedia({
        uri: heroPrimaryItem.mediaUri || undefined,
        filePath: heroPrimaryItem.filePath || undefined,
        mimeType: heroPrimaryItem.mimeType,
      });
    } else {
      setDetailsJob(heroJob);
    }
  };

  const handleShareHero = async (targetWhatsApp: boolean) => {
    if (!heroJob || heroReadyItems.length === 0) return;
    const shareable = heroReadyItems.filter((item) => !item.isOriginal);
    const list = shareable.length > 0 ? shareable : heroReadyItems;
    const allImages = list.every((i) => i.type === 'image');
    const allVideos = list.every((i) => i.type === 'video');

    await nativeBridge.shareMedia({
      uris: list.map((f) => f.mediaUri).filter((u): u is string => Boolean(u)),
      filePaths: list.map((f) => f.filePath).filter((p): p is string => Boolean(p)),
      mimeType:
        list.length === 1
          ? list[0].mimeType
          : allImages
          ? 'image/*'
          : allVideos
          ? 'video/*'
          : '*/*',
      title: heroJob.title || 'Devwolf Downloader',
      targetWhatsApp,
    });
  };

  const handleOpenRecentTile = async (job: JobResponse) => {
    const items = getJobCarouselItems(job);
    if (items.length > 1) {
      setDetailsJob(job);
      return;
    }
    const primary = items[0];
    if (primary && (primary.mediaUri || primary.filePath)) {
      await nativeBridge.openMedia({
        uri: primary.mediaUri || undefined,
        filePath: primary.filePath || undefined,
        mimeType: primary.mimeType,
      });
    } else {
      setDetailsJob(job);
    }
  };

  const HeroMediaTypeIcon =
    heroItems.length > 1
      ? Layers
      : heroPrimaryItem?.type === 'image'
      ? ImageIcon
      : heroPrimaryItem?.type === 'audio'
      ? Music
      : Video;

  return (
    <div className="space-y-4 pb-20">
      {isNativeAndroid && showReturnToSourceApp && onReturnToSourceApp && (
        <div className="dw-glass-card flex items-center justify-between gap-2 rounded-2xl px-3.5 py-2.5">
          <span className="text-xs font-medium text-[#1E1940] dark:text-[#E0DBFF]">
            Link recebido por compartilhamento
          </span>
          <button
            type="button"
            onClick={() => void onReturnToSourceApp()}
            className="dw-btn-primary inline-flex min-h-[40px] shrink-0 items-center gap-1.5 rounded-xl px-3 text-xs font-semibold transition"
          >
            <ArrowLeft className="h-3.5 w-3.5" aria-hidden="true" />
            <span>Voltar</span>
          </button>
        </div>
      )}

      {!onboardingDone && <OnboardingBanner onChoose={onOnboardingChoice} />}

      {statusNotice && (
        <div className="rounded-2xl border border-[#7C5CFF]/30 bg-[#7C5CFF]/15 px-3.5 py-2.5 text-xs font-medium text-[#5E43F3] dark:text-[#C4B5FD]">
          {statusNotice}
        </div>
      )}

      {/* 1. Hero Card — Último download ou download ativo */}
      {heroJob ? (
        <section
          aria-live={heroIsActive ? 'polite' : 'off'}
          aria-label="Destaque do download mais recente"
          className="dw-glass-card relative overflow-hidden rounded-3xl"
        >
          <div className="relative aspect-[16/10] w-full overflow-hidden sm:aspect-video">
            <MediaThumbnail
              thumbnailDataUrl={heroThumb}
              mediaUri={heroPrimaryItem?.mediaUri}
              filePath={heroPrimaryItem?.filePath}
              mediaType={heroPrimaryItem?.type ?? 'video'}
              platform={heroJob.platform}
              alt={heroJob.title || 'Prévia do download mais recente'}
            />

            {/* Top badges */}
            <div className="pointer-events-none absolute inset-x-0 top-0 flex items-start justify-between p-3">
              <div className="flex flex-wrap items-center gap-1.5">
                <span className="inline-flex items-center gap-1.5 rounded-full border border-white/15 bg-[#0D0B1E]/65 px-2.5 py-1 text-[11px] font-semibold text-white backdrop-blur-md">
                  <PlatformIcon platform={heroJob.platform} className="h-3.5 w-3.5 text-white" />
                  <span>{heroMeta.label}</span>
                </span>
                <span className="rounded-full border border-[#A78BFA]/30 bg-[#0D0B1E]/65 px-2.5 py-1 text-[11px] font-medium text-[#C4B5FD] backdrop-blur-md">
                  {heroEngine}
                </span>
              </div>

              <span className="inline-flex items-center gap-1 rounded-full border border-white/15 bg-[#0D0B1E]/65 px-2.5 py-1 text-[11px] font-semibold text-white backdrop-blur-md">
                <HeroMediaTypeIcon className="h-3.5 w-3.5" aria-hidden="true" />
                {heroItems.length > 1 ? (
                  <span>1/{heroItems.length}</span>
                ) : heroPrimaryItem?.durationSeconds ? (
                  <span>{formatDuration(heroPrimaryItem.durationSeconds)}</span>
                ) : null}
              </span>
            </div>

            {/* Centro: Progresso ativo OU Erro OU Botão Play translúcido */}
            {heroIsActive ? (
              <div className="absolute inset-0 flex flex-col items-center justify-center bg-[#0D0B1E]/65 px-6 text-center backdrop-blur-sm">
                <Loader2
                  className="h-10 w-10 animate-spin text-[#A78BFA]"
                  aria-hidden="true"
                />
                <p className="mt-2.5 text-sm font-bold text-white">
                  {heroJob.phase_label || getPhaseLabel(heroJob.status)}
                </p>
                <p className="mt-0.5 text-xs text-[#C4B5FD]">
                  {heroPercent !== null ? `${heroPercent}%` : 'Preparando mídia…'}
                  {heroJob.bytes_downloaded > 0 ? ` • ${formatBytes(heroJob.bytes_downloaded)}` : ''}
                  {heroHasKnownTotal ? ` / ${formatBytes(heroJob.total_bytes)}` : ''}
                </p>

                <div className="mt-3 h-2 w-full max-w-xs overflow-hidden rounded-full bg-white/15">
                  {heroPercent !== null ? (
                    <div
                      className="h-full rounded-full bg-gradient-to-r from-[#7C5CFF] to-[#C4B5FD] transition-all duration-300"
                      style={{ width: `${heroPercent}%` }}
                    />
                  ) : (
                    <div className="animate-indeterminate h-full rounded-full bg-gradient-to-r from-[#7C5CFF] to-[#C4B5FD]" />
                  )}
                </div>

                {engineSwitchNotice && (
                  <p className="mt-2 text-[11px] text-amber-300">{engineSwitchNotice}</p>
                )}

                <button
                  type="button"
                  onClick={() => void onCancelJob(heroJob.id)}
                  className="mt-3 inline-flex min-h-[40px] items-center gap-1.5 rounded-full border border-white/20 bg-white/10 px-4 text-xs font-semibold text-white backdrop-blur-md transition hover:bg-rose-500/30"
                >
                  <XCircle className="h-3.5 w-3.5" aria-hidden="true" />
                  <span>Cancelar</span>
                </button>
              </div>
            ) : heroIsFailed ? (
              <div className="absolute inset-0 flex flex-col items-center justify-center bg-[#0D0B1E]/75 px-5 text-center backdrop-blur-sm">
                <AlertCircle className="h-8 w-8 text-rose-400" aria-hidden="true" />
                <p className="mt-2 line-clamp-2 max-w-sm text-xs font-medium text-rose-100">
                  {heroJob.status === 'cancelled'
                    ? 'Download cancelado pelo usuário.'
                    : getUserFacingErrorMessage(
                        heroJob.error_code,
                        heroJob.error_message,
                        heroJob.platform
                      )}
                </p>
                <div className="mt-3 flex flex-wrap justify-center gap-2">
                  <button
                    type="button"
                    onClick={() =>
                      void onRetryJob(heroJob.normalized_url || heroJob.original_url)
                    }
                    className="dw-btn-primary inline-flex min-h-[40px] items-center gap-1.5 rounded-full px-4 text-xs font-bold"
                  >
                    <RefreshCw className="h-3.5 w-3.5" aria-hidden="true" />
                    <span>Tentar novamente</span>
                  </button>
                  {normalizeErrorCode(heroJob.error_code) === 'AUTH_REQUIRED' && (
                    <button
                      type="button"
                      onClick={() => onOpenAccountSettings(heroJob.platform)}
                      className="inline-flex min-h-[40px] items-center gap-1.5 rounded-full border border-white/20 bg-white/10 px-3.5 text-xs font-semibold text-white backdrop-blur-md"
                    >
                      <KeyRound className="h-3.5 w-3.5" aria-hidden="true" />
                      <span>{getAuthActionLabel(heroJob.platform)}</span>
                    </button>
                  )}
                </div>
              </div>
            ) : (
              <button
                type="button"
                onClick={() => void handleOpenHero()}
                aria-label="Abrir mídia mais recente"
                className="group absolute left-1/2 top-1/2 flex h-14 w-14 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full border border-white/25 bg-[#0D0B1E]/60 text-white shadow-2xl backdrop-blur-md transition hover:scale-105 hover:bg-[#7C5CFF]/80"
              >
                <Play className="ml-0.5 h-6 w-6 fill-current" aria-hidden="true" />
              </button>
            )}

            {/* Barra translúcida inferior estilo Rposty */}
            <div className="absolute inset-x-0 bottom-0 flex items-center justify-between gap-2 border-t border-white/10 bg-[#0D0B1E]/75 px-3.5 py-2.5 backdrop-blur-xl">
              <div className="flex min-w-0 flex-1 items-center gap-2.5">
                <div
                  className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-[#7C5CFF] to-[#5E43F3] text-white shadow"
                  aria-hidden="true"
                >
                  <PlatformIcon platform={heroJob.platform} className="h-[18px] w-[18px]" />
                </div>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-semibold text-white">
                    {heroJob.title || heroPrimaryItem?.fileName || heroMeta.label}
                  </p>
                  <p className="truncate text-[11px] text-[#C4B5FD]">
                    {heroMeta.label} • {heroEngine}
                    {heroPrimaryItem?.sizeBytes ? ` • ${formatBytes(heroPrimaryItem.sizeBytes)}` : ''}
                  </p>
                </div>
              </div>

              <div className="flex shrink-0 items-center gap-1">
                <button
                  type="button"
                  disabled={heroReadyItems.length === 0}
                  onClick={() => void handleShareHero(false)}
                  aria-label="Compartilhar mídia"
                  title="Compartilhar"
                  className="inline-flex min-h-[40px] min-w-[40px] items-center justify-center rounded-xl text-white/90 transition hover:bg-white/15 disabled:opacity-40"
                >
                  <Share2 className="h-4 w-4" aria-hidden="true" />
                </button>
                <button
                  type="button"
                  disabled={heroReadyItems.length === 0}
                  onClick={() =>
                    void (heroReadyItems.some((i) => i.type === 'video')
                      ? handleShareHero(true)
                      : handleOpenHero())
                  }
                  aria-label="Repostar ou abrir mídia"
                  title="Repostar / Abrir"
                  className="inline-flex min-h-[40px] min-w-[40px] items-center justify-center rounded-xl text-white/90 transition hover:bg-white/15 disabled:opacity-40"
                >
                  <Repeat2 className="h-4 w-4" aria-hidden="true" />
                </button>
                <button
                  type="button"
                  onClick={() => setDetailsJob(heroJob)}
                  aria-label="Mais opções e itens do carrossel"
                  title="Mais opções"
                  className="inline-flex min-h-[40px] min-w-[40px] items-center justify-center rounded-xl text-white/90 transition hover:bg-white/15"
                >
                  <MoreVertical className="h-4 w-4" aria-hidden="true" />
                </button>
              </div>
            </div>
          </div>
        </section>
      ) : (
        <section
          aria-label="Boas-vindas Devwolf"
          className="dw-glass-card relative overflow-hidden rounded-3xl p-6 text-center"
        >
          <div
            className="pointer-events-none absolute -right-10 -top-10 h-36 w-36 rounded-full bg-[#7C5CFF]/25 blur-3xl"
            aria-hidden="true"
          />
          <div className="relative mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-gradient-to-br from-[#7C5CFF] to-[#5E43F3] text-white shadow-lg shadow-[#5E43F3]/40">
            <Sparkles className="h-7 w-7" aria-hidden="true" />
          </div>
          <h2 className="mt-3 text-base font-bold text-[#1E1940] dark:text-[#F5F3FF]">
            Nenhum download ainda
          </h2>
          <p className="mx-auto mt-1 max-w-xs text-xs leading-relaxed text-[#5B5482] dark:text-[#A5A1C8]">
            Copie um link, compartilhe direto do seu app favorito ou toque no botão{' '}
            <strong>+</strong> para baixar Reels, vídeos, fotos e carrosséis.
          </p>
          <button
            type="button"
            onClick={onOpenAddLink}
            className="dw-btn-primary mt-4 inline-flex min-h-[44px] items-center gap-2 rounded-2xl px-5 text-xs font-bold"
          >
            <Plus className="h-4 w-4" aria-hidden="true" />
            <span>Adicionar link</span>
          </button>
        </section>
      )}

      {/* 2 & 3. "Baixado recentemente" expandido (sem "Visitados frequentemente") */}
      <section aria-label="Baixado recentemente" className="dw-glass-card rounded-3xl p-4">
        <div className="flex items-center justify-between gap-2">
          <h2 className="text-base font-bold tracking-tight text-[#1E1940] dark:text-[#F5F3FF]">
            Baixado recentemente
          </h2>
          <button
            type="button"
            onClick={onOpenHistory}
            aria-label="Ver todos os downloads"
            className="inline-flex min-h-[40px] min-w-[40px] items-center justify-center rounded-xl text-[#5E43F3] transition hover:bg-[#7C5CFF]/15 dark:text-[#C4B5FD]"
          >
            <ArrowRight className="h-5 w-5" aria-hidden="true" />
          </button>
        </div>

        {recentGridJobs.length === 0 ? (
          <div className="mt-3 rounded-2xl border border-dashed border-[#7C5CFF]/25 px-4 py-8 text-center text-xs text-[#5B5482] dark:text-[#A5A1C8]">
            Suas miniaturas de vídeos e fotos baixados recentemente aparecerão aqui.
          </div>
        ) : (
          <div className="mt-3 grid grid-cols-3 gap-2.5 sm:grid-cols-4">
            {recentGridJobs.map((job) => {
              const items = getJobCarouselItems(job);
              const primary = items[0] ?? null;
              const thumb = getJobThumbnail(job, items);
              const meta =
                PLATFORM_META[
                  (job.platform as PlatformId) in PLATFORM_META
                    ? (job.platform as PlatformId)
                    : 'instagram'
                ];
              const TileIcon =
                items.length > 1
                  ? Layers
                  : primary?.type === 'image'
                  ? ImageIcon
                  : primary?.type === 'audio'
                  ? Music
                  : Video;

              return (
                <button
                  key={job.id}
                  type="button"
                  onClick={() => void handleOpenRecentTile(job)}
                  aria-label={job.title || `Abrir download de ${meta.label}`}
                  className="group relative aspect-square overflow-hidden rounded-2xl border border-[#7C5CFF]/20 text-left transition hover:scale-[1.02] hover:border-[#A78BFA]/50"
                >
                  <MediaThumbnail
                    thumbnailDataUrl={thumb}
                    mediaUri={primary?.mediaUri}
                    filePath={primary?.filePath}
                    mediaType={primary?.type ?? 'video'}
                    platform={job.platform}
                    compact
                  />

                  <span
                    className="absolute right-1.5 top-1.5 inline-flex items-center gap-0.5 rounded-lg bg-[#0D0B1E]/70 px-1.5 py-0.5 text-[10px] font-semibold text-white backdrop-blur-sm"
                    aria-hidden="true"
                  >
                    <TileIcon className="h-3 w-3" />
                    {items.length > 1 ? <span>{items.length}</span> : null}
                  </span>

                  <div className="absolute inset-x-0 bottom-0 flex items-center justify-between bg-gradient-to-t from-[#0D0B1E]/85 via-[#0D0B1E]/40 to-transparent px-2 pb-1.5 pt-4 text-[10px] font-semibold text-white">
                    <span
                      className="inline-flex h-6 w-6 items-center justify-center rounded-full bg-[#7C5CFF]/85 text-white shadow-sm backdrop-blur-sm"
                      aria-hidden="true"
                    >
                      <PlatformIcon platform={job.platform} className="h-3.5 w-3.5" />
                    </span>
                    {primary?.durationSeconds ? (
                      <span className="tabular-nums opacity-90">
                        {formatDuration(primary.durationSeconds)}
                      </span>
                    ) : null}
                  </div>
                </button>
              );
            })}
          </div>
        )}
      </section>

      {/* Rodapé limpo estilo Rposty + atalhos */}
      <footer className="flex flex-col items-center gap-2.5 pt-1 text-center">
        <p className="text-xs text-[#5B5482] dark:text-[#A5A1C8]">
          Salvo na Galeria • Álbum Devwolf Downloader
        </p>

        <div className="flex flex-wrap items-center justify-center gap-2">
          <button
            type="button"
            onClick={onOpenHowTo}
            className="inline-flex min-h-[40px] items-center gap-1.5 rounded-xl border border-[#7C5CFF]/35 bg-[#7C5CFF]/10 px-4 text-xs font-semibold text-[#5E43F3] transition hover:bg-[#7C5CFF]/20 dark:text-[#C4B5FD]"
          >
            <HelpCircle className="h-3.5 w-3.5" aria-hidden="true" />
            <span>Como baixar?</span>
          </button>

          <button
            type="button"
            onClick={onOpenSettings}
            className="inline-flex min-h-[40px] items-center gap-1.5 rounded-xl border border-[#7C5CFF]/20 bg-white/50 px-4 text-xs font-medium text-[#4C3F91] transition hover:bg-[#7C5CFF]/10 dark:bg-[#1E1940]/60 dark:text-[#A5A1C8]"
          >
            <span>Configurações</span>
          </button>
        </div>
      </footer>

      {/* Floating Action Button (+) */}
      <button
        type="button"
        onClick={onOpenAddLink}
        aria-label="Adicionar link de download"
        className="dw-btn-primary fixed bottom-[max(1.25rem,env(safe-area-inset-bottom))] right-4 z-20 flex h-14 w-14 items-center justify-center rounded-full shadow-2xl transition hover:scale-105 active:scale-95 sm:right-6"
      >
        <Plus className="h-7 w-7" aria-hidden="true" />
      </button>

      <HeroActionsModal
        open={Boolean(detailsJob)}
        job={detailsJob}
        items={detailsJob ? getJobCarouselItems(detailsJob) : []}
        onClose={() => setDetailsJob(null)}
        onRepeat={(url) => void onRetryJob(url)}
      />
    </div>
  );
}
