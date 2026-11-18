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
  const [engineStatus, setEngineStatus] = useState<LocalEngineStatus | null>(null);
  const [instagramConfigured, setInstagramConfigured] = useState(false);
  const [busy, setBusy] = useState(false);
  const [actionMsg, setActionMsg] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const loadAll = useCallback(async () => {
    setLoading(true);
    const [status, sessions] = await Promise.all([
      nativeBridge.getLocalEngineStatus(),
      isNativeAndroid ? nativeBridge.getPlatformSessions() : Promise.resolve([]),
    ]);
    setEngineStatus(status);
    setInstagramConfigured(sessions.some((s) => s.platform === 'instagram' && s.configured));
    setLoading(false);
  }, [isNativeAndroid]);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  const handleUpdate = async () => {
    setBusy(true);
    setActionMsg(null);
    try {
      const res = await nativeBridge.updateLocalEngine(updateChannel);
      setActionMsg(res.message || 'Verificação/atualização concluída.');
      const refreshed = await nativeBridge.getLocalEngineStatus();
      setEngineStatus(refreshed);
    } catch (err) {
      setActionMsg(err instanceof Error ? err.message : 'Falha ao atualizar yt-dlp.');
    } finally {
      setBusy(false);
    }
  };

  const handleRollback = async () => {
    setBusy(true);
    setActionMsg(null);
    try {
      const res = await nativeBridge.rollbackLocalEngine();
      setActionMsg(res.message || 'Versão anterior restaurada.');
      const refreshed = await nativeBridge.getLocalEngineStatus();
      setEngineStatus(refreshed);
    } catch (err) {
      setActionMsg(err instanceof Error ? err.message : 'Falha ao reverter yt-dlp.');
    } finally {
      setBusy(false);
    }
  };

  const blockedByActiveDownloads = Boolean(
    engineStatus && (engineStatus.activeDownloads > 0 || !engineStatus.canUpdate)
  );
  const dnrStrategies =
    engineStatus?.dnrStrategies && engineStatus.dnrStrategies.length > 0
      ? engineStatus.dnrStrategies
      : ['EmbedCaptioned', 'WebPageJson', 'AuthorizedWebApi'];

  return (
    <div className="space-y-4 pb-8">
      <div className="flex items-center justify-between">
        <h2 className="text-base font-bold text-[#1E1940] dark:text-[#F5F3FF]">
          Status dos Motores
        </h2>
        <button
          type="button"
          onClick={() => void loadAll()}
          className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl px-3 text-xs font-medium text-[#5B5482] transition hover:bg-[#7C5CFF]/10 dark:text-[#C4B5FD] dark:hover:bg-[#7C5CFF]/15"
        >
          <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} aria-hidden="true" />
          <span>Atualizar</span>
        </button>
      </div>

      {/* 1. DNR — Devwolf Native Resolver (Instagram & Threads) */}
      <section className="dw-glass-card rounded-3xl p-4">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <Cpu className="h-4 w-4 text-[#7C5CFF] dark:text-[#A78BFA]" aria-hidden="true" />
            <h3 className="text-sm font-bold text-[#1E1940] dark:text-[#F5F3FF]">
              DNR • Instagram &amp; Threads
            </h3>
          </div>
          <span
            className={`inline-flex items-center rounded-lg border px-2.5 py-0.5 text-xs font-semibold ${
              isNativeAndroid && (engineStatus?.dnrAvailable ?? engineStatus?.available)
                ? 'border-[#7C5CFF]/35 bg-[#7C5CFF]/15 text-[#5E43F3] dark:text-[#C4B5FD]'
                : 'border-[#7C5CFF]/20 bg-[#7C5CFF]/5 text-[#5B5482] dark:text-[#A5A1C8]'
            }`}
          >
            {isNativeAndroid ? 'Ativo' : 'Somente APK'}
          </span>
        </div>

        <p className="mt-1.5 text-xs text-[#5B5482] dark:text-[#A5A1C8]">
          Resolvedor Kotlin nativo dedicado para Reels, vídeos, fotos individuais e carrosséis
          mistos do Instagram e Threads, sem depender de servidor externo.
        </p>

        <dl className="mt-3 grid grid-cols-2 gap-x-3 gap-y-2 text-xs">
          <div className="col-span-2">
            <dt className="text-[#5B5482] dark:text-[#A5A1C8]">Estratégias ativas</dt>
            <dd className="mt-1 flex flex-wrap gap-1.5">
              {dnrStrategies.map((strategy) => (
                <span
                  key={strategy}
                  className="dw-glass-subtle rounded-lg px-2 py-0.5 font-mono text-[11px] text-[#4C3F91] dark:text-[#E0DBFF]"
                >
                  {strategy}
                </span>
              ))}
            </dd>
          </div>
          <div>
            <dt className="text-[#5B5482] dark:text-[#A5A1C8]">Tempo médio de resolução</dt>
            <dd className="font-mono font-semibold text-[#1E1940] dark:text-[#F5F3FF]">
              {formatMs(engineStatus?.dnrAvgResolutionMs)}
            </dd>
          </div>
          <div>
            <dt className="text-[#5B5482] dark:text-[#A5A1C8]">Sessão opcional Instagram</dt>
            <dd className="inline-flex items-center gap-1 font-medium text-[#1E1940] dark:text-[#F5F3FF]">
              <ShieldCheck
                className={`h-3.5 w-3.5 ${
                  instagramConfigured
                    ? 'text-[#7C5CFF] dark:text-[#A78BFA]'
                    : 'text-[#7D75A8]'
                }`}
                aria-hidden="true"
              />
              <span>{instagramConfigured ? 'Configurada' : 'Anônimo (público)'}</span>
            </dd>
          </div>
        </dl>
      </section>

      {/* 2. yt-dlp + FFmpeg */}
      <section className="dw-glass-card rounded-3xl p-4">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <Smartphone className="h-4 w-4 text-[#7C5CFF] dark:text-[#A78BFA]" aria-hidden="true" />
            <h3 className="text-sm font-bold text-[#1E1940] dark:text-[#F5F3FF]">
              yt-dlp + FFmpeg
            </h3>
          </div>
          <span
            className={`inline-flex items-center rounded-lg border px-2.5 py-0.5 text-xs font-semibold ${
              isNativeAndroid && engineStatus?.available
                ? 'border-[#7C5CFF]/35 bg-[#7C5CFF]/15 text-[#5E43F3] dark:text-[#C4B5FD]'
                : 'border-[#7C5CFF]/20 bg-[#7C5CFF]/5 text-[#5B5482] dark:text-[#A5A1C8]'
            }`}
          >
            {isNativeAndroid && engineStatus?.available ? 'Ativo' : 'Somente APK'}
          </span>
        </div>

        {isNativeAndroid && engineStatus ? (
          <>
            <dl className="mt-3 grid grid-cols-2 gap-x-3 gap-y-2 text-xs">
              <div>
                <dt className="text-[#5B5482] dark:text-[#A5A1C8]">Binário yt-dlp instalado</dt>
                <dd className="font-mono font-semibold text-[#1E1940] dark:text-[#F5F3FF]">
                  {engineStatus.ytdlpVersion || '—'}
                </dd>
                {engineStatus.wrapperVersion && (
                  <dd className="text-[11px] text-[#5B5482] dark:text-[#A5A1C8]">
                    Wrapper: {engineStatus.wrapperVersion}
                  </dd>
                )}
              </div>
              <div>
                <dt className="text-[#5B5482] dark:text-[#A5A1C8]">FFmpeg</dt>
                <dd className="font-mono font-medium text-[#1E1940] dark:text-[#F5F3FF]">
                  {engineStatus.ffmpegVersion || 'Integrado'}
                </dd>
              </div>
              <div>
                <dt className="text-[#5B5482] dark:text-[#A5A1C8]">Última checagem</dt>
                <dd className="font-medium text-[#1E1940] dark:text-[#F5F3FF]">
                  {formatDateTime(engineStatus.lastCheckAt)}
                </dd>
              </div>
              <div>
                <dt className="text-[#5B5482] dark:text-[#A5A1C8]">Última atualização</dt>
                <dd className="font-medium text-[#1E1940] dark:text-[#F5F3FF]">
                  {formatDateTime(engineStatus.lastUpdateAt)}
                </dd>
              </div>
              <div className="col-span-2">
                <dt className="text-[#5B5482] dark:text-[#A5A1C8]">Status de versão</dt>
                <dd className="font-mono font-medium text-[#1E1940] dark:text-[#F5F3FF]">
                  {engineStatus.updateAvailable && engineStatus.latestYtdlpVersion
                    ? `Nova versão disponível: ${engineStatus.latestYtdlpVersion}`
                    : 'Atualizado'}
                </dd>
              </div>
            </dl>

            <div className="dw-glass-subtle mt-3 flex items-center justify-between gap-3 rounded-2xl px-3 py-2 text-xs">
              <label
                htmlFor="engine-update-channel"
                className="font-medium text-[#1E1940] dark:text-[#E0DBFF]"
              >
                Canal de atualização
              </label>
              <select
                id="engine-update-channel"
                aria-label="Canal de atualização do yt-dlp"
                value={updateChannel}
                onChange={(e) => void onChangeUpdateChannel(e.target.value as UpdateChannel)}
                className="min-h-[40px] rounded-xl border border-[#7C5CFF]/25 bg-white/80 px-2.5 text-xs font-medium text-[#1E1940] outline-none dark:bg-[#0D0B1E]/80 dark:text-[#E0DBFF]"
              >
                <option value="stable">Stable</option>
                <option value="nightly">Nightly</option>
              </select>
            </div>

            {blockedByActiveDownloads && (
              <p className="mt-2.5 text-xs text-amber-600 dark:text-amber-300">
                Atualização e rollback bloqueados enquanto houver download ativo (
                {engineStatus.activeDownloads}).
              </p>
            )}

            {engineStatus.lastError && (
              <p className="mt-2 text-xs text-rose-600 dark:text-rose-300">
                {engineStatus.lastError}
              </p>
            )}

            {actionMsg && (
              <p className="mt-2 text-xs text-[#5E43F3] dark:text-[#C4B5FD]">{actionMsg}</p>
            )}

            <div className="mt-3 flex flex-wrap gap-2">
              <button
                type="button"
                disabled={busy || blockedByActiveDownloads}
                onClick={() => void handleUpdate()}
                className="dw-btn-primary inline-flex min-h-[44px] flex-1 items-center justify-center gap-1.5 rounded-2xl px-4 text-xs font-semibold transition disabled:cursor-not-allowed disabled:opacity-45"
              >
                <ArrowUpCircle className="h-4 w-4" aria-hidden="true" />
                <span>{busy ? 'Verificando…' : 'Verificar / Atualizar agora'}</span>
              </button>

              <button
                type="button"
                disabled={busy || blockedByActiveDownloads || !engineStatus.previousVersion}
                onClick={() => void handleRollback()}
                className="inline-flex min-h-[44px] items-center justify-center gap-1.5 rounded-2xl border border-[#7C5CFF]/25 bg-[#7C5CFF]/10 px-3.5 text-xs font-medium text-[#4C3F91] transition hover:bg-[#7C5CFF]/20 disabled:cursor-not-allowed disabled:opacity-45 dark:text-[#E0DBFF]"
              >
                <RotateCcw className="h-4 w-4" aria-hidden="true" />
                <span>
                  Rollback
                  {engineStatus.previousVersion ? ` (${engineStatus.previousVersion})` : ''}
                </span>
              </button>
            </div>
          </>
        ) : (
          <p className="mt-2 text-xs text-[#5B5482] dark:text-[#A5A1C8]">
            Executado no navegador (Preview Web). Instale o APK Android do Devwolf Downloader para
            rodar o binário yt-dlp + FFmpeg diretamente no aparelho.
          </p>
        )}
      </section>

      {/* 3. gallery-dl / Resolvedores de Imagem */}
      <section className="dw-glass-card rounded-3xl p-4">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <ImageIcon
              className="h-4 w-4 text-[#7C5CFF] dark:text-[#A78BFA]"
              aria-hidden="true"
            />
            <h3 className="text-sm font-bold text-[#1E1940] dark:text-[#F5F3FF]">
              gallery-dl / Resolvedores de Imagem
            </h3>
          </div>
          <span
            className={`inline-flex items-center rounded-lg border px-2.5 py-0.5 text-xs font-semibold ${
              isNativeAndroid
                ? 'border-[#7C5CFF]/35 bg-[#7C5CFF]/15 text-[#5E43F3] dark:text-[#C4B5FD]'
                : 'border-[#7C5CFF]/20 bg-[#7C5CFF]/5 text-[#5B5482] dark:text-[#A5A1C8]'
            }`}
          >
            {isNativeAndroid ? 'Ativo' : 'Somente APK'}
          </span>
        </div>

        <dl className="mt-3 grid grid-cols-2 gap-x-3 gap-y-2 text-xs">
          <div>
            <dt className="text-[#5B5482] dark:text-[#A5A1C8]">Versão / Motor</dt>
            <dd className="font-mono font-medium text-[#1E1940] dark:text-[#F5F3FF]">
              {engineStatus?.gallerydlVersion || 'Nativo Kotlin + gallery-dl'}
            </dd>
          </div>
          <div>
            <dt className="text-[#5B5482] dark:text-[#A5A1C8]">Plataformas suportadas</dt>
            <dd className="font-medium text-[#1E1940] dark:text-[#F5F3FF]">
              Pinterest, Reddit, Carrosséis
            </dd>
          </div>
        </dl>
      </section>

      {/* Tabela centralizada de roteamento por plataforma */}
      <section className="dw-glass-card rounded-3xl p-4">
        <h3 className="text-sm font-bold text-[#1E1940] dark:text-[#F5F3FF]">
          Roteamento por plataforma
        </h3>
        <ul className="mt-3 space-y-2">
          {PLATFORM_ROUTING_TABLE.map((route) => (
            <li
              key={route.platform}
              className="dw-glass-subtle flex flex-wrap items-center justify-between gap-2 rounded-2xl px-3 py-2 text-xs"
            >
              <span className="font-semibold text-[#1E1940] dark:text-[#F5F3FF]">
                {route.label}
              </span>
              <span className="rounded-lg bg-[#7C5CFF]/15 px-2 py-0.5 font-mono text-[11px] font-medium text-[#5E43F3] dark:text-[#C4B5FD]">
                {route.routeDescription}
              </span>
            </li>
          ))}
        </ul>
      </section>

      <div className="dw-glass-card flex items-start gap-2.5 rounded-3xl p-3.5 text-xs text-[#5B5482] dark:text-[#A5A1C8]">
        <Info
          className="mt-0.5 h-4 w-4 shrink-0 text-[#7C5CFF] dark:text-[#A78BFA]"
          aria-hidden="true"
        />
        <span>
          Todos os motores executam diretamente no dispositivo Android, sem dependência de servidor
          remoto ou VPS.
        </span>
      </div>
    </div>
  );
}
