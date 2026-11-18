import { ClipboardCheck } from 'lucide-react';

interface OnboardingBannerProps {
  onChoose: (enableAuto: boolean) => void;
}

export function OnboardingBanner({ onChoose }: OnboardingBannerProps) {
  return (
    <section
      aria-label="Configuração inicial de clipboard"
      className="dw-glass-card rounded-3xl p-4"
    >
      <div className="flex items-start gap-3">
        <div className="mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-[#7C5CFF]/20 text-[#5E43F3] dark:text-[#C4B5FD]">
          <ClipboardCheck className="h-5 w-5" aria-hidden="true" />
        </div>
        <div className="flex-1">
          <h2 className="text-sm font-semibold text-[#1E1940] dark:text-[#F5F3FF]">
            Detectar links ao abrir o Devwolf?
          </h2>
          <p className="mt-1 text-xs leading-relaxed text-[#5B5482] dark:text-[#A5A1C8]">
            Quando permitido pelo aparelho, o Devwolf lê o link copiado ao abrir o app e inicia o
            download automaticamente — tudo processado diretamente no dispositivo.
          </p>
          <div className="mt-3 flex flex-wrap gap-2">
            <button
              type="button"
              onClick={() => onChoose(true)}
              className="dw-btn-primary min-h-[44px] rounded-xl px-4 text-xs font-semibold transition"
            >
              Ativar download automático
            </button>
            <button
              type="button"
              onClick={() => onChoose(false)}
              className="min-h-[44px] rounded-xl border border-[#7C5CFF]/25 bg-[#7C5CFF]/10 px-4 text-xs font-medium text-[#4C3F91] transition hover:bg-[#7C5CFF]/20 dark:text-[#E0DBFF]"
            >
              Usar apenas manual
            </button>
          </div>
        </div>
      </div>
    </section>
  );
}
