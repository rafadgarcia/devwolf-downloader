import { useCallback, useEffect, useState } from 'react';
import {
  ArrowUpCircle,
  Cpu,
  Image as ImageIcon,
  Info,
  RefreshCw,
  RotateCcw,
  ShieldCheck,
  Smartphone,
} from 'lucide-react';
import { PLATFORM_ROUTING_TABLE } from '../services/hybridOrchestrator';
import { nativeBridge } from '../services/nativeBridge';
import type { LocalEngineStatus, UpdateChannel } from '../types/api';
import { formatDateTime, formatMs } from '../utils/format';

interface EnginesViewProps {
  isNativeAndroid: boolean;
  updateChannel: UpdateChannel;
  onChangeUpdateChannel: (channel: UpdateChannel) => Promise<void>;
}

export function EnginesView({
  isNativeAndroid,
  updateChannel,
  onChangeUpdateChannel,
}: EnginesViewProps) {
  const [localStatus, setLocalStatus] = useState<LocalEngineStatus | null>(null);
  const [instagramConfigured, setInstagramConfigured] = useState(false);
  const [localBusy, setLocalBusy] = useState(false);
  const [localActionMsg, setLocalActionMsg] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const loadAll = useCallback(async () => {
    setLoading(true);
    const [status, sessions] = await Promise.all([
      nativeBridge.getLocalEngineStatus(),
      isNativeAndroid ? nativeBridge.getPlatformSessions() : Promise.resolve([]),
    ]);
    setLocalStatus(status);
    setInstagramConfigured(
      sessions.some((s) => s.platform === 'instagram' && s.configured)
    );
    setLoading(false);
  }, [isNativeAndroid]);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  const handleUpdateLocal = async () => {
    setLocalBusy(true);
    setLocalActionMsg(null);
    try {
      const res = await nativeBridge.updateLocalEngine(updateChannel);
      setLocalActionMsg(res.message || 'Verificação/atualização concluída.');
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
  const dnrStrategies =
    localStatus?.dnrStrategies && localStatus.dnrStrategies.length > 0
      ? localStatus.dnrStrategies
      : ['EmbedCaptioned', 'WebPageJson', 'AuthorizedWebApi'];

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-base font-semibold text-zinc-900 dark:text-zinc-100">
          Motores Locais
        </h2>
        <button
          type="button"
          onClick={() => void loadAll()}
          className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl px-3 text-xs font-medium text-zinc-600 transition hover:bg-zinc-200/70 dark:text-zinc-300 dark:hover:bg-[#1C1C20]"
        >
          <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} aria-hidden="true" />
          <span>Atualizar</span>
        </button>
      </div>

      {/* 1. DNR — Devwolf Native Resolver (Instagram) */}
      <section className="rounded-2xl border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-[#1C1C20]">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <Cpu className="h-4 w-4 text-[#65a30d] dark:text-[#A3E635]" aria-hidden="true" />
            <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
              DNR (Devwolf Native Resolver — Instagram &amp; Threads)
            </h3>
          </div>
          <span
            className={`inline-flex items-center rounded-lg border px-2.5 py-0.5 text-xs font-medium ${
              isNativeAndroid && (localStatus?.dnrAvailable ?? localStatus?.available)
                ? 'border-[#A3E635]/30 bg-[#A3E635]/15 text-[#4d7c0f] dark:text-[#A3E635]'
                : 'border-zinc-300 bg-zinc-100 text-zinc-600 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-400'
            }`}
          >
            {isNativeAndroid ? 'Ativo' : 'Somente APK'}
          </span>
        </div>

        <p className="mt-1.5 text-xs text-zinc-500 dark:text-zinc-400">
          Resolvedor Kotlin nativo dedicado para Reels, vídeos, fotos individuais e carrosséis mistos
          do Instagram e Threads, sem depender de servidor externo.
        </p>

        <dl className="mt-3 grid grid-cols-2 gap-x-3 gap-y-2 text-xs">
          <div className="col-span-2">
            <dt className="text-zinc-500 dark:text-zinc-400">Estratégias ativas</dt>
            <dd className="mt-1 flex flex-wrap gap-1.5">
              {dnrStrategies.map((strategy) => (
                <span
                  key={strategy}
                  className="rounded-md border border-zinc-200 bg-zinc-50 px-2 py-0.5 font-mono text-[11px] text-zinc-700 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-300"
                >
                  {strategy}
                </span>
              ))}
            </dd>
          </div>
          <div>
            <dt className="text-zinc-500 dark:text-zinc-400">Tempo médio de resolução</dt>
            <dd className="font-mono font-medium text-zinc-800 dark:text-zinc-200">
              {formatMs(localStatus?.dnrAvgResolutionMs)}
            </dd>
          </div>
          <div>
            <dt className="text-zinc-500 dark:text-zinc-400">Sessão opcional Instagram</dt>
            <dd className="inline-flex items-center gap-1 font-medium text-zinc-800 dark:text-zinc-200">
              <ShieldCheck
                className={`h-3.5 w-3.5 ${
                  instagramConfigured ? 'text-[#65a30d] dark:text-[#A3E635]' : 'text-zinc-400'
                }`}
                aria-hidden="true"
              />
              <span>{instagramConfigured ? 'Configurada' : 'Anônimo (público)'}</span>
            </dd>
          </div>
        </dl>
      </section>

      {/* 2. yt-dlp + FFmpeg (Motor Local Android) */}
      <section className="rounded-2xl border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-[#1C1C20]">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <Smartphone className="h-4 w-4 text-[#65a30d] dark:text-[#A3E635]" aria-hidden="true" />
            <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
              yt-dlp + FFmpeg (Motor Local Android)
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
                <dt className="text-zinc-500 dark:text-zinc-400">Binário yt-dlp instalado</dt>
                <dd className="font-mono font-semibold text-zinc-900 dark:text-zinc-100">
                  {localStatus.ytdlpVersion || '—'}
                </dd>
                {localStatus.wrapperVersion && (
                  <dd className="text-[11px] text-zinc-500 dark:text-zinc-400">
                    Wrapper: {localStatus.wrapperVersion}
                  </dd>
                )}
              </div>
              <div>
                <dt className="text-zinc-500 dark:text-zinc-400">FFmpeg local</dt>
                <dd className="font-mono font-medium text-zinc-800 dark:text-zinc-200">
                  {localStatus.ffmpegVersion || 'Integrado'}
                </dd>
              </div>
              <div>
                <dt className="text-zinc-500 dark:text-zinc-400">Última checagem</dt>
                <dd className="font-medium text-zinc-800 dark:text-zinc-200">
                  {formatDateTime(localStatus.lastCheckAt)}
                </dd>
              </div>
              <div>
                <dt className="text-zinc-500 dark:text-zinc-400">Última atualização</dt>
                <dd className="font-medium text-zinc-800 dark:text-zinc-200">
                  {formatDateTime(localStatus.lastUpdateAt)}
                </dd>
              </div>
              <div className="col-span-2">
                <dt className="text-zinc-500 dark:text-zinc-400">Status de versão</dt>
                <dd className="font-mono font-medium text-zinc-800 dark:text-zinc-200">
                  {localStatus.updateAvailable && localStatus.latestYtdlpVersion
                    ? `Nova versão disponível: ${localStatus.latestYtdlpVersion}`
                    : 'Atualizado'}
                </dd>
              </div>
            </dl>

            <div className="mt-3 flex items-center justify-between gap-3 rounded-xl border border-zinc-200 bg-zinc-50 px-3 py-2 text-xs dark:border-zinc-800 dark:bg-[#101012]">
              <label
                htmlFor="engine-update-channel"
                className="font-medium text-zinc-700 dark:text-zinc-300"
              >
                Canal de atualização
              </label>
              <select
                id="engine-update-channel"
                aria-label="Canal de atualização do yt-dlp"
                value={updateChannel}
                onChange={(e) => void onChangeUpdateChannel(e.target.value as UpdateChannel)}
                className="min-h-[40px] rounded-lg border border-zinc-300 bg-white px-2.5 text-xs font-medium text-zinc-800 outline-none dark:border-zinc-700 dark:bg-[#1C1C20] dark:text-zinc-200"
              >
                <option value="stable">Stable</option>
                <option value="nightly">Nightly</option>
              </select>
            </div>

            {blockedByActiveDownloads && (
              <p className="mt-2.5 text-xs text-amber-600 dark:text-amber-300">
                Atualização e rollback bloqueados enquanto houver download local ativo (
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
                <span>{localBusy ? 'Verificando…' : 'Verificar / Atualizar agora'}</span>
              </button>

              <button
                type="button"
                disabled={localBusy || blockedByActiveDownloads || !localStatus.previousVersion}
                onClick={() => void handleRollbackLocal()}
                className="inline-flex min-h-[44px] items-center justify-center gap-1.5 rounded-xl border border-zinc-300 bg-zinc-50 px-3.5 text-xs font-medium text-zinc-800 transition hover:bg-zinc-200 disabled:cursor-not-allowed disabled:opacity-45 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200 dark:hover:bg-zinc-800"
              >
                <RotateCcw className="h-4 w-4" aria-hidden="true" />
                <span>
                  Rollback
                  {localStatus.previousVersion ? ` (${localStatus.previousVersion})` : ''}
                </span>
              </button>
            </div>
          </>
        ) : (
          <p className="mt-2 text-xs text-zinc-500 dark:text-zinc-400">
            Executado no navegador (Preview Web). Instale o APK Android do Devwolf Downloader para
            rodar o binário yt-dlp + FFmpeg localmente no aparelho.
          </p>
        )}
      </section>

      {/* 3. gallery-dl / Resolvedores Locais de Imagens */}
      <section className="rounded-2xl border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-[#1C1C20]">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <ImageIcon
              className="h-4 w-4 text-[#65a30d] dark:text-[#A3E635]"
              aria-hidden="true"
            />
            <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
              gallery-dl / Resolvedores Locais de Imagens
            </h3>
          </div>
          <span
            className={`inline-flex items-center rounded-lg border px-2.5 py-0.5 text-xs font-medium ${
              isNativeAndroid
                ? 'border-[#A3E635]/30 bg-[#A3E635]/15 text-[#4d7c0f] dark:text-[#A3E635]'
                : 'border-zinc-300 bg-zinc-100 text-zinc-600 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-400'
            }`}
          >
            {isNativeAndroid ? 'Ativo' : 'Somente APK'}
          </span>
        </div>

        <dl className="mt-3 grid grid-cols-2 gap-x-3 gap-y-2 text-xs">
          <div>
            <dt className="text-zinc-500 dark:text-zinc-400">Versão / Motor</dt>
            <dd className="font-mono font-medium text-zinc-800 dark:text-zinc-200">
              {localStatus?.gallerydlVersion || 'Nativo Kotlin + gallery-dl'}
            </dd>
          </div>
          <div>
            <dt className="text-zinc-500 dark:text-zinc-400">Plataformas suportadas</dt>
            <dd className="font-medium text-zinc-800 dark:text-zinc-200">
              Pinterest, Reddit, Carrosséis
            </dd>
          </div>
        </dl>
      </section>

      {/* Tabela centralizada de roteamento por plataforma */}
      <section className="rounded-2xl border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-[#1C1C20]">
        <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
          Roteamento por plataforma (100% Local)
        </h3>
        <ul className="mt-3 space-y-2">
          {PLATFORM_ROUTING_TABLE.map((route) => (
            <li
              key={route.platform}
              className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-zinc-200 bg-zinc-50 px-3 py-2 text-xs dark:border-zinc-800 dark:bg-[#101012]"
            >
              <span className="font-medium text-zinc-900 dark:text-zinc-100">{route.label}</span>
              <span className="rounded-md bg-[#A3E635]/15 px-2 py-0.5 font-mono text-[11px] font-medium text-[#4d7c0f] dark:text-[#A3E635]">
                {route.routeDescription}
              </span>
            </li>
          ))}
        </ul>
      </section>

      <div className="flex items-start gap-2.5 rounded-2xl border border-zinc-200 bg-white p-3.5 text-xs text-zinc-600 dark:border-zinc-800 dark:bg-[#1C1C20] dark:text-zinc-400">
        <Info className="mt-0.5 h-4 w-4 shrink-0 text-[#65a30d] dark:text-[#A3E635]" aria-hidden="true" />
        <span>
          Todos os motores executam diretamente no dispositivo Android, sem dependência de servidor
          remoto ou VPS.
        </span>
      </div>
    </div>
  );
}
