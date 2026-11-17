import { Link2, X } from 'lucide-react';
import { PLATFORM_META } from '../utils/format';
import type { ParsedPlatformUrl } from '../utils/url';

interface MultiUrlModalProps {
  candidates: ParsedPlatformUrl[];
  onSelect: (parsed: ParsedPlatformUrl) => void;
  onClose: () => void;
}

export function MultiUrlModal({ candidates, onSelect, onClose }: MultiUrlModalProps) {
  if (candidates.length === 0) return null;

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="multi-url-title"
      className="fixed inset-0 z-50 flex items-end justify-center bg-black/70 p-4 sm:items-center"
    >
      <div className="w-full max-w-md rounded-2xl border border-zinc-200 bg-white p-5 shadow-2xl dark:border-zinc-800 dark:bg-[#1C1C20]">
        <div className="flex items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <Link2 className="h-5 w-5 text-[#65a30d] dark:text-[#A3E635]" aria-hidden="true" />
            <h2 id="multi-url-title" className="text-base font-semibold text-zinc-900 dark:text-zinc-100">
              Vários links encontrados
            </h2>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Fechar seleção"
            className="inline-flex min-h-[44px] min-w-[44px] items-center justify-center rounded-xl text-zinc-500 hover:bg-zinc-100 dark:text-zinc-400 dark:hover:bg-zinc-800"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        <p className="mt-1.5 text-xs text-zinc-600 dark:text-zinc-400">
          Escolha qual link deseja importar:
        </p>

        <ul className="mt-3 max-h-72 space-y-2 overflow-y-auto">
          {candidates.map((item) => {
            const meta = PLATFORM_META[item.platform];
            return (
              <li key={item.normalizedUrl}>
                <button
                  type="button"
                  onClick={() => onSelect(item)}
                  className="flex min-h-[52px] w-full flex-col items-start gap-1 rounded-xl border border-zinc-200 bg-zinc-50 p-3 text-left transition hover:border-[#A3E635] dark:border-zinc-800 dark:bg-[#101012] dark:hover:border-[#A3E635]"
                >
                  <span
                    className={`inline-flex items-center rounded-md border px-2 py-0.5 text-[11px] font-medium ${meta.badgeClass}`}
                  >
                    {item.routeLabel}
                  </span>
                  <span className="w-full truncate text-xs text-zinc-700 dark:text-zinc-300">
                    {item.normalizedUrl}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      </div>
    </div>
  );
}
