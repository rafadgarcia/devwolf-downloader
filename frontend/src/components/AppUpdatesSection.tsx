import { useCallback, useEffect, useRef, useState } from 'react';
import { ChevronDown, ChevronUp, CloudDownload, PackageCheck, RefreshCw, Trash2, X } from 'lucide-react';
import {
  nativeBridge,
  type AppUpdateProgressEvent,
  type AppUpdateState,
} from '../services/nativeBridge';
import type { UserSettings } from '../types/api';
import { formatBytes } from '../utils/format';

/** Verificação automática ao abrir a seção quando a última consulta tiver mais de 6 h. */
export const APP_UPDATE_AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000;

export function shouldAutoCheckAppUpdate(lastCheckAt: number | null | undefined, now = Date.now()): boolean {
  if (!lastCheckAt) return true;
  return now - lastCheckAt >= APP_UPDATE_AUTO_CHECK_INTERVAL_MS;
}

interface AppUpdatesSectionProps {
  settings: UserSettings;
  isNativeAndroid: boolean;
  onUpdateSettings: (patch: Partial<UserSettings>) => Promise<void>;
}

function formatCheckTime(ms: number | null | undefined): string {
  if (!ms) return 'nunca';
  try {
    return new Intl.DateTimeFormat('pt-BR', {
      day: '2-digit',
      month: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    }).format(new Date(ms));
  } catch {
    return '—';
  }
}

