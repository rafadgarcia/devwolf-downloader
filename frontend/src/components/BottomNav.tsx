import { Cpu, History, Home, Settings } from 'lucide-react';

export type NavTab = 'home' | 'history' | 'engines' | 'settings';

interface BottomNavProps {
  activeTab: NavTab;
  onSelectTab: (tab: NavTab) => void;
}

const TABS: { id: NavTab; label: string; Icon: typeof Home }[] = [
  { id: 'home', label: 'Início', Icon: Home },
  { id: 'history', label: 'Histórico', Icon: History },
  { id: 'engines', label: 'Motores', Icon: Cpu },
  { id: 'settings', label: 'Ajustes', Icon: Settings },
];

export function BottomNav({ activeTab, onSelectTab }: BottomNavProps) {
  return (
    <nav
      aria-label="Navegação principal"
      className="fixed bottom-0 left-0 right-0 z-30 border-t border-zinc-200 bg-white/95 pb-[env(safe-area-inset-bottom)] backdrop-blur-md dark:border-zinc-800 dark:bg-[#101012]/95"
    >
      <div className="mx-auto grid max-w-2xl grid-cols-4 gap-1 px-2 py-1.5">
        {TABS.map(({ id, label, Icon }) => {
          const isActive = activeTab === id;
          return (
            <button
              key={id}
              type="button"
              onClick={() => onSelectTab(id)}
              aria-current={isActive ? 'page' : undefined}
              className={`flex min-h-[48px] flex-col items-center justify-center gap-1 rounded-xl px-2 py-1.5 text-xs font-medium transition ${
                isActive
                  ? 'bg-[#A3E635]/15 text-[#4d7c0f] dark:text-[#A3E635]'
                  : 'text-zinc-500 hover:bg-zinc-100 dark:text-zinc-400 dark:hover:bg-[#1C1C20]'
              }`}
            >
              <Icon className="h-5 w-5" aria-hidden="true" />
              <span>{label}</span>
            </button>
          );
        })}
      </div>
    </nav>
  );
}
