import { useState, type FormEvent, type KeyboardEvent } from 'react';
import { Check, ClipboardPaste, Download, X } from 'lucide-react';
import type { FormatOption, QualityOption } from '../types/api';
import { useModalDialog } from '../utils/dialogHelpers';
import { parseAndValidateUrl } from '../utils/url';

interface AddLinkModalProps {
  open: boolean;
  urlInput: string;
  onUrlInputChange: (value: string) => void;
  onUserEditingChange: (editing: boolean) => void;
  selectedFormat: FormatOption;
  selectedQuality: QualityOption;
  onFormatChange: (format: FormatOption) => void;
  onQualityChange: (quality: QualityOption) => void;
  clipboardGuidance: string | null;
  statusNotice: string | null;
  submitting: boolean;
  onExplicitPaste: () => Promise<void>;
  onSubmitDownload: (rawUrl: string) => Promise<void>;
  onClose: () => void;
}

export function AddLinkModal({
  open,
  urlInput,
  onUrlInputChange,
  onUserEditingChange,
  selectedFormat,
  selectedQuality,
  onFormatChange,
  onQualityChange,
  clipboardGuidance,
  statusNotice,
  submitting,
  onExplicitPaste,
  onSubmitDownload,
  onClose,
}: AddLinkModalProps) {
  const [submitError, setSubmitError] = useState<string | null>(null);
  const dialogRef = useModalDialog(open, onClose);

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
      await onSubmitDownload(parsedPreview.normalizedUrl);
      onClose();
    } catch (err) {
      setSubmitError(err instanceof Error ? err.message : 'Erro ao iniciar download.');
    }
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      void handleSubmit();
    }
  };

  return (
    <dialog
      ref={dialogRef}
      aria-labelledby="add-link-title"
      {...({ closedby: 'any' } as Record<string, string>)}
      className="w-[calc(100%-2rem)] max-w-md rounded-3xl border border-[#7C5CFF]/25 bg-[#F5F3FF] p-0 text-[#1E1940] shadow-2xl dark:border-[#9382FF]/25 dark:bg-[#191538] dark:text-[#F5F3FF]"
    >
      <form onSubmit={(e) => void handleSubmit(e)} className="p-5">
        <div className="flex items-center justify-between gap-2">
          <h2 id="add-link-title" className="text-base font-bold tracking-tight">
            Adicionar link de download
          </h2>

          <button
            type="button"
            onClick={onClose}
            aria-label="Fechar modal"
            className="inline-flex min-h-[44px] min-w-[44px] items-center justify-center rounded-xl text-[#5B5482] transition hover:bg-[#7C5CFF]/10 dark:text-[#A5A1C8] dark:hover:bg-[#7C5CFF]/15"
          >
            <X className="h-5 w-5" aria-hidden="true" />
          </button>
        </div>

        <div className="relative mt-4">
          <textarea
            rows={3}
            inputMode="url"
            autoFocus={open}
            aria-label="Link da mídia"
            placeholder="Cole o link do Instagram, TikTok, YouTube, Threads, X, Reddit ou Pinterest…"
            value={urlInput}
            onFocus={() => onUserEditingChange(true)}
            onBlur={() => onUserEditingChange(false)}
            onChange={(e) => {
              setSubmitError(null);
              onUrlInputChange(e.target.value);
            }}
            onKeyDown={handleKeyDown}
            className="w-full resize-none rounded-2xl border border-[#7C5CFF]/25 bg-white/80 py-3.5 pl-4 pr-14 text-sm text-[#1E1940] placeholder-[#7D75A8] outline-none transition focus:border-[#7C5CFF] dark:border-[#9382FF]/25 dark:bg-[#0D0B1E]/75 dark:text-[#F5F3FF] dark:placeholder-[#7A74A6] dark:focus:border-[#A78BFA]"
          />
          {urlInput && (
            <button
              type="button"
              onClick={() => {
                setSubmitError(null);
                onUrlInputChange('');
              }}
              aria-label="Limpar link"
              className="absolute right-3 top-1/2 inline-flex h-9 w-9 min-h-[36px] min-w-[36px] [min-block-size:36px] -translate-y-1/2 aspect-square shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-[#7C5CFF] to-[#5E43F3] text-white shadow transition after:absolute after:-inset-1 after:content-[''] hover:opacity-90"
            >
              <X className="h-4 w-4" aria-hidden="true" />
            </button>
          )}
        </div>

        <div className="mt-3 flex flex-wrap items-center justify-between gap-2">
          <div className="flex min-h-[40px] items-center gap-2">
            {parsedPreview && (
              <span className="inline-flex min-h-[40px] [min-block-size:40px] items-center gap-1.5 rounded-xl border border-[#7C5CFF]/35 bg-[#7C5CFF]/15 px-3 text-xs font-semibold text-[#5E43F3] dark:text-[#C4B5FD]">
                <Check className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                <span>{parsedPreview.routeLabel} detectado</span>
              </span>
            )}
            {parsedPreview?.isExperimental && (
              <span className="text-[11px] text-amber-500 dark:text-amber-300">
                (experimental)
              </span>
            )}
            {hasInvalidNonEmptyUrl && (
              <span className="text-xs text-rose-600 dark:text-rose-300">
                URL inválida ou plataforma não suportada
              </span>
            )}
          </div>

          <div className="flex items-center gap-2">
            <select
              aria-label="Formato"
              value={selectedFormat}
              onChange={(e) => onFormatChange(e.target.value as FormatOption)}
              className="min-h-[40px] [min-block-size:40px] rounded-xl border border-[#7C5CFF]/25 bg-white/80 px-2.5 text-xs font-medium text-[#1E1940] outline-none dark:border-[#9382FF]/25 dark:bg-[#0D0B1E]/80 dark:text-[#E0DBFF]"
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
              className="min-h-[40px] [min-block-size:40px] rounded-xl border border-[#7C5CFF]/25 bg-white/80 px-2.5 text-xs font-medium text-[#1E1940] outline-none dark:border-[#9382FF]/25 dark:bg-[#0D0B1E]/80 dark:text-[#E0DBFF]"
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
          <p className="mt-2 text-xs text-[#5B5482] dark:text-[#A5A1C8]">{clipboardGuidance}</p>
        )}

        {statusNotice && (
          <p className="mt-2 text-xs text-[#5E43F3] dark:text-[#C4B5FD]">{statusNotice}</p>
        )}

        {submitError && (
          <div
            role="alert"
            className="mt-2.5 rounded-xl border border-rose-500/30 bg-rose-500/10 px-3 py-2 text-xs text-rose-600 dark:text-rose-300"
          >
            {submitError}
          </div>
        )}

        <div className="mt-4 space-y-2">
          <button
            type="submit"
            disabled={submitting || !parsedPreview}
            className="dw-btn-primary inline-flex min-h-[48px] w-full items-center justify-center gap-2 rounded-2xl px-4 text-sm font-bold tracking-wide transition disabled:cursor-not-allowed disabled:opacity-45"
          >
            <Download className="h-4 w-4" aria-hidden="true" />
            <span>{submitting ? 'INICIANDO…' : 'BAIXAR'}</span>
          </button>

          <button
            type="button"
            onClick={() => {
              setSubmitError(null);
              void onExplicitPaste();
            }}
            className="inline-flex min-h-[44px] w-full items-center justify-center gap-2 rounded-2xl border border-[#7C5CFF]/25 bg-[#7C5CFF]/10 px-4 text-xs font-bold tracking-wide text-[#5E43F3] transition hover:bg-[#7C5CFF]/20 dark:border-[#9382FF]/25 dark:text-[#C4B5FD]"
          >
            <ClipboardPaste className="h-4 w-4" aria-hidden="true" />
            <span>COLAR DA ÁREA DE TRANSFERÊNCIA</span>
          </button>
        </div>
      </form>
    </dialog>
  );
}
