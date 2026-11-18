import { Link2, X } from 'lucide-react';
import { useModalDialog } from '../utils/dialogHelpers';
import { PLATFORM_META } from '../utils/format';
import type { ParsedPlatformUrl } from '../utils/url';

interface MultiUrlModalProps {
  candidates: ParsedPlatformUrl[];
  onSelect: (parsed: ParsedPlatformUrl) => void;
  onClose: () => void;
}

export function MultiUrlModal({ candidates, onSelect, onClose }: MultiUrlModalProps) {
  const open = candidates.length > 0;
  const dialogRef = useModalDialog(open, onClose);

  return (
    <dialog
      ref={dialogRef}
      aria-labelledby="multi-url-title"
      {...({ closedby: 'any' } as Record<string, string>)}
      className="w-[calc(100%-2rem)] max-w-md rounded-3xl border border-[#7C5CFF]/25 bg-[#F5F3FF] p-0 text-[#1E1940] shadow-2xl dark:border-[#9382FF]/25 dark:bg-[#191538] dark:text-[#F5F3FF]"
    >
      <div className="p-5">
        <div className="flex items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <Link2 className="h-5 w-5 text-[#7C5CFF] dark:text-[#A78BFA]" aria-hidden="true" />
            <h2 id="multi-url-title" className="text-base font-bold">
              Vários links encontrados
            </h2>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Fechar seleção"
            className="inline-flex min-h-[44px] min-w-[44px] items-center justify-center rounded-xl text-[#5B5482] hover:bg-[#7C5CFF]/10 dark:text-[#A5A1C8] dark:hover:bg-[#7C5CFF]/15"
          >
            <X className="h-5 w-5" aria-hidden="true" />
          </button>
        </div>

        <p className="mt-1.5 text-xs text-[#5B5482] dark:text-[#A5A1C8]">
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
                  className="dw-glass-subtle flex min-h-[52px] w-full flex-col items-start gap-1 rounded-2xl p-3 text-left transition hover:border-[#7C5CFF]"
                >
                  <span
                    className={`inline-flex items-center rounded-md border px-2 py-0.5 text-[11px] font-medium ${meta.badgeClass}`}
                  >
                    {item.routeLabel}
                  </span>
                  <span className="w-full truncate text-xs text-[#4C3F91] dark:text-[#E0DBFF]">
                    {item.normalizedUrl}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      </div>
    </dialog>
  );
}
