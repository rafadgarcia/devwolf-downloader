import { useEffect, useId, useRef, useState } from 'react';
import {
  ArrowLeft,
  Cpu,
  Download,
  HelpCircle,
  Home,
  KeyRound,
  MoreVertical,
  Plus,
  Settings,
  Zap,
} from 'lucide-react';

export type NavTab = 'home' | 'history' | 'engines' | 'settings';

interface HeaderProps {
  activeTab: NavTab;
  onNavigate: (tab: NavTab) => void;
  onOpenAccounts: () => void;
  onOpenAddLink: () => void;
  onOpenHowTo: () => void;
}

const VIEW_TITLES: Record<NavTab, string> = {
  home: 'Devwolf',
  history: 'Baixados',
  engines: 'Motores',
  settings: 'Configurações',
};

export function Header({
  activeTab,
  onNavigate,
  onOpenAccounts,
  onOpenAddLink,
  onOpenHowTo,
}: HeaderProps) {
  const triggerId = useId();
  const menuId = useId();
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const popoverRef = useRef<HTMLDivElement | null>(null);
  const focusGroupRef = useRef<HTMLDivElement | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);

  const supportsCommandFor =
    typeof HTMLButtonElement !== 'undefined' &&
    'commandForElement' in HTMLButtonElement.prototype;
  const supportsPopover =
    typeof HTMLElement !== 'undefined' &&
    Object.prototype.hasOwnProperty.call(HTMLElement.prototype, 'popover');

  const closeMenu = (restoreFocus = false) => {
    const popover = popoverRef.current as (HTMLDivElement & { hidePopover?: () => void }) | null;
    if (supportsPopover && popover && typeof popover.hidePopover === 'function') {
      try {
        if (popover.matches(':popover-open')) {
          popover.hidePopover();
        }
      } catch {
        setMenuOpen(false);
      }
    } else {
      setMenuOpen(false);
    }
    if (restoreFocus) {
      triggerRef.current?.focus({ preventScroll: true });
    }
  };

  useEffect(() => {
    const popover = popoverRef.current;
    if (!popover) return;

    const onToggle = (event: Event) => {
      const toggleEvt = event as Event & { newState?: string };
      setMenuOpen(toggleEvt.newState === 'open');
    };
    popover.addEventListener('toggle', onToggle);
    return () => {
      popover.removeEventListener('toggle', onToggle);
    };
  }, []);

  // Fallback de navegação por teclado para navegadores sem focusgroup nativo
  useEffect(() => {
    const group = focusGroupRef.current;
    if (!group || 'focusGroup' in HTMLElement.prototype) return;

    group.setAttribute('role', 'menu');
    group.querySelectorAll('button:not([role])').forEach((item) => {
      item.setAttribute('role', 'menuitem');
    });

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        closeMenu(true);
        return;
      }
      if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
      const items = [...group.querySelectorAll('button')].filter(
        (item) => !item.disabled && item.getAttribute('aria-disabled') !== 'true'
      );
      if (items.length === 0) return;
      const current = items.indexOf(document.activeElement as HTMLButtonElement);
      const next =
        event.key === 'Home'
          ? 0
          : event.key === 'End'
          ? items.length - 1
          : current < 0
          ? 0
          : (current + (event.key === 'ArrowUp' ? -1 : 1) + items.length) % items.length;
      event.preventDefault();
      event.stopImmediatePropagation();
      items[next].focus({ preventScroll: true });
    };

    group.addEventListener('keydown', onKeyDown, true);
    return () => {
      group.removeEventListener('keydown', onKeyDown, true);
    };
  });

  const handleTriggerClick = () => {
    if (supportsCommandFor) return;
    const popover = popoverRef.current as (HTMLDivElement & { togglePopover?: () => void }) | null;
    if (supportsPopover && popover && typeof popover.togglePopover === 'function') {
      popover.togglePopover();
    } else {
      setMenuOpen((prev) => !prev);
    }
  };

  const handleMenuAction = (action: () => void) => {
    closeMenu(false);
    action();
  };

  return (
    <header className="sticky top-0 z-30 border-b border-[#7C5CFF]/15 bg-[#F5F3FF]/85 pt-[env(safe-area-inset-top)] backdrop-blur-xl dark:border-[#9382FF]/15 dark:bg-[#0D0B1E]/80">
      <div className="mx-auto flex max-w-2xl items-center justify-between px-4 py-2.5">
        {activeTab === 'home' ? (
          <div className="flex items-center gap-2.5">
            <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-[#7C5CFF] to-[#5E43F3] text-white shadow-md shadow-[#5E43F3]/35">
              <Zap className="h-5 w-5 fill-current" aria-hidden="true" />
            </div>
            <h1 className="text-lg font-extrabold tracking-tight text-[#1E1940] dark:text-[#F5F3FF]">
              Devwolf
            </h1>
          </div>
        ) : (
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => onNavigate('home')}
              aria-label="Voltar para o início"
              className="inline-flex min-h-[44px] min-w-[44px] items-center justify-center rounded-xl text-[#4C3F91] transition hover:bg-[#7C5CFF]/10 dark:text-[#C4B5FD] dark:hover:bg-[#7C5CFF]/15"
            >
              <ArrowLeft className="h-5 w-5" aria-hidden="true" />
            </button>
            <h1 className="text-base font-bold tracking-tight text-[#1E1940] dark:text-[#F5F3FF]">
              {VIEW_TITLES[activeTab]}
            </h1>
          </div>
        )}

        <div className="flex items-center gap-1">
          <button
            type="button"
            onClick={onOpenAddLink}
            aria-label="Adicionar link de download"
            title="Adicionar link"
            className="inline-flex min-h-[44px] min-w-[44px] items-center justify-center rounded-xl text-[#5E43F3] transition hover:bg-[#7C5CFF]/12 dark:text-[#C4B5FD] dark:hover:bg-[#7C5CFF]/15"
          >
            <Plus className="h-5 w-5" aria-hidden="true" />
          </button>

          <button
            ref={triggerRef}
            id={triggerId}
            type="button"
            aria-label="Menu de navegação"
            aria-haspopup="menu"
            aria-expanded={menuOpen}
            onClick={handleTriggerClick}
            {...(supportsCommandFor
              ? ({ commandfor: menuId, command: 'toggle-popover' } as Record<string, string>)
              : {})}
            className="inline-flex min-h-[44px] min-w-[44px] items-center justify-center rounded-xl text-[#4C3F91] transition hover:bg-[#7C5CFF]/12 dark:text-[#E0DBFF] dark:hover:bg-[#7C5CFF]/15"
          >
            <MoreVertical className="h-5 w-5" aria-hidden="true" />
          </button>

          {/* Popover imediatamente após o trigger */}
          <div
            ref={popoverRef}
            id={menuId}
            className={`dw-nav-popover ${
              !supportsPopover && menuOpen
                ? 'fixed right-4 top-[calc(3.75rem+env(safe-area-inset-top,0px))] z-50 block'
                : ''
            }`}
            {...(supportsPopover
              ? ({ popover: 'auto' } as Record<string, string>)
              : !menuOpen
              ? { hidden: true }
              : {})}
          >
            <div
              ref={focusGroupRef}
              aria-labelledby={triggerId}
              {...({ focusgroup: 'menu nomemory' } as Record<string, string>)}
              className="dw-glass-card w-60 rounded-2xl p-1.5 text-sm shadow-2xl"
            >
              {activeTab !== 'home' && (
                <button
                  type="button"
                  onClick={() => handleMenuAction(() => onNavigate('home'))}
                  className="min-h-[44px] w-full rounded-xl px-3 py-2 text-left text-sm font-medium text-[#1E1940] transition hover:bg-[#7C5CFF]/12 dark:text-[#F5F3FF] dark:hover:bg-[#7C5CFF]/20"
                >
                  <Home className="h-4 w-4 shrink-0 text-[#7C5CFF] dark:text-[#A78BFA]" aria-hidden="true" />
                  <span>Início</span>
                </button>
              )}

              <button
                type="button"
                onClick={() => handleMenuAction(() => onNavigate('history'))}
                className={`min-h-[44px] w-full rounded-xl px-3 py-2 text-left text-sm font-medium transition ${
                  activeTab === 'history'
                    ? 'bg-[#7C5CFF]/15 text-[#5E43F3] dark:text-[#C4B5FD]'
                    : 'text-[#1E1940] hover:bg-[#7C5CFF]/12 dark:text-[#F5F3FF] dark:hover:bg-[#7C5CFF]/20'
                }`}
              >
                <Download className="h-4 w-4 shrink-0 text-[#7C5CFF] dark:text-[#A78BFA]" aria-hidden="true" />
                <span>Baixados</span>
              </button>

              <button
                type="button"
                onClick={() => handleMenuAction(() => onNavigate('engines'))}
                className={`min-h-[44px] w-full rounded-xl px-3 py-2 text-left text-sm font-medium transition ${
                  activeTab === 'engines'
                    ? 'bg-[#7C5CFF]/15 text-[#5E43F3] dark:text-[#C4B5FD]'
                    : 'text-[#1E1940] hover:bg-[#7C5CFF]/12 dark:text-[#F5F3FF] dark:hover:bg-[#7C5CFF]/20'
                }`}
              >
                <Cpu className="h-4 w-4 shrink-0 text-[#7C5CFF] dark:text-[#A78BFA]" aria-hidden="true" />
                <span>Motores</span>
              </button>

              <button
                type="button"
                onClick={() => handleMenuAction(onOpenAccounts)}
                className="min-h-[44px] w-full rounded-xl px-3 py-2 text-left text-sm font-medium text-[#1E1940] transition hover:bg-[#7C5CFF]/12 dark:text-[#F5F3FF] dark:hover:bg-[#7C5CFF]/20"
              >
                <KeyRound className="h-4 w-4 shrink-0 text-[#7C5CFF] dark:text-[#A78BFA]" aria-hidden="true" />
                <span>Contas e Sessões</span>
              </button>

              <hr className="my-1.5 border-[#7C5CFF]/15 dark:border-[#9382FF]/15" />

              <button
                type="button"
                onClick={() => handleMenuAction(onOpenHowTo)}
                className="min-h-[44px] w-full rounded-xl px-3 py-2 text-left text-sm font-medium text-[#1E1940] transition hover:bg-[#7C5CFF]/12 dark:text-[#F5F3FF] dark:hover:bg-[#7C5CFF]/20"
              >
                <HelpCircle className="h-4 w-4 shrink-0 text-[#7C5CFF] dark:text-[#A78BFA]" aria-hidden="true" />
                <span>Como baixar</span>
              </button>

              <button
                type="button"
                onClick={() => handleMenuAction(() => onNavigate('settings'))}
                className={`min-h-[44px] w-full rounded-xl px-3 py-2 text-left text-sm font-medium transition ${
                  activeTab === 'settings'
                    ? 'bg-[#7C5CFF]/15 text-[#5E43F3] dark:text-[#C4B5FD]'
                    : 'text-[#1E1940] hover:bg-[#7C5CFF]/12 dark:text-[#F5F3FF] dark:hover:bg-[#7C5CFF]/20'
                }`}
              >
                <Settings className="h-4 w-4 shrink-0 text-[#7C5CFF] dark:text-[#A78BFA]" aria-hidden="true" />
                <span>Configurações</span>
              </button>
            </div>
          </div>
        </div>
      </div>
    </header>
  );
}
