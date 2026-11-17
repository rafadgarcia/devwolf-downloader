import { LogIn, LogOut, Moon, Smartphone, Sun, Zap } from 'lucide-react';
import type { ThemeMode } from '../types/api';

interface HeaderProps {
  username: string | null;
  isNativeAndroid: boolean;
  vpsAuthenticated: boolean;
  theme: ThemeMode;
  onToggleTheme: () => void;
  onRequestVpsLogin: () => void;
  onLogout: () => void;
}

export function Header({
  username,
  isNativeAndroid,
  vpsAuthenticated,
  theme,
  onToggleTheme,
  onRequestVpsLogin,
  onLogout,
}: HeaderProps) {
  const subtitle = vpsAuthenticated
    ? username || 'VPS conectada'
    : isNativeAndroid
    ? 'Modo Local • Offline da VPS'
    : username || 'Conectado';

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
              {isNativeAndroid && <Smartphone className="h-3 w-3 text-[#65a30d] dark:text-[#A3E635]" aria-hidden="true" />}
              <span>{subtitle}</span>
            </p>
          </div>
        </div>

        <div className="flex items-center gap-1">
          <button
            type="button"
            onClick={onToggleTheme}
            aria-label={theme === 'dark' ? 'Ativar tema claro' : 'Ativar tema escuro'}
            title={theme === 'dark' ? 'Tema claro' : 'Tema escuro'}
            className="inline-flex min-h-[44px] min-w-[44px] items-center justify-center rounded-xl text-zinc-600 transition hover:bg-zinc-200/70 dark:text-zinc-300 dark:hover:bg-[#1C1C20]"
          >
            {theme === 'dark' ? <Sun className="h-5 w-5" /> : <Moon className="h-5 w-5" />}
          </button>

          {vpsAuthenticated ? (
            <button
              type="button"
              onClick={onLogout}
              aria-label="Sair da conta VPS"
              title="Sair da VPS"
              className="inline-flex min-h-[44px] min-w-[44px] items-center justify-center rounded-xl text-zinc-600 transition hover:bg-red-500/10 hover:text-red-500 dark:text-zinc-300 dark:hover:text-red-400"
            >
              <LogOut className="h-5 w-5" />
            </button>
          ) : (
            <button
              type="button"
              onClick={onRequestVpsLogin}
              aria-label="Conectar à VPS"
              title="Conectar à VPS"
              className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl border border-zinc-300 px-3 text-xs font-medium text-zinc-700 transition hover:border-[#A3E635] dark:border-zinc-700 dark:text-zinc-200"
            >
              <LogIn className="h-4 w-4" aria-hidden="true" />
              <span>VPS</span>
            </button>
          )}
        </div>
      </div>
    </header>
  );
}
