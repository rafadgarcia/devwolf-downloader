import { useState, type FormEvent, type KeyboardEvent } from 'react';
import {
  ArrowLeft,
  Check,
  ClipboardPaste,
  Download,
  Film,
  Sparkles,
  X,
} from 'lucide-react';
import type { FormatOption, JobResponse, QualityOption, UserSettings } from '../types/api';
import { PLATFORM_META } from '../utils/format';
import { parseAndValidateUrl } from '../utils/url';
import { JobCard } from './JobCard';
import { OnboardingBanner } from './OnboardingBanner';

interface HomeViewProps {
  urlInput: string;
  onUrlInputChange: (value: string) => void;
  onUserEditingChange: (editing: boolean) => void;
  selectedFormat: FormatOption;
  selectedQuality: QualityOption;
  onFormatChange: (format: FormatOption) => void;
  onQualityChange: (quality: QualityOption) => void;
  settings: UserSettings;
  isNativeAndroid: boolean;
  onboardingDone: boolean;
  onOnboardingChoice: (enableAuto: boolean) => void;
  onToggleAutoDownload: () => void;
  onToggleStatusMode: () => void;
  onExplicitPaste: () => Promise<void>;
  onSubmitManualDownload: (rawUrl: string) => Promise<void>;
  onCancelJob: (jobId: string) => Promise<void>;
  onRetryJob: (url: string) => Promise<void>;
  onReturnToSourceApp?: () => Promise<void>;
  onOpenSettings?: () => void;
  showReturnToSourceApp?: boolean;
  clipboardGuidance: string | null;
  statusNotice: string | null;
  submitting: boolean;
  activeJob: JobResponse | null;
  engineSwitchNotice: string | null;
  connectionMode: 'local' | 'idle';
}