export function AppUpdatesSection({ settings, isNativeAndroid, onUpdateSettings }: AppUpdatesSectionProps) {
  const [open, setOpen] = useState(false);
  const [state, setState] = useState<AppUpdateState | null>(null);
  const [checking, setChecking] = useState(false);
  const [progress, setProgress] = useState<AppUpdateProgressEvent | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [notesOpen, setNotesOpen] = useState(false);
  const awaitingPermissionRef = useRef(false);
  const autoCheckedRef = useRef(false);
  const includePre = settings.include_prerelease_updates;

  const refreshState = useCallback(async () => {
    const next = await nativeBridge.getAppUpdateState();
    if (next) setState(next);
    return next;
  }, []);

  const runCheck = useCallback(
    async (includePreReleases: boolean) => {
      setChecking(true);
      setError(null);
      setNotice(null);
      try {
        const next = await nativeBridge.checkAppUpdate(includePreReleases);
        setState(next);
        if (!next.updateAvailable) {
          setNotice('Você já está na versão mais recente.');
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Não foi possível verificar atualizações.');
      } finally {
        setChecking(false);
      }
    },
    []
  );

  const startInstall = useCallback(async () => {
    setError(null);
    setNotice(null);
    try {
      const status = await nativeBridge.installAppUpdate();
      if (status === 'permission_required') {
        awaitingPermissionRef.current = true;
        setNotice(
          'Permita “Instalar apps desconhecidos” para o Devwolf Downloader e volte ao app. O APK já baixado será usado, sem baixar de novo.'
        );
      } else {
        awaitingPermissionRef.current = false;
        setNotice('Instalador do Android aberto. Confirme a atualização para concluir.');
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Não foi possível iniciar a instalação.');
    }
  }, []);

  // Estado inicial + progresso do download.
  useEffect(() => {
    if (!isNativeAndroid) return undefined;
    void refreshState();
    let unsubscribe: (() => void) | null = null;
    let disposed = false;
    void nativeBridge
      .onAppUpdateProgress((event) => {
        setProgress(event.status === 'downloading' ? event : null);
        if (event.status !== 'downloading') void refreshState();
      })
      .then((fn) => {
        if (disposed) fn();
        else unsubscribe = fn;
      });
    return () => {
      disposed = true;
      unsubscribe?.();
    };
  }, [isNativeAndroid, refreshState]);

  // Ao voltar da tela de permissão, retoma a instalação automaticamente com o APK já baixado.
  useEffect(() => {
    if (!isNativeAndroid) return undefined;
    const onVisible = () => {
      if (document.visibilityState !== 'visible' || !awaitingPermissionRef.current) return;
      void refreshState().then((next) => {
        if (next?.canRequestInstall && next.downloadedReady) {
          awaitingPermissionRef.current = false;
          void startInstall();
        }
      });
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [isNativeAndroid, refreshState, startInstall]);

  // Verificação automática na primeira abertura da seção (no máximo a cada 6 h).
  useEffect(() => {
    if (!open || !isNativeAndroid || autoCheckedRef.current || state === null) return;
    autoCheckedRef.current = true;
    if (shouldAutoCheckAppUpdate(state.lastCheckAt)) {
      void runCheck(includePre);
    }
  }, [open, isNativeAndroid, state, includePre, runCheck]);

  const handleDownload = async () => {
    setError(null);
    setNotice(null);
    setProgress({
      tag: state?.latest?.tag ?? '',
      status: 'downloading',
      bytesDownloaded: 0,
      totalBytes: state?.latest?.assetSizeBytes ?? null,
      percent: 0,
    });
    try {
      const next = await nativeBridge.downloadAppUpdate();
      setState(next);
      setProgress(null);
      if (next.downloadedReady) {
        await startInstall();
      }
    } catch (err) {
      setProgress(null);
      setError(err instanceof Error ? err.message : 'Não foi possível baixar a atualização.');
      void refreshState();
    }
  };

  const handleDelete = async () => {
    setError(null);
    const next = await nativeBridge.deleteDownloadedAppUpdate();
    if (next) setState(next);
    setNotice('APK baixado removido.');
  };

  const handleTogglePre = async () => {
    const value = !includePre;
    await onUpdateSettings({ include_prerelease_updates: value });
    if (isNativeAndroid) void runCheck(value);
  };

  const latest = state?.latest ?? null;
  const downloading = progress !== null || Boolean(state?.downloading);
  const ready = Boolean(state?.downloadedReady);
  const signatureMismatch = ready && state?.downloaded?.signatureMatches === false;
  const percent = progress?.percent ?? null;

  const statusLine = !isNativeAndroid
    ? 'Disponível apenas no aplicativo Android'
    : checking
      ? 'Verificando…'
      : state?.updateAvailable && latest
        ? `Nova versão disponível: ${latest.version}`
        : `Versão ${state?.currentVersion || '—'} · verificado ${formatCheckTime(state?.lastCheckAt)}`;

  return (
    <section className="dw-glass-card rounded-3xl">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className="flex min-h-[52px] w-full items-center justify-between gap-3 px-4 py-3 text-left"
      >
        <div className="flex items-center gap-2.5">
          <CloudDownload className="h-4 w-4 text-[#7C5CFF] dark:text-[#A78BFA]" aria-hidden="true" />
          <div>
            <p className="flex items-center gap-2 text-sm font-bold text-[#1E1940] dark:text-[#F5F3FF]">
              Atualizações
              {state?.updateAvailable && (
                <span className="rounded-full bg-[#7C5CFF] px-2 py-0.5 text-[10px] font-bold text-white">
                  Nova
                </span>
              )}
            </p>
            <p className="text-xs text-[#5B5482] dark:text-[#A5A1C8]">{statusLine}</p>
          </div>
        </div>
        {open ? (
          <ChevronUp className="h-4 w-4 text-[#7D75A8]" aria-hidden="true" />
        ) : (
          <ChevronDown className="h-4 w-4 text-[#7D75A8]" aria-hidden="true" />
        )}
      </button>

      {open && (
        <div className="space-y-3 border-t border-[#7C5CFF]/15 px-4 pb-4 pt-3">
          <dl className="grid grid-cols-2 gap-x-3 gap-y-2 text-xs">
            <div>
              <dt className="text-[#5B5482] dark:text-[#A5A1C8]">Versão instalada</dt>
              <dd className="font-mono font-medium text-[#1E1940] dark:text-[#F5F3FF]">
                {state?.currentVersion || '—'}
              </dd>
            </div>
            <div>
              <dt className="text-[#5B5482] dark:text-[#A5A1C8]">Última disponível</dt>
              <dd className="font-mono font-medium text-[#1E1940] dark:text-[#F5F3FF]">
                {latest ? `${latest.version}${latest.isPreRelease ? ' (teste)' : ''}` : '—'}
              </dd>
            </div>
            <div>
              <dt className="text-[#5B5482] dark:text-[#A5A1C8]">Arquivo</dt>
              <dd className="break-all font-mono text-[#1E1940] dark:text-[#F5F3FF]">
                {latest ? `${latest.assetName} · ${formatBytes(latest.assetSizeBytes) || '—'}` : '—'}
              </dd>
            </div>
            <div>
              <dt className="text-[#5B5482] dark:text-[#A5A1C8]">Última verificação</dt>
              <dd className="font-mono text-[#1E1940] dark:text-[#F5F3FF]">
                {formatCheckTime(state?.lastCheckAt)}
              </dd>
            </div>
          </dl>

          <div className="flex items-center justify-between gap-4">
            <div>
              <p className="text-sm font-semibold text-[#1E1940] dark:text-[#F5F3FF]">
                Incluir versões de teste
              </p>
              <p className="text-xs text-[#5B5482] dark:text-[#A5A1C8]">
                Considera releases marcados como pre-release (ex.: rc)
              </p>
            </div>
            <button
              type="button"
              role="switch"
              aria-label="Incluir versões de teste"
              aria-checked={includePre}
              onClick={() => void handleTogglePre()}
              className={`min-h-[44px] min-w-[68px] rounded-xl px-3 text-xs font-bold transition ${
                includePre
                  ? 'dw-btn-primary'
                  : 'border border-[#7C5CFF]/25 bg-[#7C5CFF]/10 text-[#5B5482] dark:text-[#A5A1C8]'
              }`}
            >
              {includePre ? 'ON' : 'OFF'}
            </button>
          </div>

          {downloading && (
            <div aria-live="polite">
              <div className="mb-1 flex justify-between text-xs text-[#5B5482] dark:text-[#A5A1C8]">
                <span>Baixando atualização…</span>
                <span className="font-mono">
                  {percent !== null ? `${Math.round(percent)}%` : formatBytes(progress?.bytesDownloaded)}
                </span>
              </div>
              <div className="h-2 overflow-hidden rounded-full bg-[#7C5CFF]/15">
                <div
                  className={`h-full rounded-full bg-[#7C5CFF] transition-all ${percent === null ? 'w-1/3 animate-pulse' : ''}`}
                  style={percent !== null ? { width: `${percent}%` } : undefined}
                />
              </div>
            </div>
          )}

          {signatureMismatch && (
            <p className="rounded-xl border border-amber-500/30 bg-amber-500/10 p-3 text-xs text-amber-700 dark:text-amber-300">
              Este APK foi assinado com outra chave. O Android vai recusar a atualização; seria preciso
              desinstalar o app atual (perdendo fila e sessões) e instalar manualmente.
            </p>
          )}

          {error && (
            <p role="alert" className="rounded-xl border border-red-500/30 bg-red-500/10 p-3 text-xs text-red-600 dark:text-red-300">
              {error}
            </p>
          )}
          {notice && !error && (
            <p className="rounded-xl border border-[#7C5CFF]/20 bg-[#7C5CFF]/10 p-3 text-xs text-[#5E43F3] dark:text-[#C4B5FD]">
              {notice}
            </p>
          )}

          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              disabled={!isNativeAndroid || checking || downloading}
              onClick={() => void runCheck(includePre)}
              className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl border border-[#7C5CFF]/25 bg-[#7C5CFF]/10 px-3.5 text-xs font-semibold text-[#5E43F3] transition hover:bg-[#7C5CFF]/20 disabled:opacity-50 dark:text-[#C4B5FD]"
            >
              <RefreshCw className={`h-3.5 w-3.5 ${checking ? 'animate-spin' : ''}`} aria-hidden="true" />
              {checking ? 'Verificando…' : 'Verificar agora'}
            </button>

            {state?.updateAvailable && !ready && !downloading && (
              <button
                type="button"
                onClick={() => void handleDownload()}
                className="dw-btn-primary inline-flex min-h-[44px] items-center gap-1.5 rounded-xl px-3.5 text-xs font-bold"
              >
                <CloudDownload className="h-3.5 w-3.5" aria-hidden="true" />
                Baixar e instalar
              </button>
            )}

            {downloading && (
              <button
                type="button"
                onClick={() => void nativeBridge.cancelAppUpdateDownload()}
                className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl border border-[#7C5CFF]/25 px-3.5 text-xs font-semibold text-[#5B5482] dark:text-[#A5A1C8]"
              >
                <X className="h-3.5 w-3.5" aria-hidden="true" />
                Cancelar
              </button>
            )}

            {ready && !downloading && (
              <>
                <button
                  type="button"
                  disabled={signatureMismatch}
                  onClick={() => void startInstall()}
                  className="dw-btn-primary inline-flex min-h-[44px] items-center gap-1.5 rounded-xl px-3.5 text-xs font-bold disabled:opacity-50"
                >
                  <PackageCheck className="h-3.5 w-3.5" aria-hidden="true" />
                  Instalar {state?.downloaded?.version}
                </button>
                <button
                  type="button"
                  onClick={() => void handleDelete()}
                  aria-label="Excluir APK baixado"
                  className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl border border-[#7C5CFF]/25 px-3.5 text-xs font-semibold text-[#5B5482] dark:text-[#A5A1C8]"
                >
                  <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
                  Excluir APK
                </button>
              </>
            )}
          </div>

          {ready && !downloading && (
            <p className="text-xs text-[#5B5482] dark:text-[#A5A1C8]">
              APK já baixado ({formatBytes(state?.downloaded?.sizeBytes)}). Se a instalação não for concluída,
              toque em Instalar novamente — não é preciso baixar de novo.
            </p>
          )}

          {latest?.notes && (
            <div>
              <button
                type="button"
                onClick={() => setNotesOpen((v) => !v)}
                className="text-xs font-semibold text-[#5E43F3] underline-offset-2 hover:underline dark:text-[#C4B5FD]"
              >
                {notesOpen ? 'Ocultar novidades' : `Novidades da ${latest.version}`}
              </button>
              {notesOpen && (
                <pre className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap rounded-xl bg-[#7C5CFF]/5 p-3 text-[11px] text-[#1E1940] dark:text-[#E9E5FF]">
                  {latest.notes}
                </pre>
              )}
            </div>
          )}

          <p className="text-[11px] text-[#7D75A8]">
            Fonte: releases públicos do GitHub ({state?.repository || 'rafadgarcia/devwolf-downloader'}). O APK é
            verificado (pacote, assinatura e SHA-256 quando publicado) antes da instalação.
          </p>
        </div>
      )}
    </section>
  );
}
