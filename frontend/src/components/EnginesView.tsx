import { useCallback, useEffect, useState } from 'react';
import {
  AlertTriangle,
  ArrowUpCircle,
  Cpu,
  Info,
  RefreshCw,
  RotateCcw,
  Server,
  ShieldAlert,
  Smartphone,
} from 'lucide-react';
import { api } from '../services/api';
import { nativeBridge } from '../services/nativeBridge';
import type {
  CobaltUpdaterStatus,
  EngineHealthState,
  EngineInfo,
  EngineReleaseInfo,
  LocalEngineStatus,
  PlatformCapabilityInfo,
} from '../types/api';
import { formatDateTime, getEngineHealthLabel } from '../utils/format';

interface EnginesViewProps {
  isNativeAndroid: boolean;
  vpsAuthenticated: boolean;
  onRequestVpsLogin: () => void;
}

function getCircuitLabel(state: string): string {
  switch (state) {
    case 'open':
      return 'Aberto (em pausa)';
    case 'half_open':
      return 'Meio-aberto (testando)';
    case 'closed':
    default:
      return 'Fechado (normal)';
  }
}

function normalizeHealth(raw: string): EngineHealthState {
  if (raw === 'online' || raw === 'degraded' || raw === 'maintenance' || raw === 'offline') {
    return raw;
  }
  return 'offline';
}

