import { Moon, Smartphone, Sun, Zap } from 'lucide-react';
import type { ThemeMode } from '../types/api';

interface HeaderProps {
  isNativeAndroid: boolean;
  theme: ThemeMode;
  onToggleTheme: () => void;
}

export function Header({ isNativeAndroid, theme, onToggleTheme }: HeaderProps) {
  const subtitle = isNativeAndroid
    ? '100% Local • Android Nativo'
    : '100% Local • Preview Web';

  return (
    <header className="sticky top-0 z-30 border-b border-zinc-200/80 bg-white/90 pt-[env(safe-area-inset-top)] backdrop-blur-md dark:border-zinc-800/80 dark:bg-[#101012]/90">
      <div className="mx-auto flex max-w-2xl items-center justify-between px-4 py-2.5">
        <div className="flex items-center gap-2.5">
          <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-[#A3E635]/15 text-[#65a30d] dark:text-[#A3E635]">
            <Zap className="h-5 w-5 fill-current" aria-hidden="true" />
          </div>
          <div>
            <h1 className="text-base font-bold tracking-tight text-zinc-900 dark:text-zinc-100">
              Devwolf Downloader
            </h1>
            <p className="flex items-center gap-1 text-[11px] leading-none text-zinc-500 dark:text-zinc-400">
              <Smartphone
                className="h-3 w-3 text-[#65a30d] dark:text-[#A3E635]"
                aria-hidden="true"
              />
              <span>{subtitle}</span>
            </p>
          </div>
        </div>

        <button
          type="button"
          onClick={onToggleTheme}
          aria-label={theme === 'dark' ? 'Ativar tema claro' : 'Ativar tema escuro'}
          title={theme === 'dark' ? 'Tema claro' : 'Tema escuro'}
          className="inline-flex min-h-[44px] min-w-[44px] items-center justify-center rounded-xl text-zinc-600 transition hover:bg-zinc-200/70 dark:text-zinc-300 dark:hover:bg-[#1C1C20]"
        >
          {theme === 'dark' ? <Sun className="h-5 w-5" /> : <Moon className="h-5 w-5" />}
        </button>
      </div>
    </header>
  );
}
