import { useCallback, useEffect, useState } from 'react';
import {
  Activity,
  Check,
  ChevronDown,
  ChevronUp,
  Copy,
  HardDrive,
  KeyRound,
  LogIn,
  ShieldCheck,
  Smartphone,
  Trash2,
} from 'lucide-react';
import { nativeBridge } from '../services/nativeBridge';
import type {
  DiagnosticsReport,
  JobResponse,
  PlatformSessionInfo,
  QualityOption,
  StatusSegmentDuration,
  ThemeMode,
  UpdateChannel,
  UserSettings,
} from '../types/api';
import { sanitizeDiagnosticText } from '../utils/errorContract';
import { formatBytes, formatDateTime, formatMs } from '../utils/format';

interface SettingsViewProps {
  settings: UserSettings;
  isNativeAndroid: boolean;
  latestJob: JobResponse | null;
  onUpdateSettings: (patch: Partial<UserSettings>) => Promise<void>;
}

export function SettingsView({
  settings,
  isNativeAndroid,
  latestJob,
  onUpdateSettings,
}: SettingsViewProps) {
  const [savingKey, setSavingKey] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [sessions, setSessions] = useState<PlatformSessionInfo[]>([]);
  const [showManualCookie, setShowManualCookie] = useState(false);
  const [manualCookie, setManualCookie] = useState('');
  const [cacheClearing, setCacheClearing] = useState(false);

  const [diagnosticsOpen, setDiagnosticsOpen] = useState(false);
  const [diagReport, setDiagReport] = useState<DiagnosticsReport | null>(null);
  const [diagCopied, setDiagCopied] = useState(false);

  const loadSessions = useCallback(async () => {
    if (!isNativeAndroid) return;
    const list = await nativeBridge.getPlatformSessions();
    setSessions(list);
  }, [isNativeAndroid]);

  const loadDiagnostics = useCallback(async () => {
    const [report, engineStatus] = await Promise.all([
      nativeBridge.getDiagnosticsReport(),
      isNativeAndroid ? nativeBridge.getLocalEngineStatus() : Promise.resolve(null),
    ]);

    if (report) {
      setDiagReport(report);
      return;
    }

    const metrics = latestJob?.metrics;
    setDiagReport({
      engineUsed: metrics?.engineUsed ?? latestJob?.current_engine ?? latestJob?.selected_engine ?? null,
      engineVersion: metrics?.engineVersion ?? latestJob?.engine_version ?? engineStatus?.ytdlpVersion ?? null,
      lastUpdateAt: engineStatus?.lastUpdateAt ?? null,
      strategyUsed: metrics?.strategyUsed ?? latestJob?.strategy_used ?? null,
      resolutionMs: metrics?.resolutionMs ?? null,
      ttfbMs: metrics?.ttfbMs ?? null,
      transferMs: metrics?.transferMs ?? null,
      processingMs: metrics?.processingMs ?? null,
      galleryPublishMs: metrics?.galleryPublishMs ?? null,
      totalJobMs: metrics?.totalJobMs ?? null,
      httpRequestCount: metrics?.httpRequestCount ?? null,
      errorCode: latestJob?.error_code ?? null,
      technicalReason: latestJob?.technical_reason ?? latestJob?.error_message ?? null,
      sanitizedLog: null,
      generatedAt: latestJob?.updated_at ?? null,
    });
  }, [isNativeAndroid, latestJob]);

  useEffect(() => {
    void loadSessions();
  }, [loadSessions]);

  useEffect(() => {
    if (diagnosticsOpen) {
      void loadDiagnostics();
    }
  }, [diagnosticsOpen, loadDiagnostics]);

  const patch = async (key: string, partial: Partial<UserSettings>) => {
    setSavingKey(key);
    setError(null);
    setNotice(null);
    try {
      await onUpdateSettings(partial);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Erro ao salvar configuração.');
    } finally {
      setSavingKey(null);
    }
  };

  const instagramSession = sessions.find((s) => s.platform === 'instagram' && s.configured);

  const handleOpenInstagramWebView = async () => {
    setError(null);
    setNotice(null);
    try {
      const res = await nativeBridge.openInstagramLoginWebView();
      await loadSessions();
      if (res.saved) {
        setNotice(res.message || 'Sessão opcional do Instagram salva com criptografia no aparelho.');
      } else if (res.message) {
        setNotice(res.message);
      }
    } catch (err) {
      setError(
        err instanceof Error ? err.message : 'Não foi possível abrir o WebView do Instagram.'
      );
    }
  };

  const handleSaveManualCookie = async () => {
    if (!manualCookie.trim()) {
      setError('Cole o cabeçalho de sessão/cookie antes de salvar no cofre local.');
      return;
    }
    setError(null);
    setNotice(null);
    try {
      await nativeBridge.savePlatformSession('instagram', manualCookie.trim());
      setManualCookie('');
      setShowManualCookie(false);
      await loadSessions();
      setNotice('Sessão opcional do Instagram salva com criptografia no dispositivo.');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Erro ao salvar sessão no cofre local.');
    }
  };

  const handleRevokeInstagramSession = async () => {
    setError(null);
    setNotice(null);
    try {
      await nativeBridge.revokePlatformSession('instagram');
      await loadSessions();
      setNotice('Sessão do Instagram encerrada e removida do aparelho.');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Erro ao excluir sessão.');
    }
  };

  const handleClearTempCache = async () => {
    setCacheClearing(true);
    setError(null);
    setNotice(null);
    try {
      const res = await nativeBridge.clearTempCache();
      setNotice(
        res.filesRemoved > 0
          ? `Cache limpo: ${res.filesRemoved} arquivo(s), ${formatBytes(res.freedBytes)} liberados.`
          : 'Nenhum arquivo temporário pendente para limpar.'
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Falha ao limpar cache temporário.');
    } finally {
      setCacheClearing(false);
    }
  };

  const handleCopySanitizedLog = async () => {
    const payload = {
      app: 'Devwolf Downloader',
      engineUsed: diagReport?.engineUsed ?? null,
      engineVersion: diagReport?.engineVersion ?? null,
      strategyUsed: diagReport?.strategyUsed ?? null,
      lastUpdateAt: diagReport?.lastUpdateAt ?? null,
      timingsMs: {
        resolution: diagReport?.resolutionMs ?? null,
        ttfb: diagReport?.ttfbMs ?? null,
        transfer: diagReport?.transferMs ?? null,
        processing: diagReport?.processingMs ?? null,
        galleryPublish: diagReport?.galleryPublishMs ?? null,
        total: diagReport?.totalJobMs ?? null,
      },
      httpRequestCount: diagReport?.httpRequestCount ?? null,
      errorCode: diagReport?.errorCode ?? null,
      technicalReason: diagReport?.technicalReason
        ? sanitizeDiagnosticText(diagReport.technicalReason)
        : null,
      sanitizedLog: diagReport?.sanitizedLog
        ? sanitizeDiagnosticText(diagReport.sanitizedLog)
        : null,
    };
    const text = sanitizeDiagnosticText(JSON.stringify(payload, null, 2));
    try {
      await navigator.clipboard.writeText(text);
      setDiagCopied(true);
      window.setTimeout(() => setDiagCopied(false), 2000);
    } catch {
      setError('Não foi possível copiar o log para a área de transferência.');
    }
  };

  return (
    <div className="space-y-4">
      <h2 className="text-base font-semibold text-zinc-900 dark:text-zinc-100">Ajustes</h2>

      {error && (
        <div
          role="alert"
          className="rounded-xl border border-red-500/30 bg-red-500/10 p-3 text-xs text-red-600 dark:text-red-300"
        >
          {error}
        </div>
      )}

      {notice && (
        <div className="rounded-xl border border-[#A3E635]/30 bg-[#A3E635]/10 p-3 text-xs text-[#4d7c0f] dark:text-[#A3E635]">
          {notice}
        </div>
      )}

      {/* Modo Status (WhatsApp) */}
      <section className="divide-y divide-zinc-200 rounded-2xl border border-zinc-200 bg-white dark:divide-zinc-800 dark:border-zinc-800 dark:bg-[#1C1C20]">
        <div className="flex items-center justify-between gap-4 p-4">
          <div>
            <p className="text-sm font-medium text-zinc-900 dark:text-zinc-100">
              Modo Status (WhatsApp)
            </p>
            <p className="text-xs text-zinc-500 dark:text-zinc-400">
              Garante H.264 + AAC em MP4 sem recompressão desnecessária
            </p>
          </div>
          <button
            type="button"
            role="switch"
            aria-checked={settings.status_mode_enabled}
            disabled={savingKey === 'status_mode_enabled'}
            onClick={() =>
              void patch('status_mode_enabled', {
                status_mode_enabled: !settings.status_mode_enabled,
              })
            }
            className={`min-h-[44px] min-w-[68px] rounded-xl px-3 text-xs font-bold transition ${
              settings.status_mode_enabled
                ? 'bg-[#A3E635] text-zinc-950'
                : 'border border-zinc-300 bg-zinc-100 text-zinc-600 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-400'
            }`}
          >
            {settings.status_mode_enabled ? 'ON' : 'OFF'}
          </button>
        </div>

        <div className="flex items-center justify-between gap-4 p-4">
          <div>
            <label
              htmlFor="setting-status-segments"
              className="text-sm font-medium text-zinc-900 dark:text-zinc-100"
            >
              Cortar vídeos longos
            </label>
            <p className="text-xs text-zinc-500 dark:text-zinc-400">
              Divide automaticamente em partes para publicar no Status
            </p>
          </div>
          <select
            id="setting-status-segments"
            value={settings.status_max_duration_seconds}
            onChange={(e) =>
              void patch('status_max_duration_seconds', {
                status_max_duration_seconds: Number(e.target.value) as StatusSegmentDuration,
              })
            }
            className="min-h-[44px] rounded-xl border border-zinc-300 bg-zinc-50 px-3 text-xs font-medium text-zinc-800 outline-none dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200"
          >
            <option value={0}>Desativado</option>
            <option value={30}>30 segundos</option>
            <option value={45}>45 segundos</option>
            <option value={60}>60 segundos</option>
          </select>
        </div>

        <div className="flex items-center justify-between gap-4 p-4">
          <div>
            <p className="text-sm font-medium text-zinc-900 dark:text-zinc-100">
              Manter arquivo original
            </p>
            <p className="text-xs text-zinc-500 dark:text-zinc-400">
              Preserva o vídeo completo além dos cortes do Modo Status
            </p>
          </div>
          <button
            type="button"
            role="switch"
            aria-checked={settings.status_keep_original}
            disabled={savingKey === 'status_keep_original'}
            onClick={() =>
              void patch('status_keep_original', {
                status_keep_original: !settings.status_keep_original,
              })
            }
            className={`min-h-[44px] min-w-[68px] rounded-xl px-3 text-xs font-bold transition ${
              settings.status_keep_original
                ? 'bg-[#A3E635] text-zinc-950'
                : 'border border-zinc-300 bg-zinc-100 text-zinc-600 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-400'
            }`}
          >
            {settings.status_keep_original ? 'ON' : 'OFF'}
          </button>
        </div>
      </section>

      {/* Preferências gerais */}
      <section className="divide-y divide-zinc-200 rounded-2xl border border-zinc-200 bg-white dark:divide-zinc-800 dark:border-zinc-800 dark:bg-[#1C1C20]">
        <div className="flex items-center justify-between gap-4 p-4">
          <div>
            <p className="text-sm font-medium text-zinc-900 dark:text-zinc-100">
              Download automático
            </p>
            <p className="text-xs text-zinc-500 dark:text-zinc-400">
              Iniciar download ao detectar link válido no clipboard ou compartilhamento
            </p>
          </div>
          <button
            type="button"
            role="switch"
            aria-checked={settings.auto_download}
            disabled={savingKey === 'auto_download'}
            onClick={() => void patch('auto_download', { auto_download: !settings.auto_download })}
            className={`min-h-[44px] min-w-[68px] rounded-xl px-3 text-xs font-bold transition ${
              settings.auto_download
                ? 'bg-[#A3E635] text-zinc-950'
                : 'border border-zinc-300 bg-zinc-100 text-zinc-600 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-400'
            }`}
          >
            {settings.auto_download ? 'ON' : 'OFF'}
          </button>
        </div>

        <div className="flex items-center justify-between gap-4 p-4">
          <div>
            <label
              htmlFor="setting-quality"
              className="text-sm font-medium text-zinc-900 dark:text-zinc-100"
            >
              Qualidade padrão
            </label>
            <p className="text-xs text-zinc-500 dark:text-zinc-400">
              Preferência aplicada aos novos downloads
            </p>
          </div>
          <select
            id="setting-quality"
            value={settings.default_quality}
            onChange={(e) =>
              void patch('default_quality', {
                default_quality: e.target.value as QualityOption,
              })
            }
            className="min-h-[44px] rounded-xl border border-zinc-300 bg-zinc-50 px-3 text-xs font-medium text-zinc-800 outline-none dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200"
          >
            <option value="best">Melhor disponível</option>
            <option value="1080p">1080p</option>
            <option value="720p">720p</option>
            <option value="480p">480p</option>
            <option value="audio">Somente áudio</option>
          </select>
        </div>

        <div className="flex items-center justify-between gap-4 p-4">
          <div>
            <p className="text-sm font-medium text-zinc-900 dark:text-zinc-100">
              Atualização automática do yt-dlp
            </p>
            <p className="text-xs text-zinc-500 dark:text-zinc-400">
              Verificar novas versões do motor local quando sem downloads ativos
            </p>
          </div>
          <button
            type="button"
            role="switch"
            aria-checked={settings.auto_update_engines}
            disabled={savingKey === 'auto_update_engines'}
            onClick={() =>
              void patch('auto_update_engines', {
                auto_update_engines: !settings.auto_update_engines,
              })
            }
            className={`min-h-[44px] min-w-[68px] rounded-xl px-3 text-xs font-bold transition ${
              settings.auto_update_engines
                ? 'bg-[#A3E635] text-zinc-950'
                : 'border border-zinc-300 bg-zinc-100 text-zinc-600 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-400'
            }`}
          >
            {settings.auto_update_engines ? 'ON' : 'OFF'}
          </button>
        </div>

        <div className="flex items-center justify-between gap-4 p-4">
          <div>
            <label
              htmlFor="setting-update-channel"
              className="text-sm font-medium text-zinc-900 dark:text-zinc-100"
            >
              Canal de atualização do yt-dlp
            </label>
            <p className="text-xs text-zinc-500 dark:text-zinc-400">
              Stable (recomendado) ou Nightly (correções recentes)
            </p>
          </div>
          <select
            id="setting-update-channel"
            value={settings.update_channel}
            onChange={(e) =>
              void patch('update_channel', {
                update_channel: e.target.value as UpdateChannel,
              })
            }
            className="min-h-[44px] rounded-xl border border-zinc-300 bg-zinc-50 px-3 text-xs font-medium text-zinc-800 outline-none dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200"
          >
            <option value="stable">Stable</option>
            <option value="nightly">Nightly</option>
          </select>
        </div>

        <div className="flex items-center justify-between gap-4 p-4">
          <div>
            <p className="text-sm font-medium text-zinc-900 dark:text-zinc-100">
              Notificações nativas
            </p>
            <p className="text-xs text-zinc-500 dark:text-zinc-400">
              Exibir progresso e conclusão em segundo plano no Android
            </p>
          </div>
          <button
            type="button"
            role="switch"
            aria-checked={settings.notifications_enabled}
            disabled={savingKey === 'notifications_enabled'}
            onClick={() =>
              void patch('notifications_enabled', {
                notifications_enabled: !settings.notifications_enabled,
              })
            }
            className={`min-h-[44px] min-w-[68px] rounded-xl px-3 text-xs font-bold transition ${
              settings.notifications_enabled
                ? 'bg-[#A3E635] text-zinc-950'
                : 'border border-zinc-300 bg-zinc-100 text-zinc-600 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-400'
            }`}
          >
            {settings.notifications_enabled ? 'ON' : 'OFF'}
          </button>
        </div>

        <div className="flex items-center justify-between gap-4 p-4">
          <div>
            <p className="text-sm font-medium text-zinc-900 dark:text-zinc-100">
              Economia de dados
            </p>
            <p className="text-xs text-zinc-500 dark:text-zinc-400">
              Priorizar arquivos menores (até 720p) quando disponível
            </p>
          </div>
          <button
            type="button"
            role="switch"
            aria-checked={settings.data_saver}
            disabled={savingKey === 'data_saver'}
            onClick={() => void patch('data_saver', { data_saver: !settings.data_saver })}
            className={`min-h-[44px] min-w-[68px] rounded-xl px-3 text-xs font-bold transition ${
              settings.data_saver
                ? 'bg-[#A3E635] text-zinc-950'
                : 'border border-zinc-300 bg-zinc-100 text-zinc-600 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-400'
            }`}
          >
            {settings.data_saver ? 'ON' : 'OFF'}
          </button>
        </div>

        <div className="flex items-center justify-between gap-4 p-4">
          <div>
            <label
              htmlFor="setting-theme"
              className="text-sm font-medium text-zinc-900 dark:text-zinc-100"
            >
              Tema da interface
            </label>
            <p className="text-xs text-zinc-500 dark:text-zinc-400">
              Padrão escuro para uso mobile
            </p>
          </div>
          <select
            id="setting-theme"
            value={settings.theme}
            onChange={(e) => void patch('theme', { theme: e.target.value as ThemeMode })}
            className="min-h-[44px] rounded-xl border border-zinc-300 bg-zinc-50 px-3 text-xs font-medium text-zinc-800 outline-none dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200"
          >
            <option value="dark">Escuro</option>
            <option value="light">Claro</option>
          </select>
        </div>
      </section>

      {/* Sessão opcional do Instagram */}
      <section className="rounded-2xl border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-[#1C1C20]">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <KeyRound className="h-4 w-4 text-[#65a30d] dark:text-[#A3E635]" aria-hidden="true" />
            <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
              Sessão opcional do Instagram
            </h3>
          </div>
          <span
            className={`rounded-md px-2 py-0.5 text-[11px] font-medium ${
              instagramSession
                ? 'bg-[#A3E635]/15 text-[#4d7c0f] dark:text-[#A3E635]'
                : 'bg-zinc-100 text-zinc-600 dark:bg-[#101012] dark:text-zinc-400'
            }`}
          >
            {instagramSession ? 'Conectada' : 'Opcional'}
          </span>
        </div>

        <p className="mt-1.5 flex items-start gap-1.5 text-xs text-zinc-500 dark:text-zinc-400">
          <ShieldCheck
            className="mt-0.5 h-3.5 w-3.5 shrink-0 text-[#65a30d] dark:text-[#A3E635]"
            aria-hidden="true"
          />
          <span>
            Mídias públicas não exigem login. Caso queira baixar publicações que exijam sessão
            autenticada, os cookies ficam criptografados exclusivamente no aparelho
            (EncryptedSharedPreferences).
          </span>
        </p>

        <div className="mt-3 flex flex-wrap gap-2">
          <button
            type="button"
            disabled={!isNativeAndroid}
            onClick={() => void handleOpenInstagramWebView()}
            className="inline-flex min-h-[44px] flex-1 items-center justify-center gap-1.5 rounded-xl bg-[#A3E635] px-4 text-xs font-semibold text-zinc-950 transition hover:bg-[#84CC16] disabled:cursor-not-allowed disabled:opacity-45"
          >
            <LogIn className="h-4 w-4" aria-hidden="true" />
            <span>Entrar com WebView Oficial do Instagram</span>
          </button>

          {instagramSession && (
            <button
              type="button"
              onClick={() => void handleRevokeInstagramSession()}
              className="inline-flex min-h-[44px] items-center justify-center gap-1.5 rounded-xl border border-red-500/30 bg-red-500/10 px-3.5 text-xs font-semibold text-red-600 transition hover:bg-red-500/20 dark:text-red-300"
            >
              <Trash2 className="h-4 w-4" aria-hidden="true" />
              <span>Encerrar e Excluir Sessão</span>
            </button>
          )}
        </div>

        {isNativeAndroid && (
          <div className="mt-2.5">
            <button
              type="button"
              onClick={() => setShowManualCookie((v) => !v)}
              className="text-xs font-medium text-zinc-500 underline-offset-2 hover:underline dark:text-zinc-400"
            >
              {showManualCookie ? 'Ocultar entrada manual' : 'Inserir cookie manualmente (opcional)'}
            </button>

            {showManualCookie && (
              <div className="mt-2 flex gap-2">
                <input
                  type="password"
                  aria-label="Cookie de sessão local do Instagram"
                  placeholder="sessionid=...; csrftoken=..."
                  value={manualCookie}
                  onChange={(e) => setManualCookie(e.target.value)}
                  className="min-h-[44px] flex-1 rounded-xl border border-zinc-300 bg-zinc-50 px-3 text-xs text-zinc-900 outline-none focus:border-[#A3E635] dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-100"
                />
                <button
                  type="button"
                  onClick={() => void handleSaveManualCookie()}
                  className="min-h-[44px] rounded-xl border border-zinc-300 bg-zinc-100 px-3.5 text-xs font-semibold text-zinc-800 transition hover:bg-zinc-200 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200"
                >
                  Salvar
                </button>
              </div>
            )}
          </div>
        )}
      </section>

      {/* Limpeza de cache temporário */}
      <section className="flex items-center justify-between gap-4 rounded-2xl border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-[#1C1C20]">
        <div className="flex items-center gap-2.5">
          <HardDrive className="h-4 w-4 text-zinc-400" aria-hidden="true" />
          <div>
            <p className="text-sm font-medium text-zinc-900 dark:text-zinc-100">
              Cache temporário
            </p>
            <p className="text-xs text-zinc-500 dark:text-zinc-400">
              Remove arquivos parciais e temporários de conversão
            </p>
          </div>
        </div>
        <button
          type="button"
          disabled={cacheClearing}
          onClick={() => void handleClearTempCache()}
          className="min-h-[44px] shrink-0 rounded-xl border border-zinc-300 bg-zinc-50 px-3.5 text-xs font-medium text-zinc-800 transition hover:bg-zinc-200 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200 dark:hover:bg-zinc-800"
        >
          {cacheClearing ? 'Limpando…' : 'Limpar cache'}
        </button>
      </section>

      {/* Área de Diagnóstico e Performance (colapsável) */}
      <section className="rounded-2xl border border-zinc-200 bg-white dark:border-zinc-800 dark:bg-[#1C1C20]">
        <button
          type="button"
          aria-expanded={diagnosticsOpen}
          onClick={() => setDiagnosticsOpen((open) => !open)}
          className="flex min-h-[52px] w-full items-center justify-between gap-3 px-4 py-3 text-left"
        >
          <div className="flex items-center gap-2.5">
            <Activity className="h-4 w-4 text-[#65a30d] dark:text-[#A3E635]" aria-hidden="true" />
            <div>
              <p className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
                Diagnóstico e Performance
              </p>
              <p className="text-xs text-zinc-500 dark:text-zinc-400">
                Métricas locais do último download e log sanitizado
              </p>
            </div>
          </div>
          {diagnosticsOpen ? (
            <ChevronUp className="h-4 w-4 text-zinc-400" aria-hidden="true" />
          ) : (
            <ChevronDown className="h-4 w-4 text-zinc-400" aria-hidden="true" />
          )}
        </button>

        {diagnosticsOpen && (
          <div className="border-t border-zinc-200 px-4 pb-4 pt-3 dark:border-zinc-800">
            <dl className="grid grid-cols-2 gap-x-3 gap-y-2 text-xs">
              <div>
                <dt className="text-zinc-500 dark:text-zinc-400">Motor utilizado</dt>
                <dd className="font-mono font-medium text-zinc-800 dark:text-zinc-200">
                  {diagReport?.engineUsed || '—'}
                </dd>
              </div>
              <div>
                <dt className="text-zinc-500 dark:text-zinc-400">Versão do motor</dt>
                <dd className="font-mono font-medium text-zinc-800 dark:text-zinc-200">
                  {diagReport?.engineVersion || '—'}
                </dd>
              </div>
              <div>
                <dt className="text-zinc-500 dark:text-zinc-400">Estratégia</dt>
                <dd className="font-mono font-medium text-zinc-800 dark:text-zinc-200">
                  {diagReport?.strategyUsed || '—'}
                </dd>
              </div>
              <div>
                <dt className="text-zinc-500 dark:text-zinc-400">Última atualização</dt>
                <dd className="font-medium text-zinc-800 dark:text-zinc-200">
                  {formatDateTime(diagReport?.lastUpdateAt)}
                </dd>
              </div>
              <div>
                <dt className="text-zinc-500 dark:text-zinc-400">Resolução</dt>
                <dd className="font-mono text-zinc-800 dark:text-zinc-200">
                  {formatMs(diagReport?.resolutionMs)}
                </dd>
              </div>
              <div>
                <dt className="text-zinc-500 dark:text-zinc-400">TTFB</dt>
                <dd className="font-mono text-zinc-800 dark:text-zinc-200">
                  {formatMs(diagReport?.ttfbMs)}
                </dd>
              </div>
              <div>
                <dt className="text-zinc-500 dark:text-zinc-400">Transferência</dt>
                <dd className="font-mono text-zinc-800 dark:text-zinc-200">
                  {formatMs(diagReport?.transferMs)}
                </dd>
              </div>
              <div>
                <dt className="text-zinc-500 dark:text-zinc-400">Galeria (MediaStore)</dt>
                <dd className="font-mono text-zinc-800 dark:text-zinc-200">
                  {formatMs(diagReport?.galleryPublishMs)}
                </dd>
              </div>
              <div>
                <dt className="text-zinc-500 dark:text-zinc-400">Requisições HTTP</dt>
                <dd className="font-mono text-zinc-800 dark:text-zinc-200">
                  {diagReport?.httpRequestCount !== null && diagReport?.httpRequestCount !== undefined
                    ? diagReport.httpRequestCount
                    : '—'}
                </dd>
              </div>
              <div>
                <dt className="text-zinc-500 dark:text-zinc-400">Tempo total</dt>
                <dd className="font-mono text-zinc-800 dark:text-zinc-200">
                  {formatMs(diagReport?.totalJobMs)}
                </dd>
              </div>
            </dl>

            {(diagReport?.errorCode || diagReport?.technicalReason) && (
              <div className="mt-3 rounded-xl border border-red-500/25 bg-red-500/10 p-2.5 text-xs text-red-600 dark:text-red-300">
                <p className="font-mono font-semibold">
                  {diagReport.errorCode || 'ERRO'}
                </p>
                {diagReport.technicalReason && (
                  <p className="mt-0.5 break-words">
                    {sanitizeDiagnosticText(diagReport.technicalReason)}
                  </p>
                )}
              </div>
            )}

            <button
              type="button"
              onClick={() => void handleCopySanitizedLog()}
              className="mt-3 inline-flex min-h-[44px] w-full items-center justify-center gap-1.5 rounded-xl border border-zinc-300 bg-zinc-50 px-4 text-xs font-medium text-zinc-800 transition hover:bg-zinc-200 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200 dark:hover:bg-zinc-800"
            >
              {diagCopied ? (
                <>
                  <Check className="h-4 w-4 text-[#65a30d] dark:text-[#A3E635]" aria-hidden="true" />
                  <span>Log sanitizado copiado!</span>
                </>
              ) : (
                <>
                  <Copy className="h-4 w-4" aria-hidden="true" />
                  <span>Copiar log sanitizado</span>
                </>
              )}
            </button>
          </div>
        )}
      </section>

      <section className="flex items-start gap-2.5 rounded-2xl border border-zinc-200 bg-white p-4 text-xs text-zinc-600 dark:border-zinc-800 dark:bg-[#1C1C20] dark:text-zinc-400">
        <Smartphone className="mt-0.5 h-4 w-4 shrink-0 text-[#65a30d] dark:text-[#A3E635]" aria-hidden="true" />
        <p className="leading-relaxed">
          O Devwolf Downloader executa 100% localmente no Android, recebe links diretamente do menu
          Compartilhar e salva fotos e vídeos no álbum &ldquo;Devwolf Downloader&rdquo; da Galeria.
        </p>
      </section>
    </div>
  );
}