export function HomeView({
  urlInput,
  onUrlInputChange,
  onUserEditingChange,
  selectedFormat,
  selectedQuality,
  onFormatChange,
  onQualityChange,
  settings,
  isNativeAndroid,
  onboardingDone,
  onOnboardingChoice,
  onToggleAutoDownload,
  onToggleStatusMode,
  onExplicitPaste,
  onSubmitManualDownload,
  onCancelJob,
  onRetryJob,
  onReturnToSourceApp,
  onOpenSettings,
  showReturnToSourceApp,
  clipboardGuidance,
  statusNotice,
  submitting,
  activeJob,
  engineSwitchNotice,
  connectionMode,
}: HomeViewProps) {
  const [submitError, setSubmitError] = useState<string | null>(null);
  const trimmed = urlInput.trim();
  const parsedPreview = trimmed ? parseAndValidateUrl(trimmed) : null;
  const hasInvalidNonEmptyUrl = Boolean(trimmed) && !parsedPreview;

  const handleSubmit = async (e?: FormEvent) => {
    e?.preventDefault();
    setSubmitError(null);
    if (!trimmed) {
      setSubmitError('Cole ou digite uma URL para baixar.');
      return;
    }
    if (!parsedPreview) {
      setSubmitError('URL inválida ou plataforma não suportada pelo Devwolf Downloader.');
      return;
    }
    try {
      await onSubmitManualDownload(parsedPreview.normalizedUrl);
    } catch (err) {
      setSubmitError(err instanceof Error ? err.message : 'Erro ao iniciar download.');
    }
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      void handleSubmit();
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between pt-1">
        <p className="text-sm font-medium tracking-tight text-zinc-600 dark:text-zinc-300">
          Copiou. Abriu. Baixou.
        </p>
        <span className="rounded-lg border border-zinc-200 bg-white px-2.5 py-1 text-[11px] font-medium text-zinc-600 dark:border-zinc-800 dark:bg-[#1C1C20] dark:text-zinc-300">
          Execução: Local (Android)
        </span>
      </div>

      {isNativeAndroid && showReturnToSourceApp && onReturnToSourceApp && (
        <div className="flex items-center justify-between gap-2 rounded-2xl border border-[#A3E635]/30 bg-[#A3E635]/10 px-3.5 py-2.5">
          <span className="text-xs font-medium text-zinc-800 dark:text-zinc-200">
            Link recebido por compartilhamento
          </span>
          <button
            type="button"
            onClick={() => void onReturnToSourceApp()}
            className="inline-flex min-h-[44px] shrink-0 items-center gap-1.5 rounded-xl bg-[#A3E635] px-3 text-xs font-semibold text-zinc-950 transition hover:bg-[#84CC16]"
          >
            <ArrowLeft className="h-3.5 w-3.5" aria-hidden="true" />
            <span>Voltar ao app de origem</span>
          </button>
        </div>
      )}

      {!onboardingDone && <OnboardingBanner onChoose={onOnboardingChoice} />}

      <form
        onSubmit={(e) => void handleSubmit(e)}
        className="rounded-2xl border border-zinc-200 bg-white p-4 shadow-sm dark:border-zinc-800 dark:bg-[#1C1C20]"
      >
        <div className="flex gap-2">
          <div className="relative flex-1">
            <input
              type="url"
              inputMode="url"
              aria-label="Link da mídia"
              placeholder="Cole o link aqui…"
              value={urlInput}
              onFocus={() => onUserEditingChange(true)}
              onBlur={() => onUserEditingChange(false)}
              onChange={(e) => {
                setSubmitError(null);
                onUrlInputChange(e.target.value);
              }}
              onKeyDown={handleKeyDown}
              className="min-h-[48px] w-full rounded-xl border border-zinc-300 bg-zinc-50 pl-3.5 pr-11 text-sm text-zinc-900 placeholder-zinc-400 outline-none transition focus:border-[#A3E635] dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-100 dark:placeholder-zinc-500"
            />
            {urlInput && (
              <button
                type="button"
                onClick={() => {
                  setSubmitError(null);
                  onUrlInputChange('');
                }}
                aria-label="Limpar campo"
                className="absolute right-1 top-1/2 inline-flex min-h-[44px] min-w-[44px] -translate-y-1/2 items-center justify-center rounded-lg text-zinc-400 transition hover:text-zinc-700 dark:hover:text-zinc-200"
              >
                <X className="h-4 w-4" />
              </button>
            )}
          </div>

          <button
            type="button"
            onClick={() => {
              setSubmitError(null);
              void onExplicitPaste();
            }}
            className="inline-flex min-h-[48px] shrink-0 items-center justify-center gap-1.5 rounded-xl border border-zinc-300 bg-zinc-100 px-3.5 text-sm font-medium text-zinc-800 transition hover:bg-zinc-200 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200 dark:hover:bg-zinc-800"
          >
            <ClipboardPaste className="h-4 w-4" aria-hidden="true" />
            <span>Colar</span>
          </button>
        </div>

        <div className="mt-2.5 flex flex-wrap items-center justify-between gap-2">
          <div className="flex min-h-[24px] items-center gap-2">
            {parsedPreview && (
              <span className="inline-flex items-center gap-1.5 rounded-lg border border-[#A3E635]/30 bg-[#A3E635]/15 px-2.5 py-1 text-xs font-medium text-[#4d7c0f] dark:text-[#A3E635]">
                <Check className="h-3.5 w-3.5" aria-hidden="true" />
                <span>{parsedPreview.routeLabel} detectado</span>
              </span>
            )}
            {parsedPreview?.isExperimental && (
              <span className="text-[11px] text-amber-600 dark:text-amber-400">
                (experimental)
              </span>
            )}
            {hasInvalidNonEmptyUrl && (
              <span className="text-xs text-red-500 dark:text-red-400">
                URL inválida ou plataforma não suportada
              </span>
            )}
          </div>

          <div className="flex items-center gap-2">
            <select
              aria-label="Formato"
              value={selectedFormat}
              onChange={(e) => onFormatChange(e.target.value as FormatOption)}
              className="min-h-[44px] rounded-xl border border-zinc-300 bg-zinc-50 px-2.5 text-xs font-medium text-zinc-800 outline-none dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200"
            >
              <option value="auto">Auto</option>
              <option value="video">Vídeo</option>
              <option value="audio">Áudio</option>
              <option value="image">Foto</option>
            </select>

            <select
              aria-label="Qualidade"
              value={selectedQuality}
              onChange={(e) => onQualityChange(e.target.value as QualityOption)}
              className="min-h-[44px] rounded-xl border border-zinc-300 bg-zinc-50 px-2.5 text-xs font-medium text-zinc-800 outline-none dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200"
            >
              <option value="best">Melhor</option>
              <option value="1080p">1080p</option>
              <option value="720p">720p</option>
              <option value="480p">480p</option>
              <option value="audio">Áudio</option>
            </select>
          </div>
        </div>

        {clipboardGuidance && !parsedPreview && (
          <p className="mt-2 text-xs text-zinc-500 dark:text-zinc-400">{clipboardGuidance}</p>
        )}

        {statusNotice && (
          <p className="mt-2 text-xs text-[#4d7c0f] dark:text-[#A3E635]">{statusNotice}</p>
        )}

        {submitError && (
          <div
            role="alert"
            className="mt-2.5 rounded-xl border border-red-500/30 bg-red-500/10 px-3 py-2 text-xs text-red-600 dark:text-red-300"
          >
            {submitError}
          </div>
        )}

        <button
          type="submit"
          disabled={submitting || !parsedPreview}
          className="mt-3 inline-flex min-h-[48px] w-full items-center justify-center gap-2 rounded-xl bg-[#A3E635] px-4 text-sm font-bold tracking-wide text-zinc-950 transition hover:bg-[#84CC16] disabled:cursor-not-allowed disabled:opacity-45"
        >
          <Download className="h-4 w-4" aria-hidden="true" />
          <span>{submitting ? 'INICIANDO…' : 'BAIXAR'}</span>
        </button>
      </form>

      {activeJob && (
        <JobCard
          job={activeJob}
          engineSwitchNotice={engineSwitchNotice}
          connectionMode={connectionMode}
          isNativeAndroid={isNativeAndroid}
          onCancel={(id) => void onCancelJob(id)}
          onRetry={(url) => void onRetryJob(url)}
          onReturnToSourceApp={onReturnToSourceApp}
          onOpenSettings={onOpenSettings}
        />
      )}

      <div className="divide-y divide-zinc-200 rounded-2xl border border-zinc-200 bg-white dark:divide-zinc-800 dark:border-zinc-800 dark:bg-[#1C1C20]">
        <div className="flex items-center justify-between px-4 py-3">
          <div className="flex items-center gap-2.5">
            <Film className="h-4 w-4 text-[#65a30d] dark:text-[#A3E635]" aria-hidden="true" />
            <div>
              <p className="text-sm font-medium text-zinc-900 dark:text-zinc-100">
                Modo Status (WhatsApp)
              </p>
              <p className="text-xs text-zinc-500 dark:text-zinc-400">
                {settings.status_mode_enabled
                  ? settings.status_max_duration_seconds > 0
                    ? `H.264/AAC MP4 • cortes de ${settings.status_max_duration_seconds}s`
                    : 'H.264/AAC MP4 compatível com Status'
                  : 'Converte apenas quando necessário, sem marca d’água'}
              </p>
            </div>
          </div>

          <button
            type="button"
            role="switch"
            aria-label="Modo Status (WhatsApp)"
            aria-checked={settings.status_mode_enabled}
            onClick={onToggleStatusMode}
            className={`inline-flex min-h-[44px] min-w-[68px] items-center justify-center rounded-xl px-3 text-xs font-bold transition ${
              settings.status_mode_enabled
                ? 'bg-[#A3E635] text-zinc-950'
                : 'border border-zinc-300 bg-zinc-100 text-zinc-600 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-400'
            }`}
          >
            {settings.status_mode_enabled ? 'ON' : 'OFF'}
          </button>
        </div>

        <div className="flex items-center justify-between px-4 py-3">
          <div className="flex items-center gap-2.5">
            <Sparkles className="h-4 w-4 text-[#65a30d] dark:text-[#A3E635]" aria-hidden="true" />
            <div>
              <p className="text-sm font-medium text-zinc-900 dark:text-zinc-100">
                Download automático
              </p>
              <p className="text-xs text-zinc-500 dark:text-zinc-400">
                Detecta link ao abrir ou colar
              </p>
            </div>
          </div>

          <button
            type="button"
            role="switch"
            aria-label="Download automático"
            aria-checked={settings.auto_download}
            onClick={onToggleAutoDownload}
            className={`inline-flex min-h-[44px] min-w-[68px] items-center justify-center rounded-xl px-3 text-xs font-bold transition ${
              settings.auto_download
                ? 'bg-[#A3E635] text-zinc-950'
                : 'border border-zinc-300 bg-zinc-100 text-zinc-600 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-400'
            }`}
          >
            {settings.auto_download ? 'ON' : 'OFF'}
          </button>
        </div>
      </div>

      <div className="flex flex-wrap justify-center gap-1.5 pt-1">
        {Object.entries(PLATFORM_META).map(([key, meta]) => (
          <span
            key={key}
            className="rounded-lg border border-zinc-200 px-2 py-0.5 text-[11px] text-zinc-500 dark:border-zinc-800 dark:text-zinc-400"
          >
            {meta.label}
          </span>
        ))}
      </div>
    </div>
  );
}