export function EnginesView({
  isNativeAndroid,
  vpsAuthenticated,
  onRequestVpsLogin,
}: EnginesViewProps) {
  const [localStatus, setLocalStatus] = useState<LocalEngineStatus | null>(null);
  const [localBusy, setLocalBusy] = useState(false);
  const [localActionMsg, setLocalActionMsg] = useState<string | null>(null);

  const [engines, setEngines] = useState<EngineInfo[]>([]);
  const [releases, setReleases] = useState<Record<string, EngineReleaseInfo>>({});
  const [capabilities, setCapabilities] = useState<PlatformCapabilityInfo[]>([]);
  const [updater, setUpdater] = useState<CobaltUpdaterStatus | null>(null);
  const [disclaimer, setDisclaimer] = useState(
    'Online significa processo respondendo, não garantia de que todas as plataformas funcionam.'
  );
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadAll = useCallback(async () => {
    setLoading(true);
    setError(null);

    if (isNativeAndroid) {
      const local = await nativeBridge.getLocalEngineStatus();
      setLocalStatus(local);
    }

    if (vpsAuthenticated) {
      try {
        const [res, relRes] = await Promise.all([
          api.getEngines(),
          api.getEngineReleases().catch(() => null),
        ]);
        setEngines(res.engines ?? []);
        if (res.disclaimer) {
          setDisclaimer(res.disclaimer);
        }
        const cobaltEngine = res.engines?.find((e) => e.engine === 'cobalt');
        setUpdater(cobaltEngine?.updater_status ?? null);

        if (relRes) {
          const map: Record<string, EngineReleaseInfo> = {};
          for (const item of relRes.engines ?? []) {
            map[item.engine] = item;
          }
          setReleases(map);
          setCapabilities(relRes.capabilities ?? []);
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Erro ao consultar motores da VPS.');
      }
    } else {
      setEngines([]);
      setReleases({});
      setCapabilities([]);
      setUpdater(null);
    }

    setLoading(false);
  }, [isNativeAndroid, vpsAuthenticated]);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  const handleUpdateLocal = async () => {
    setLocalBusy(true);
    setLocalActionMsg(null);
    try {
      const res = await nativeBridge.updateLocalEngine('stable');
      setLocalActionMsg(res.message || 'Motor local atualizado.');
      const refreshed = await nativeBridge.getLocalEngineStatus();
      setLocalStatus(refreshed);
    } catch (err) {
      setLocalActionMsg(err instanceof Error ? err.message : 'Falha ao atualizar yt-dlp local.');
    } finally {
      setLocalBusy(false);
    }
  };

  const handleRollbackLocal = async () => {
    setLocalBusy(true);
    setLocalActionMsg(null);
    try {
      const res = await nativeBridge.rollbackLocalEngine();
      setLocalActionMsg(res.message || 'Versão anterior restaurada.');
      const refreshed = await nativeBridge.getLocalEngineStatus();
      setLocalStatus(refreshed);
    } catch (err) {
      setLocalActionMsg(err instanceof Error ? err.message : 'Falha ao reverter yt-dlp local.');
    } finally {
      setLocalBusy(false);
    }
  };

  const blockedByActiveDownloads = Boolean(
    localStatus && (localStatus.activeDownloads > 0 || !localStatus.canUpdate)
  );

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-base font-semibold text-zinc-900 dark:text-zinc-100">Motores</h2>
        <button
          type="button"
          onClick={() => void loadAll()}
          className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl px-3 text-xs font-medium text-zinc-600 transition hover:bg-zinc-200/70 dark:text-zinc-300 dark:hover:bg-[#1C1C20]"
        >
          <RefreshCw className="h-4 w-4" aria-hidden="true" />
          <span>Atualizar</span>
        </button>
      </div>

      {/* Motor Local (Android: yt-dlp + FFmpeg) */}
      <section className="rounded-2xl border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-[#1C1C20]">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <Smartphone className="h-4 w-4 text-[#65a30d] dark:text-[#A3E635]" aria-hidden="true" />
            <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
              Motor Local (Android: yt-dlp + FFmpeg)
            </h3>
          </div>
          <span
            className={`inline-flex items-center rounded-lg border px-2.5 py-0.5 text-xs font-medium ${
              isNativeAndroid && localStatus?.available
                ? 'border-[#A3E635]/30 bg-[#A3E635]/15 text-[#4d7c0f] dark:text-[#A3E635]'
                : 'border-zinc-300 bg-zinc-100 text-zinc-600 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-400'
            }`}
          >
            {isNativeAndroid && localStatus?.available ? 'Ativo' : 'Somente APK'}
          </span>
        </div>

        {isNativeAndroid && localStatus ? (
          <>
            <dl className="mt-3 grid grid-cols-2 gap-x-3 gap-y-2 text-xs">
              <div>
                <dt className="text-zinc-500 dark:text-zinc-400">yt-dlp instalado</dt>
                <dd className="font-mono font-medium text-zinc-800 dark:text-zinc-200">
                  {localStatus.ytdlpVersion || '—'}
                </dd>
              </div>
              <div>
                <dt className="text-zinc-500 dark:text-zinc-400">FFmpeg local</dt>
                <dd className="font-mono font-medium text-zinc-800 dark:text-zinc-200">
                  {localStatus.ffmpegVersion || 'Integrado'}
                </dd>
              </div>
              <div>
                <dt className="text-zinc-500 dark:text-zinc-400">Atualização disponível</dt>
                <dd className="font-mono font-medium text-zinc-800 dark:text-zinc-200">
                  {localStatus.updateAvailable && localStatus.latestYtdlpVersion
                    ? localStatus.latestYtdlpVersion
                    : 'Atualizado'}
                </dd>
              </div>
              <div>
                <dt className="text-zinc-500 dark:text-zinc-400">Última atualização</dt>
                <dd className="font-medium text-zinc-800 dark:text-zinc-200">
                  {formatDateTime(localStatus.lastUpdateAt)}
                </dd>
              </div>
            </dl>

            {blockedByActiveDownloads && (
              <p className="mt-2.5 text-xs text-amber-600 dark:text-amber-300">
                Atualização bloqueada enquanto houver download local ativo (
                {localStatus.activeDownloads}).
              </p>
            )}

            {localStatus.lastError && (
              <p className="mt-2 text-xs text-red-500 dark:text-red-400">{localStatus.lastError}</p>
            )}

            {localActionMsg && (
              <p className="mt-2 text-xs text-[#4d7c0f] dark:text-[#A3E635]">{localActionMsg}</p>
            )}

            <div className="mt-3 flex flex-wrap gap-2">
              <button
                type="button"
                disabled={localBusy || blockedByActiveDownloads}
                onClick={() => void handleUpdateLocal()}
                className="inline-flex min-h-[44px] flex-1 items-center justify-center gap-1.5 rounded-xl bg-[#A3E635] px-4 text-xs font-semibold text-zinc-950 transition hover:bg-[#84CC16] disabled:cursor-not-allowed disabled:opacity-45"
              >
                <ArrowUpCircle className="h-4 w-4" aria-hidden="true" />
                <span>{localBusy ? 'Atualizando…' : 'Atualizar yt-dlp'}</span>
              </button>

              {localStatus.previousVersion && (
                <button
                  type="button"
                  disabled={localBusy || blockedByActiveDownloads}
                  onClick={() => void handleRollbackLocal()}
                  className="inline-flex min-h-[44px] items-center justify-center gap-1.5 rounded-xl border border-zinc-300 bg-zinc-50 px-3.5 text-xs font-medium text-zinc-800 transition hover:bg-zinc-200 disabled:cursor-not-allowed disabled:opacity-45 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200 dark:hover:bg-zinc-800"
                >
                  <RotateCcw className="h-4 w-4" aria-hidden="true" />
                  <span>Reverter ({localStatus.previousVersion})</span>
                </button>
              )}
            </div>
          </>
        ) : (
          <p className="mt-2 text-xs text-zinc-500 dark:text-zinc-400">
            Executado no navegador (PWA). Instale o APK Android do Devwolf Downloader para rodar
            yt-dlp + FFmpeg localmente no dispositivo.
          </p>
        )}
      </section>

      <div className="flex items-start gap-2.5 rounded-2xl border border-zinc-200 bg-white p-3.5 text-xs text-zinc-600 dark:border-zinc-800 dark:bg-[#1C1C20] dark:text-zinc-400">
        <Info className="mt-0.5 h-4 w-4 shrink-0 text-[#65a30d] dark:text-[#A3E635]" aria-hidden="true" />
        <span>{disclaimer}</span>
      </div>

      {error && (
        <div
          role="alert"
          className="rounded-xl border border-red-500/30 bg-red-500/10 p-3 text-xs text-red-600 dark:text-red-300"
        >
          {error}
        </div>
      )}

      {/* Motores da VPS */}
      <div className="flex items-center gap-2 pt-1">
        <Server className="h-4 w-4 text-zinc-400" aria-hidden="true" />
        <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
          Motores da VPS (Cobalt, yt-dlp, gallery-dl)
        </h3>
      </div>

      {!vpsAuthenticated ? (
        <div className="rounded-2xl border border-zinc-200 bg-white p-4 text-xs text-zinc-600 dark:border-zinc-800 dark:bg-[#1C1C20] dark:text-zinc-400">
          <p>
            VPS desconectada. Você pode continuar usando o Motor Local offline ou conectar-se à sua
            VPS para acompanhar Cobalt, yt-dlp e gallery-dl remotos.
          </p>
          <button
            type="button"
            onClick={onRequestVpsLogin}
            className="mt-3 min-h-[44px] rounded-xl bg-[#A3E635] px-4 text-xs font-semibold text-zinc-950 transition hover:bg-[#84CC16]"
          >
            Conectar à VPS
          </button>
        </div>
      ) : loading ? (
        <div className="rounded-2xl border border-zinc-200 bg-white p-6 text-center text-xs text-zinc-500 dark:border-zinc-800 dark:bg-[#1C1C20] dark:text-zinc-400">
          Verificando saúde dos motores da VPS…
        </div>
      ) : (
        <div className="space-y-3">
          {engines.map((engine) => {
            const badge = getEngineHealthLabel(normalizeHealth(engine.health));
            const rel = releases[engine.engine];
            const latestVersion = engine.latest_version ?? rel?.latest_version;
            const hasUpdate = Boolean(engine.update_available ?? rel?.update_available);
            return (
              <article
                key={engine.engine}
                className="rounded-2xl border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-[#1C1C20]"
              >
                <div className="flex items-center justify-between gap-2">
                  <div className="flex items-center gap-2">
                    <Cpu className="h-4 w-4 text-zinc-400" aria-hidden="true" />
                    <h4 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
                      {engine.engine}
                    </h4>
                    {engine.installed_version && (
                      <span className="rounded-md bg-zinc-100 px-2 py-0.5 font-mono text-[11px] text-zinc-600 dark:bg-[#101012] dark:text-zinc-300">
                        {engine.installed_version}
                      </span>
                    )}
                    {hasUpdate && latestVersion && (
                      <span className="rounded-md bg-[#A3E635]/15 px-2 py-0.5 text-[11px] font-medium text-[#4d7c0f] dark:text-[#A3E635]">
                        Nova: {latestVersion}
                      </span>
                    )}
                  </div>

                  <span
                    className={`inline-flex items-center rounded-lg border px-2.5 py-0.5 text-xs font-medium ${badge.className}`}
                  >
                    {badge.label}
                  </span>
                </div>

                <dl className="mt-3 grid grid-cols-2 gap-x-3 gap-y-2 text-xs">
                  <div>
                    <dt className="text-zinc-500 dark:text-zinc-400">Circuit breaker</dt>
                    <dd className="font-medium text-zinc-800 dark:text-zinc-200">
                      {getCircuitLabel(engine.circuit_state)}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-zinc-500 dark:text-zinc-400">Último sucesso</dt>
                    <dd className="font-medium text-zinc-800 dark:text-zinc-200">
                      {formatDateTime(engine.last_success_at)}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-zinc-500 dark:text-zinc-400">Última verificação</dt>
                    <dd className="font-medium text-zinc-800 dark:text-zinc-200">
                      {formatDateTime(engine.last_checked_at)}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-zinc-500 dark:text-zinc-400">Reabertura / Nota</dt>
                    <dd className="font-medium text-zinc-800 dark:text-zinc-200">
                      {engine.circuit_retry_at
                        ? formatDateTime(engine.circuit_retry_at)
                        : engine.maintenance_note || '—'}
                    </dd>
                  </div>
                </dl>

                {engine.last_error_message && (
                  <div className="mt-3 flex items-start gap-2 rounded-xl border border-red-500/25 bg-red-500/10 p-2.5 text-xs text-red-600 dark:text-red-300">
                    <ShieldAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                    <span className="break-words">{engine.last_error_message}</span>
                  </div>
                )}
              </article>
            );
          })}
        </div>
      )}

      {updater && (
        <section className="rounded-2xl border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-[#1C1C20]">
          <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
            Atualizador do Cobalt (host)
          </h3>

          {(updater.last_rollback_at || updater.rejected_digest) && (
            <div className="mt-2.5 flex items-start gap-2 rounded-xl border border-amber-500/30 bg-amber-500/10 p-3 text-xs text-amber-700 dark:text-amber-300">
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
              <div>
                <p className="font-semibold">Rollback automático registrado</p>
                <p className="mt-0.5">
                  Último rollback: {formatDateTime(updater.last_rollback_at)}.
                  {updater.rejected_digest
                    ? ` Digest rejeitado: ${updater.rejected_digest.slice(0, 19)}…`
                    : ''}
                </p>
              </div>
            </div>
          )}

          <dl className="mt-3 grid grid-cols-2 gap-x-3 gap-y-2 text-xs">
            <div>
              <dt className="text-zinc-500 dark:text-zinc-400">Estado</dt>
              <dd className="font-medium text-zinc-800 dark:text-zinc-200">
                {updater.maintenance ? 'Em manutenção' : updater.state || 'idle'}
              </dd>
            </div>
            <div>
              <dt className="text-zinc-500 dark:text-zinc-400">Versão / Digest</dt>
              <dd className="truncate font-mono text-zinc-800 dark:text-zinc-200">
                {updater.current_version || updater.current_digest?.slice(0, 18) || '—'}
              </dd>
            </div>
            <div>
              <dt className="text-zinc-500 dark:text-zinc-400">Última checagem</dt>
              <dd className="font-medium text-zinc-800 dark:text-zinc-200">
                {formatDateTime(updater.last_check_at)}
              </dd>
            </div>
            <div>
              <dt className="text-zinc-500 dark:text-zinc-400">Última atualização</dt>
              <dd className="font-medium text-zinc-800 dark:text-zinc-200">
                {formatDateTime(updater.last_update_at)}
              </dd>
            </div>
          </dl>

          {updater.last_error && (
            <p className="mt-2.5 text-xs text-red-500 dark:text-red-400">{updater.last_error}</p>
          )}
        </section>
      )}

      {capabilities.length > 0 && (
        <section className="rounded-2xl border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-[#1C1C20]">
          <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
            Compatibilidade por plataforma
          </h3>
          <ul className="mt-3 space-y-2">
            {capabilities.map((cap) => (
              <li
                key={cap.platform}
                className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-zinc-200 bg-zinc-50 px-3 py-2 text-xs dark:border-zinc-800 dark:bg-[#101012]"
              >
                <div>
                  <p className="font-medium text-zinc-900 dark:text-zinc-100">{cap.label}</p>
                  <p className="text-[11px] text-zinc-500 dark:text-zinc-400">
                    Primário: {cap.primary_engine}
                    {cap.fallback_engines.length > 0
                      ? ` • Fallback: ${cap.fallback_engines.join(', ')}`
                      : ''}
                  </p>
                </div>
                <div className="flex items-center gap-1.5">
                  {cap.local_supported && (
                    <span className="rounded-md bg-[#A3E635]/15 px-2 py-0.5 text-[11px] font-medium text-[#4d7c0f] dark:text-[#A3E635]">
                      Local
                    </span>
                  )}
                  {cap.remote_supported && (
                    <span className="rounded-md bg-zinc-200 px-2 py-0.5 text-[11px] font-medium text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300">
                      VPS
                    </span>
                  )}
                </div>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
