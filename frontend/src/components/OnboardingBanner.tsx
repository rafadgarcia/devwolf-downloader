import { ClipboardCheck } from 'lucide-react';

interface OnboardingBannerProps {
  onChoose: (enableAuto: boolean) => void;
}

export function OnboardingBanner({ onChoose }: OnboardingBannerProps) {
  return (
    <section
      aria-label="Configuração inicial de clipboard"
      className="rounded-2xl border border-[#A3E635]/30 bg-[#A3E635]/10 p-4 dark:bg-[#1C1C20]"
    >
      <div className="flex items-start gap-3">
        <div className="mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-[#A3E635]/20 text-[#4d7c0f] dark:text-[#A3E635]">
          <ClipboardCheck className="h-5 w-5" aria-hidden="true" />
        </div>
        <div className="flex-1">
          <h2 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
            Detectar links ao abrir o Devwolf Downloader?
          </h2>
          <p className="mt-1 text-xs leading-relaxed text-zinc-600 dark:text-zinc-400">
            Quando permitido pelo dispositivo ou navegador, o Devwolf Downloader lê o link copiado ao
            focar o app, normaliza localmente e inicia o download automaticamente. Texto comum ou
            desconhecido nunca é enviado ao servidor.
          </p>
          <div className="mt-3 flex flex-wrap gap-2">
            <button
              type="button"
              onClick={() => onChoose(true)}
              className="min-h-[44px] rounded-xl bg-[#A3E635] px-4 text-xs font-semibold text-zinc-950 transition hover:bg-[#84CC16]"
            >
              Ativar download automático
            </button>
            <button
              type="button"
              onClick={() => onChoose(false)}
              className="min-h-[44px] rounded-xl border border-zinc-300 bg-white px-4 text-xs font-medium text-zinc-700 transition hover:bg-zinc-100 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-300 dark:hover:bg-zinc-800"
            >
              Usar apenas manual
            </button>
          </div>
        </div>
      </div>
    </section>
  );
}
