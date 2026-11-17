import { useState, type FormEvent } from 'react';
import { Globe, Lock, Share2, Smartphone, Zap } from 'lucide-react';
import type { PendingSharePayload } from '../types/api';

interface LoginScreenProps {
  pendingShare: PendingSharePayload | null;
  isNativeAndroid: boolean;
  initialVpsUrl: string;
  onLogin: (username: string, password: string, vpsUrl?: string) => Promise<void>;
  onContinueOffline?: () => void;
}

export function LoginScreen({
  pendingShare,
  isNativeAndroid,
  initialVpsUrl,
  onLogin,
  onContinueOffline,
}: LoginScreenProps) {
  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('');
  const [vpsUrl, setVpsUrl] = useState(initialVpsUrl);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!password.trim()) {
      setError('Informe a senha para continuar.');
      return;
    }
    if (isNativeAndroid && !vpsUrl.trim()) {
      setError('Informe o endereço da sua VPS (ex.: https://downloader.seudominio.com) ou use o Modo Local.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await onLogin(username.trim() || 'admin', password, vpsUrl.trim() || undefined);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Falha ao autenticar.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main className="flex min-h-screen flex-col items-center justify-center bg-[#F4F4F5] px-4 py-8 dark:bg-[#101012]">
      <div className="w-full max-w-sm rounded-2xl border border-zinc-200 bg-white p-6 shadow-xl dark:border-zinc-800 dark:bg-[#1C1C20]">
        <div className="mb-6 flex flex-col items-center text-center">
          <div className="mb-3 flex h-12 w-12 items-center justify-center rounded-2xl bg-[#A3E635]/15 text-[#65a30d] dark:text-[#A3E635]">
            <Zap className="h-6 w-6 fill-current" aria-hidden="true" />
          </div>
          <h1 className="text-xl font-bold text-zinc-900 dark:text-zinc-100">Devwolf Downloader</h1>
          <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">
            Copiou. Abriu. Baixou.
          </p>
        </div>

        {pendingShare && (
          <div className="mb-4 flex items-start gap-2.5 rounded-xl border border-[#A3E635]/30 bg-[#A3E635]/10 p-3 text-xs text-zinc-800 dark:text-zinc-200">
            <Share2 className="mt-0.5 h-4 w-4 shrink-0 text-[#65a30d] dark:text-[#A3E635]" />
            <span>
              Link compartilhado preservado. Entre na sua VPS ou continue no Modo Local para baixar.
            </span>
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-4">
          {isNativeAndroid && (
            <div>
              <label
                htmlFor="login-vps-url"
                className="mb-1.5 flex items-center gap-1.5 text-xs font-medium text-zinc-600 dark:text-zinc-400"
              >
                <Globe className="h-3.5 w-3.5" aria-hidden="true" />
                <span>Endereço da VPS (opcional para Modo Local)</span>
              </label>
              <input
                id="login-vps-url"
                type="url"
                inputMode="url"
                placeholder="https://downloader.seudominio.com"
                value={vpsUrl}
                onChange={(e) => setVpsUrl(e.target.value)}
                className="min-h-[44px] w-full rounded-xl border border-zinc-300 bg-zinc-50 px-3.5 text-sm text-zinc-900 outline-none transition focus:border-[#A3E635] dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-100"
              />
            </div>
          )}

          <div>
            <label
              htmlFor="login-username"
              className="mb-1.5 block text-xs font-medium text-zinc-600 dark:text-zinc-400"
            >
              Usuário
            </label>
            <input
              id="login-username"
              type="text"
              autoComplete="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              className="min-h-[44px] w-full rounded-xl border border-zinc-300 bg-zinc-50 px-3.5 text-sm text-zinc-900 outline-none transition focus:border-[#A3E635] dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-100"
            />
          </div>

          <div>
            <label
              htmlFor="login-password"
              className="mb-1.5 block text-xs font-medium text-zinc-600 dark:text-zinc-400"
            >
              Senha
            </label>
            <input
              id="login-password"
              type="password"
              autoComplete="current-password"
              placeholder="••••••••"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="min-h-[44px] w-full rounded-xl border border-zinc-300 bg-zinc-50 px-3.5 text-sm text-zinc-900 outline-none transition focus:border-[#A3E635] dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-100"
            />
          </div>

          {error && (
            <div
              role="alert"
              className="rounded-xl border border-red-500/30 bg-red-500/10 px-3 py-2.5 text-xs text-red-600 dark:text-red-300"
            >
              {error}
            </div>
          )}

          <button
            type="submit"
            disabled={submitting}
            className="inline-flex min-h-[48px] w-full items-center justify-center gap-2 rounded-xl bg-[#A3E635] px-4 text-sm font-semibold text-zinc-950 transition hover:bg-[#84CC16] disabled:cursor-not-allowed disabled:opacity-60"
          >
            <Lock className="h-4 w-4" aria-hidden="true" />
            <span>{submitting ? 'Entrando…' : 'Entrar na VPS'}</span>
          </button>
        </form>

        {isNativeAndroid && onContinueOffline && (
          <div className="mt-4 border-t border-zinc-200 pt-4 dark:border-zinc-800">
            <button
              type="button"
              onClick={onContinueOffline}
              className="inline-flex min-h-[48px] w-full items-center justify-center gap-2 rounded-xl border border-zinc-300 bg-zinc-50 px-4 text-sm font-medium text-zinc-800 transition hover:bg-zinc-200 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200 dark:hover:bg-zinc-800"
            >
              <Smartphone className="h-4 w-4 text-[#65a30d] dark:text-[#A3E635]" aria-hidden="true" />
              <span>Usar Motor Local (sem VPS)</span>
            </button>
          </div>
        )}
      </div>
    </main>
  );
}
