import { ClipboardCopy, Download, Share2, Sparkles, X } from 'lucide-react';
import { useModalDialog } from '../utils/dialogHelpers';

interface HowToDownloadModalProps {
  open: boolean;
  onClose: () => void;
}

export function HowToDownloadModal({ open, onClose }: HowToDownloadModalProps) {
  const dialogRef = useModalDialog(open, onClose);

  return (
    <dialog
      ref={dialogRef}
      aria-labelledby="howto-title"
      {...({ closedby: 'any' } as Record<string, string>)}
      className="w-[calc(100%-2rem)] max-w-md rounded-3xl border border-[#7C5CFF]/25 bg-[#F5F3FF] p-0 text-[#1E1940] shadow-2xl dark:border-[#9382FF]/25 dark:bg-[#191538] dark:text-[#F5F3FF]"
    >
      <div className="p-5">
        <div className="flex items-center justify-between gap-2">
          <h2 id="howto-title" className="text-base font-bold tracking-tight">
            Como baixar?
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Fechar ajuda"
            className="inline-flex min-h-[44px] min-w-[44px] items-center justify-center rounded-xl text-[#5B5482] transition hover:bg-[#7C5CFF]/10 dark:text-[#A5A1C8] dark:hover:bg-[#7C5CFF]/15"
          >
            <X className="h-5 w-5" aria-hidden="true" />
          </button>
        </div>

        <ol className="mt-4 space-y-3 text-xs">
          <li className="dw-glass-subtle flex items-start gap-3 rounded-2xl p-3">
            <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-[#7C5CFF]/20 text-[#5E43F3] dark:text-[#C4B5FD]">
              <Share2 className="h-4 w-4" aria-hidden="true" />
            </div>
            <div>
              <p className="font-semibold">1. Copie ou Compartilhe o link</p>
              <p className="mt-0.5 leading-relaxed text-[#5B5482] dark:text-[#A5A1C8]">
                No Instagram, TikTok, YouTube, Threads, X, Reddit ou Pinterest, copie o link ou
                toque em <strong>Compartilhar → Devwolf Downloader</strong>.
              </p>
            </div>
          </li>

          <li className="dw-glass-subtle flex items-start gap-3 rounded-2xl p-3">
            <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-[#7C5CFF]/20 text-[#5E43F3] dark:text-[#C4B5FD]">
              <ClipboardCopy className="h-4 w-4" aria-hidden="true" />
            </div>
            <div>
              <p className="font-semibold">2. Toque no botão + ou ative o Automático</p>
              <p className="mt-0.5 leading-relaxed text-[#5B5482] dark:text-[#A5A1C8]">
                Toque no botão <strong>+</strong> para colar manualmente, ou ative o{' '}
                <strong>Download automático</strong> em Configurações para baixar assim que abrir o
                app.
              </p>
            </div>
          </li>

          <li className="dw-glass-subtle flex items-start gap-3 rounded-2xl p-3">
            <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-[#7C5CFF]/20 text-[#5E43F3] dark:text-[#C4B5FD]">
              <Download className="h-4 w-4" aria-hidden="true" />
            </div>
            <div>
              <p className="font-semibold">3. Salvo direto na Galeria</p>
              <p className="mt-0.5 leading-relaxed text-[#5B5482] dark:text-[#A5A1C8]">
                Fotos e vídeos vão para o álbum <strong>Devwolf Downloader</strong> na sua Galeria,
                prontos para abrir ou compartilhar.
              </p>
            </div>
          </li>
        </ol>

        <div className="mt-3 flex items-start gap-2 rounded-2xl border border-[#7C5CFF]/20 bg-[#7C5CFF]/10 p-3 text-xs text-[#4C3F91] dark:text-[#C4B5FD]">
          <Sparkles className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
          <span>
            Dica: ative o <strong>Modo Status (WhatsApp)</strong> em <strong>Configurações</strong>{' '}
            para compatibilidade automática H.264/AAC e cortes de 30s/45s/60s.
          </span>
        </div>

        <button
          type="button"
          onClick={onClose}
          className="dw-btn-primary mt-4 inline-flex min-h-[46px] w-full items-center justify-center rounded-2xl px-4 text-sm font-bold"
        >
          Entendi
        </button>
      </div>
    </dialog>
  );
}
