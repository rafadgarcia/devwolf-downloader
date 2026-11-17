import { useCallback, useEffect, useState } from 'react';
import { AlertTriangle, Cpu, Info, RefreshCw, ShieldAlert } from 'lucide-react';
import { api } from '../services/api';
import type { CobaltUpdaterStatus, EngineHealthState, EngineInfo } from '../types/api';
import { formatDateTime, getEngineHealthLabel } from '../utils/format';

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

export function EnginesView() {
  const [engines, setEngines] = useState<EngineInfo[]>([]);
  const [updater, setUpdater] = useState<CobaltUpdaterStatus | null>(null);
  const [disclaimer, setDisclaimer] = useState(
    'Online significa processo respondendo, não garantia de que todas as plataformas funcionam.'
  );
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadEngines = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await api.getEngines();
      setEngines(res.engines ?? []);
      if (res.disclaimer) {
        setDisclaimer(res.disclaimer);
      }
      const cobaltEngine = res.engines?.find((e) => e.engine === 'cobalt');
      setUpdater(cobaltEngine?.updater_status ?? null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Erro ao consultar motores.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadEngines();
  }, [loadEngines]);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-base font-semibold text-zinc-900 dark:text-zinc-100">Motores</h2>
        <button
          type="button"
          onClick={() => void loadEngines()}
          className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl px-3 text-xs font-medium text-zinc-600 transition hover:bg-zinc-200/70 dark:text-zinc-300 dark:hover:bg-[#1C1C20]"
        >
          <RefreshCw className="h-4 w-4" aria-hidden="true" />
          <span>Atualizar</span>
        </button>
      </div>

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

      {loading ? (
        <div className="rounded-2xl border border-zinc-200 bg-white p-6 text-center text-xs text-zinc-500 dark:border-zinc-800 dark:bg-[#1C1C20] dark:text-zinc-400">
          Verificando saúde dos motores…
        </div>
      ) : (
        <div className="space-y-3">
          {engines.map((engine) => {
            const badge = getEngineHealthLabel(normalizeHealth(engine.health));
            return (
              <article
                key={engine.engine}
                className="rounded-2xl border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-[#1C1C20]"
              >
                <div className="flex items-center justify-between gap-2">
                  <div className="flex items-center gap-2">
                    <Cpu className="h-4 w-4 text-zinc-400" aria-hidden="true" />
                    <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
                      {engine.engine}
                    </h3>
                    {engine.installed_version && (
                      <span className="rounded-md bg-zinc-100 px-2 py-0.5 text-[11px] font-mono text-zinc-600 dark:bg-[#101012] dark:text-zinc-300">
                        {engine.installed_version}
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
                  {updater.rejected_digest ? ` Digest rejeitado: ${updater.rejected_digest.slice(0, 19)}…` : ''}
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
    </div>
  );
}
