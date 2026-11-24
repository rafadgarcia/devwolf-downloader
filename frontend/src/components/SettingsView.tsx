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
  Trash2,
} from 'lucide-react';
import { nativeBridge } from '../services/nativeBridge';
import { AppUpdatesSection } from './AppUpdatesSection';
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

type ManagedAccountPlatform = 'instagram' | 'reddit' | 'threads';

const ACCOUNT_PLATFORMS: Array<{
  id: ManagedAccountPlatform;
  label: string;
  description: string;
  cookiePlaceholder: string;
}> = [
  {
    id: 'instagram',
    label: 'Instagram',
    description:
      'Entre na sua conta para baixar mídias que exigem sessão autenticada. A sessão fica salva e criptografada no aparelho.',
    cookiePlaceholder: 'sessionid=...; csrftoken=...',
  },
  {
    id: 'reddit',
    label: 'Reddit',
    description:
      'Entre na sua conta para baixar mídias que exigem sessão autenticada. A sessão fica salva e criptografada no aparelho.',
    cookiePlaceholder: 'reddit_session=...; token_v2=...',
  },
  {
    id: 'threads',
    label: 'Threads',
    description:
      'Entre na sua conta para baixar mídias que exigem sessão autenticada. A sessão fica salva e criptografada no aparelho.',
    cookiePlaceholder: 'sessionid=...; csrftoken=...',
  },
];

interface SettingsViewProps {
  settings: UserSettings;
  isNativeAndroid: boolean;
  latestJob: JobResponse | null;
  focusAccountPlatform?: string | null;
  scrollToAccounts?: boolean;
  onClearFocusAccount?: () => void;
  onUpdateSettings: (patch: Partial<UserSettings>) => Promise<void>;
}

export function SettingsView({
  settings,
  isNativeAndroid,
  latestJob,
  focusAccountPlatform,
  scrollToAccounts,
  onClearFocusAccount,
  onUpdateSettings,
}: SettingsViewProps) {
  const [savingKey, setSavingKey] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [sessions, setSessions] = useState<PlatformSessionInfo[]>([]);
  const [manualOpenFor, setManualOpenFor] = useState<ManagedAccountPlatform | null>(null);
  const [manualCookies, setManualCookies] = useState<Record<ManagedAccountPlatform, string>>({
    instagram: '',
    reddit: '',
    threads: '',
  });
  const [cacheClearing, setCacheClearing] = useState(false);

  const [diagnosticsOpen, setDiagnosticsOpen] = useState(false);
  const [diagReport, setDiagReport] = useState<DiagnosticsReport | null>(null);
  const [diagCopied, setDiagCopied] = useState(false);
  const [bubbleStatus, setBubbleStatus] = useState({ enabled: false, permissionGranted: false });

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
    const itemCount = latestJob?.items?.length ?? latestJob?.files?.length ?? 0;
    const fallbackMediaType =
      itemCount > 1
        ? `carousel (${itemCount} itens)`
        : latestJob?.items?.[0]?.type ?? latestJob?.files?.[0]?.type ?? 'unknown';

    setDiagReport({
      platform: metrics?.platform ?? latestJob?.platform ?? null,
      engineUsed:
        metrics?.engineUsed ?? latestJob?.current_engine ?? latestJob?.selected_engine ?? null,
      engineVersion:
        metrics?.engineVersion ?? latestJob?.engine_version ?? engineStatus?.ytdlpVersion ?? null,
      lastUpdateAt: engineStatus?.lastUpdateAt ?? null,
      strategyUsed: metrics?.strategyUsed ?? latestJob?.strategy_used ?? null,
      strategyAttempts: metrics?.strategyAttempts ?? [],
      httpStatus: metrics?.httpStatus ?? null,
      exitCode: metrics?.exitCode ?? null,
      rawErrorSanitized: metrics?.rawErrorSanitized ?? latestJob?.technical_reason ?? null,
      mediaTypeIdentified: metrics?.mediaTypeIdentified ?? fallbackMediaType,
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
    if (isNativeAndroid) void nativeBridge.getClipboardBubbleStatus().then(setBubbleStatus).catch(() => undefined);
  }, [isNativeAndroid]);


  useEffect(() => {
    if (diagnosticsOpen) {
      void loadDiagnostics();
    }
  }, [diagnosticsOpen, loadDiagnostics]);

  useEffect(() => {
    if (!focusAccountPlatform && !scrollToAccounts) return;
    const targetId = focusAccountPlatform
      ? `session-card-${focusAccountPlatform}`
      : 'contas-e-sessoes';
    const el = document.getElementById(targetId);
    if (el && typeof el.scrollIntoView === 'function') {
      el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
    const timer = window.setTimeout(() => {
      onClearFocusAccount?.();
    }, 3500);
    return () => window.clearTimeout(timer);
  }, [focusAccountPlatform, scrollToAccounts, onClearFocusAccount]);

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

  const handleConnectPlatform = async (platform: ManagedAccountPlatform) => {
    setError(null);
    setNotice(null);
    try {
      const res = await nativeBridge.openPlatformLoginWebView({ platform });
      await loadSessions();
      if (res.saved) {
        setNotice(res.message || `Sessão de ${platform} salva com criptografia no aparelho.`);
      } else if (res.message) {
        setNotice(res.message);
      }
    } catch (err) {
      setError(
        err instanceof Error ? err.message : `Não foi possível abrir o login de ${platform}.`
      );
    }
  };

  const handleSaveManualCookie = async (platform: ManagedAccountPlatform) => {
    const raw = (manualCookies[platform] ?? '').trim();
    if (!raw) {
      setError('Cole o cabeçalho de sessão/cookie antes de salvar no cofre.');
      return;
    }
    setError(null);
    setNotice(null);
    try {
      await nativeBridge.savePlatformSession(platform, raw);
      setManualCookies((prev) => ({ ...prev, [platform]: '' }));
      setManualOpenFor(null);
      await loadSessions();
      setNotice(`Sessão opcional de ${platform} salva com criptografia no dispositivo.`);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Erro ao salvar sessão no cofre.');
    }
  };

  const handleRevokePlatformSession = async (platform: ManagedAccountPlatform, label: string) => {
    setError(null);
    setNotice(null);
    try {
      await nativeBridge.revokePlatformSession(platform);
      await loadSessions();
      setNotice(`Sessão de ${label} encerrada e credenciais excluídas do aparelho.`);
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
      platform: diagReport?.platform ?? null,
      engineUsed: diagReport?.engineUsed ?? null,
      engineVersion: diagReport?.engineVersion ?? null,
      strategyUsed: diagReport?.strategyUsed ?? null,
      strategyAttempts: diagReport?.strategyAttempts ?? [],
      httpStatus: diagReport?.httpStatus ?? null,
      exitCode: diagReport?.exitCode ?? null,
      rawErrorSanitized: diagReport?.rawErrorSanitized
        ? sanitizeDiagnosticText(diagReport.rawErrorSanitized)
        : null,
      errorCode: diagReport?.errorCode ?? null,
      mediaTypeIdentified: diagReport?.mediaTypeIdentified ?? null,
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

  const toggleClass = (active: boolean) =>
    `min-h-[44px] min-w-[68px] rounded-xl px-3 text-xs font-bold transition ${
      active
        ? 'dw-btn-primary'
        : 'border border-[#7C5CFF]/25 bg-[#7C5CFF]/10 text-[#5B5482] dark:text-[#A5A1C8]'
    }`;

  return (
    <div className="space-y-4 pb-8">
      <h2 className="text-base font-bold text-[#1E1940] dark:text-[#F5F3FF]">Configurações</h2>

      {error && (
        <div
          role="alert"
          className="rounded-2xl border border-rose-500/30 bg-rose-500/10 p-3 text-xs text-rose-600 dark:text-rose-300"
        >
          {error}
        </div>
      )}

      {notice && (
        <div className="rounded-2xl border border-[#7C5CFF]/30 bg-[#7C5CFF]/15 p-3 text-xs text-[#5E43F3] dark:text-[#C4B5FD]">
          {notice}
        </div>
      )}

      {/* Modo Status (WhatsApp) — exclusivo das Configurações */}
      <section className="dw-glass-card divide-y divide-[#7C5CFF]/15 rounded-3xl">
        <div className="flex items-center justify-between gap-4 p-4">
          <div>
            <p className="text-sm font-semibold text-[#1E1940] dark:text-[#F5F3FF]">
              Modo Status (WhatsApp)
            </p>
            <p className="text-xs text-[#5B5482] dark:text-[#A5A1C8]">
              Garante H.264 + AAC em MP4 compatível sem recompressão desnecessária
            </p>
          </div>
          <button
            type="button"
            role="switch"
            aria-label="Modo Status (WhatsApp)"
            aria-checked={settings.status_mode_enabled}
            disabled={savingKey === 'status_mode_enabled'}
            onClick={() =>
              void patch('status_mode_enabled', {
                status_mode_enabled: !settings.status_mode_enabled,
              })
            }
            className={toggleClass(settings.status_mode_enabled)}
          >
            {settings.status_mode_enabled ? 'ON' : 'OFF'}
          </button>
        </div>

        <div className="flex items-center justify-between gap-4 p-4">
          <div>
            <label
              htmlFor="setting-status-segments"
              className="text-sm font-semibold text-[#1E1940] dark:text-[#F5F3FF]"
            >
              Cortar vídeos longos
            </label>
            <p className="text-xs text-[#5B5482] dark:text-[#A5A1C8]">
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
            className="min-h-[44px] rounded-xl border border-[#7C5CFF]/25 bg-white/80 px-3 text-xs font-medium text-[#1E1940] outline-none dark:bg-[#0D0B1E]/80 dark:text-[#E0DBFF]"
          >
            <option value={0}>Desativado</option>
            <option value={30}>30 segundos</option>
            <option value={45}>45 segundos</option>
            <option value={60}>60 segundos</option>
          </select>
        </div>

        <div className="flex items-center justify-between gap-4 p-4">
          <div>
            <p className="text-sm font-semibold text-[#1E1940] dark:text-[#F5F3FF]">
              Manter arquivo original
            </p>
            <p className="text-xs text-[#5B5482] dark:text-[#A5A1C8]">
              Preserva o vídeo completo além dos cortes do Modo Status
            </p>
          </div>
          <button
            type="button"
            role="switch"
            aria-label="Manter arquivo original"
            aria-checked={settings.status_keep_original}
            disabled={savingKey === 'status_keep_original'}
            onClick={() =>
              void patch('status_keep_original', {
                status_keep_original: !settings.status_keep_original,
              })
            }
            className={toggleClass(settings.status_keep_original)}
          >
            {settings.status_keep_original ? 'ON' : 'OFF'}
          </button>
        </div>
      </section>

      {/* Preferências gerais + Tema (exclusivo das Configurações) */}
      <section className="dw-glass-card space-y-3 rounded-3xl p-4">
        <h3 className="text-sm font-bold text-[#1E1940] dark:text-[#F5F3FF]">Atalhos do Android</h3>
        <div className="flex items-center justify-between gap-4">
          <div>
            <p className="text-sm font-semibold">Bolha do clipboard</p>
            <p className="text-xs text-[#5B5482] dark:text-[#A5A1C8]">Toque na bolha para verificar o link copiado. O Android pedirá autorização de sobreposição.</p>
          </div>
          <button type="button" role="switch" aria-label="Bolha do clipboard" aria-checked={bubbleStatus.enabled}
            disabled={!isNativeAndroid || savingKey === 'clipboard_bubble_enabled'}
            onClick={() => {
              setSavingKey('clipboard_bubble_enabled'); setError(null);
              void nativeBridge.setClipboardBubbleEnabled(!bubbleStatus.enabled).then(async (enabled) => {
                setBubbleStatus(await nativeBridge.getClipboardBubbleStatus());
                await patch('clipboard_bubble_enabled', { clipboard_bubble_enabled: enabled });
              }).catch((err) => setError(err instanceof Error ? err.message : 'Não foi possível ativar a bolha.'))
                .finally(() => setSavingKey(null));
            }} className={toggleClass(bubbleStatus.enabled)}>
            {bubbleStatus.enabled ? 'ON' : 'OFF'}
          </button>
        </div>
      </section>

      <section className="dw-glass-card divide-y divide-[#7C5CFF]/15 rounded-3xl">
        <div className="flex items-center justify-between gap-4 p-4">
          <div>
            <p className="text-sm font-semibold text-[#1E1940] dark:text-[#F5F3FF]">
              Download automático
            </p>
            <p className="text-xs text-[#5B5482] dark:text-[#A5A1C8]">
              Iniciar download ao detectar link válido no clipboard ou compartilhamento
            </p>
          </div>
          <button
            type="button"
            role="switch"
            aria-label="Download automático"
            aria-checked={settings.auto_download}
            disabled={savingKey === 'auto_download'}
            onClick={() => void patch('auto_download', { auto_download: !settings.auto_download })}
            className={toggleClass(settings.auto_download)}
          >
            {settings.auto_download ? 'ON' : 'OFF'}
          </button>
        </div>

        <div className="flex items-center justify-between gap-4 p-4">
          <div>
            <label
              htmlFor="setting-quality"
              className="text-sm font-semibold text-[#1E1940] dark:text-[#F5F3FF]"
            >
              Qualidade padrão
            </label>
            <p className="text-xs text-[#5B5482] dark:text-[#A5A1C8]">
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
            className="min-h-[44px] rounded-xl border border-[#7C5CFF]/25 bg-white/80 px-3 text-xs font-medium text-[#1E1940] outline-none dark:bg-[#0D0B1E]/80 dark:text-[#E0DBFF]"
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
            <p className="text-sm font-semibold text-[#1E1940] dark:text-[#F5F3FF]">
              Atualização automática do yt-dlp
            </p>
            <p className="text-xs text-[#5B5482] dark:text-[#A5A1C8]">
              Verificar novas versões do motor quando sem downloads ativos
            </p>
          </div>
          <button
            type="button"
            role="switch"
            aria-label="Atualização automática do yt-dlp"
            aria-checked={settings.auto_update_engines}
            disabled={savingKey === 'auto_update_engines'}
            onClick={() =>
              void patch('auto_update_engines', {
                auto_update_engines: !settings.auto_update_engines,
              })
            }
            className={toggleClass(settings.auto_update_engines)}
          >
            {settings.auto_update_engines ? 'ON' : 'OFF'}
          </button>
        </div>

        <div className="flex items-center justify-between gap-4 p-4">
          <div>
            <label
              htmlFor="setting-update-channel"
              className="text-sm font-semibold text-[#1E1940] dark:text-[#F5F3FF]"
            >
              Canal de atualização do yt-dlp
            </label>
            <p className="text-xs text-[#5B5482] dark:text-[#A5A1C8]">
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
            className="min-h-[44px] rounded-xl border border-[#7C5CFF]/25 bg-white/80 px-3 text-xs font-medium text-[#1E1940] outline-none dark:bg-[#0D0B1E]/80 dark:text-[#E0DBFF]"
          >
            <option value="stable">Stable</option>
            <option value="nightly">Nightly</option>
          </select>
        </div>

        <div className="flex items-center justify-between gap-4 p-4">
          <div>
            <p className="text-sm font-semibold text-[#1E1940] dark:text-[#F5F3FF]">
              Notificações nativas
            </p>
            <p className="text-xs text-[#5B5482] dark:text-[#A5A1C8]">
              Exibir progresso e conclusão em segundo plano no Android
            </p>
          </div>
          <button
            type="button"
            role="switch"
            aria-label="Notificações nativas"
            aria-checked={settings.notifications_enabled}
            disabled={savingKey === 'notifications_enabled'}
            onClick={() =>
              void patch('notifications_enabled', {
                notifications_enabled: !settings.notifications_enabled,
              })
            }
            className={toggleClass(settings.notifications_enabled)}
          >
            {settings.notifications_enabled ? 'ON' : 'OFF'}
          </button>
        </div>

        <div className="flex items-center justify-between gap-4 p-4">
          <div>
            <p className="text-sm font-semibold text-[#1E1940] dark:text-[#F5F3FF]">
              Economia de dados
            </p>
            <p className="text-xs text-[#5B5482] dark:text-[#A5A1C8]">
              Priorizar arquivos menores (até 720p) quando disponível
            </p>
          </div>
          <button
            type="button"
            role="switch"
            aria-label="Economia de dados"
            aria-checked={settings.data_saver}
            disabled={savingKey === 'data_saver'}
            onClick={() => void patch('data_saver', { data_saver: !settings.data_saver })}
            className={toggleClass(settings.data_saver)}
          >
            {settings.data_saver ? 'ON' : 'OFF'}
          </button>
        </div>

        <div className="flex items-center justify-between gap-4 p-4">
          <div>
            <label
              htmlFor="setting-theme"
              className="text-sm font-semibold text-[#1E1940] dark:text-[#F5F3FF]"
            >
              Tema da interface
            </label>
            <p className="text-xs text-[#5B5482] dark:text-[#A5A1C8]">
              Escuro Roxo (padrão) ou Claro
            </p>
          </div>
          <select
            id="setting-theme"
            value={settings.theme}
            onChange={(e) => void patch('theme', { theme: e.target.value as ThemeMode })}
            className="min-h-[44px] rounded-xl border border-[#7C5CFF]/25 bg-white/80 px-3 text-xs font-medium text-[#1E1940] outline-none dark:bg-[#0D0B1E]/80 dark:text-[#E0DBFF]"
          >
            <option value="dark">Escuro Roxo</option>
            <option value="light">Claro</option>
          </select>
        </div>
      </section>

      {/* Contas e Sessões (Instagram, Reddit, Threads) */}
      <section id="contas-e-sessoes" className="dw-glass-card space-y-3 rounded-3xl p-4">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <KeyRound className="h-4 w-4 text-[#7C5CFF] dark:text-[#A78BFA]" aria-hidden="true" />
            <h3 className="text-sm font-bold text-[#1E1940] dark:text-[#F5F3FF]">
              Contas e Sessões
            </h3>
          </div>
          <span className="rounded-lg bg-[#7C5CFF]/15 px-2 py-0.5 text-[11px] font-medium text-[#5E43F3] dark:text-[#C4B5FD]">
            Cofre criptografado
          </span>
        </div>

        <p className="flex items-start gap-1.5 text-xs text-[#5B5482] dark:text-[#A5A1C8]">
          <ShieldCheck
            className="mt-0.5 h-3.5 w-3.5 shrink-0 text-[#7C5CFF] dark:text-[#A78BFA]"
            aria-hidden="true"
          />
          <span>
            Mídias públicas não exigem login. Quando uma plataforma exigir sessão autenticada,
            conecte a conta correspondente abaixo. Cookies e sessões ficam criptografados
            exclusivamente no aparelho (EncryptedSharedPreferences) e isolados por plataforma.
          </span>
        </p>

        <div className="space-y-3">
          {ACCOUNT_PLATFORMS.map((account) => {
            const session = sessions.find((s) => s.platform === account.id);
            const connected = Boolean(session?.configured);
            const isFocused = focusAccountPlatform === account.id;
            const authMethodLabel =
              session?.authType === 'oauth_token'
                ? 'OAuth2 PKCE'
                : session?.authType === 'webview_cookie'
                ? 'WebView'
                : 'Cookie manual';

            return (
              <div
                key={account.id}
                id={`session-card-${account.id}`}
                className={`rounded-2xl border p-3.5 transition ${
                  isFocused
                    ? 'border-[#7C5CFF] bg-[#7C5CFF]/10'
                    : 'dw-glass-subtle'
                }`}
              >
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div>
                    <p className="text-sm font-semibold text-[#1E1940] dark:text-[#F5F3FF]">
                      {account.label}
                    </p>
                    <p className="text-[11px] text-[#5B5482] dark:text-[#A5A1C8]">
                      {account.description}
                    </p>
                  </div>
                  <span
                    className={`rounded-lg px-2 py-0.5 text-[11px] font-semibold ${
                      connected
                        ? 'bg-[#7C5CFF]/20 text-[#5E43F3] dark:text-[#C4B5FD]'
                        : 'bg-[#7C5CFF]/10 text-[#5B5482] dark:text-[#A5A1C8]'
                    }`}
                  >
                    {connected ? 'Conectado' : 'Não conectado'}
                  </span>
                </div>

                {connected && (
                  <p className="mt-2 text-[11px] text-[#5B5482] dark:text-[#A5A1C8]">
                    Método:{' '}
                    <span className="font-medium text-[#1E1940] dark:text-[#F5F3FF]">
                      {authMethodLabel}
                    </span>
                    {session?.username ? ` • /u/${session.username}` : ''}
                    {session?.updatedAt ? ` • Atualizado: ${formatDateTime(session.updatedAt)}` : ''}
                  </p>
                )}

                <div className="mt-3 flex flex-wrap gap-2">
                  <button
                    type="button"
                    disabled={!isNativeAndroid}
                    onClick={() => void handleConnectPlatform(account.id)}
                    className="dw-btn-primary inline-flex min-h-[42px] flex-1 items-center justify-center gap-1.5 rounded-xl px-3.5 text-xs font-semibold transition disabled:cursor-not-allowed disabled:opacity-45"
                  >
                    <LogIn className="h-3.5 w-3.5" aria-hidden="true" />
                    <span>Conectar {account.label}</span>
                  </button>

                  {connected && (
                    <button
                      type="button"
                      onClick={() => void handleRevokePlatformSession(account.id, account.label)}
                      className="inline-flex min-h-[42px] items-center justify-center gap-1.5 rounded-xl border border-rose-500/30 bg-rose-500/10 px-3 text-xs font-semibold text-rose-600 transition hover:bg-rose-500/20 dark:text-rose-300"
                    >
                      <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
                      <span>Encerrar e excluir sessão</span>
                    </button>
                  )}
                </div>

                {isNativeAndroid && (
                  <div className="mt-2">
                    <button
                      type="button"
                      onClick={() =>
                        setManualOpenFor((cur) => (cur === account.id ? null : account.id))
                      }
                      className="text-[11px] font-medium text-[#5B5482] underline-offset-2 hover:underline dark:text-[#A5A1C8]"
                    >
                      {manualOpenFor === account.id
                        ? 'Ocultar entrada manual'
                        : 'Inserir cookie manualmente (opcional)'}
                    </button>

                    {manualOpenFor === account.id && (
                      <div className="mt-2 flex gap-2">
                        <input
                          type="password"
                          aria-label={`Cookie de sessão do ${account.label}`}
                          placeholder={account.cookiePlaceholder}
                          value={manualCookies[account.id]}
                          onChange={(e) =>
                            setManualCookies((prev) => ({
                              ...prev,
                              [account.id]: e.target.value,
                            }))
                          }
                          className="min-h-[40px] flex-1 rounded-xl border border-[#7C5CFF]/25 bg-white px-3 text-xs text-[#1E1940] outline-none focus:border-[#7C5CFF] dark:bg-[#0D0B1E] dark:text-[#F5F3FF]"
                        />
                        <button
                          type="button"
                          onClick={() => void handleSaveManualCookie(account.id)}
                          className="min-h-[40px] rounded-xl border border-[#7C5CFF]/25 bg-[#7C5CFF]/15 px-3 text-xs font-semibold text-[#5E43F3] transition hover:bg-[#7C5CFF]/25 dark:text-[#C4B5FD]"
                        >
                          Salvar
                        </button>
                      </div>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </section>

      {/* Limpeza de cache temporário */}
      <section className="dw-glass-card flex items-center justify-between gap-4 rounded-3xl p-4">
        <div className="flex items-center gap-2.5">
          <HardDrive className="h-4 w-4 text-[#7C5CFF] dark:text-[#A78BFA]" aria-hidden="true" />
          <div>
            <p className="text-sm font-semibold text-[#1E1940] dark:text-[#F5F3FF]">
              Cache temporário
            </p>
            <p className="text-xs text-[#5B5482] dark:text-[#A5A1C8]">
              Remove arquivos parciais e temporários de conversão
            </p>
          </div>
        </div>
        <button
          type="button"
          disabled={cacheClearing}
          onClick={() => void handleClearTempCache()}
          className="min-h-[44px] shrink-0 rounded-xl border border-[#7C5CFF]/25 bg-[#7C5CFF]/10 px-3.5 text-xs font-semibold text-[#5E43F3] transition hover:bg-[#7C5CFF]/20 dark:text-[#C4B5FD]"
        >
          {cacheClearing ? 'Limpando…' : 'Limpar cache'}
        </button>
      </section>

      <AppUpdatesSection
        settings={settings}
        isNativeAndroid={isNativeAndroid}
        onUpdateSettings={onUpdateSettings}
      />

      {/* Área de Diagnóstico e Performance (colapsável) */}
      <section className="dw-glass-card rounded-3xl">
        <button
          type="button"
          aria-expanded={diagnosticsOpen}
          onClick={() => setDiagnosticsOpen((open) => !open)}
          className="flex min-h-[52px] w-full items-center justify-between gap-3 px-4 py-3 text-left"
        >
          <div className="flex items-center gap-2.5">
            <Activity className="h-4 w-4 text-[#7C5CFF] dark:text-[#A78BFA]" aria-hidden="true" />
            <div>
              <p className="text-sm font-bold text-[#1E1940] dark:text-[#F5F3FF]">
                Diagnóstico e Performance
              </p>
              <p className="text-xs text-[#5B5482] dark:text-[#A5A1C8]">
                Métricas do último download e log sanitizado
              </p>
            </div>
          </div>
          {diagnosticsOpen ? (
            <ChevronUp className="h-4 w-4 text-[#7D75A8]" aria-hidden="true" />
          ) : (
            <ChevronDown className="h-4 w-4 text-[#7D75A8]" aria-hidden="true" />
          )}
        </button>

        {diagnosticsOpen && (
          <div className="border-t border-[#7C5CFF]/15 px-4 pb-4 pt-3">
            <dl className="grid grid-cols-2 gap-x-3 gap-y-2 text-xs">
              <div>
                <dt className="text-[#5B5482] dark:text-[#A5A1C8]">Plataforma</dt>
                <dd className="font-mono font-medium text-[#1E1940] dark:text-[#F5F3FF]">
                  {diagReport?.platform || '—'}
                </dd>
              </div>
              <div>
                <dt className="text-[#5B5482] dark:text-[#A5A1C8]">Tipo de mídia</dt>
                <dd className="font-mono font-medium text-[#1E1940] dark:text-[#F5F3FF]">
                  {diagReport?.mediaTypeIdentified || '—'}
                </dd>
              </div>
              <div>
                <dt className="text-[#5B5482] dark:text-[#A5A1C8]">Motor utilizado</dt>
                <dd className="font-mono font-medium text-[#1E1940] dark:text-[#F5F3FF]">
                  {diagReport?.engineUsed || '—'}
                </dd>
              </div>
              <div>
                <dt className="text-[#5B5482] dark:text-[#A5A1C8]">Versão do motor</dt>
                <dd className="font-mono font-medium text-[#1E1940] dark:text-[#F5F3FF]">
                  {diagReport?.engineVersion || '—'}
                </dd>
              </div>
              <div>
                <dt className="text-[#5B5482] dark:text-[#A5A1C8]">Estratégia principal</dt>
                <dd className="font-mono font-medium text-[#1E1940] dark:text-[#F5F3FF]">
                  {diagReport?.strategyUsed || '—'}
                </dd>
              </div>
              <div>
                <dt className="text-[#5B5482] dark:text-[#A5A1C8]">Classificação final</dt>
                <dd className="font-mono font-medium text-[#1E1940] dark:text-[#F5F3FF]">
                  {diagReport?.errorCode || 'OK'}
                </dd>
              </div>
              <div>
                <dt className="text-[#5B5482] dark:text-[#A5A1C8]">Código HTTP</dt>
                <dd className="font-mono font-medium text-[#1E1940] dark:text-[#F5F3FF]">
                  {diagReport?.httpStatus !== null && diagReport?.httpStatus !== undefined
                    ? diagReport.httpStatus
                    : '—'}
                </dd>
              </div>
              <div>
                <dt className="text-[#5B5482] dark:text-[#A5A1C8]">Exit code</dt>
                <dd className="font-mono font-medium text-[#1E1940] dark:text-[#F5F3FF]">
                  {diagReport?.exitCode !== null && diagReport?.exitCode !== undefined
                    ? diagReport.exitCode
                    : '—'}
                </dd>
              </div>
              <div>
                <dt className="text-[#5B5482] dark:text-[#A5A1C8]">Resolução</dt>
                <dd className="font-mono text-[#1E1940] dark:text-[#F5F3FF]">
                  {formatMs(diagReport?.resolutionMs)}
                </dd>
              </div>
              <div>
                <dt className="text-[#5B5482] dark:text-[#A5A1C8]">Transferência</dt>
                <dd className="font-mono text-[#1E1940] dark:text-[#F5F3FF]">
                  {formatMs(diagReport?.transferMs)}
                </dd>
              </div>
              <div>
                <dt className="text-[#5B5482] dark:text-[#A5A1C8]">TTFB / Galeria</dt>
                <dd className="font-mono text-[#1E1940] dark:text-[#F5F3FF]">
                  {formatMs(diagReport?.ttfbMs)} / {formatMs(diagReport?.galleryPublishMs)}
                </dd>
              </div>
              <div>
                <dt className="text-[#5B5482] dark:text-[#A5A1C8]">Tempo total</dt>
                <dd className="font-mono text-[#1E1940] dark:text-[#F5F3FF]">
                  {formatMs(diagReport?.totalJobMs)}
                </dd>
              </div>
              <div>
                <dt className="text-[#5B5482] dark:text-[#A5A1C8]">Requisições HTTP</dt>
                <dd className="font-mono text-[#1E1940] dark:text-[#F5F3FF]">
                  {diagReport?.httpRequestCount !== null &&
                  diagReport?.httpRequestCount !== undefined
                    ? diagReport.httpRequestCount
                    : '—'}
                </dd>
              </div>
              <div>
                <dt className="text-[#5B5482] dark:text-[#A5A1C8]">Última atualização</dt>
                <dd className="font-medium text-[#1E1940] dark:text-[#F5F3FF]">
                  {formatDateTime(diagReport?.lastUpdateAt)}
                </dd>
              </div>
            </dl>

            {diagReport?.strategyAttempts && diagReport.strategyAttempts.length > 0 && (
              <div className="dw-glass-subtle mt-3 rounded-2xl p-2.5 text-xs">
                <p className="font-semibold text-[#1E1940] dark:text-[#F5F3FF]">
                  Estratégias utilizadas
                </p>
                <ul className="mt-1 space-y-1 font-mono text-[11px] text-[#5B5482] dark:text-[#A5A1C8]">
                  {diagReport.strategyAttempts.map((att, idx) => (
                    <li key={`${att.strategyName}-${idx}`}>
                      {att.strategyName}: {att.success ? 'OK' : `FALHA(${att.errorCode || '-'})`} •{' '}
                      {att.durationMs}ms
                      {att.note ? ` — ${sanitizeDiagnosticText(att.note)}` : ''}
                    </li>
                  ))}
                </ul>
              </div>
            )}

            {(diagReport?.errorCode ||
              diagReport?.rawErrorSanitized ||
              diagReport?.technicalReason) && (
              <div className="mt-3 rounded-2xl border border-rose-500/25 bg-rose-500/10 p-2.5 text-xs text-rose-600 dark:text-rose-300">
                <p className="font-mono font-semibold">{diagReport.errorCode || 'ERRO'}</p>
                {diagReport.rawErrorSanitized && (
                  <p className="mt-0.5 break-words font-mono text-[11px]">
                    {sanitizeDiagnosticText(diagReport.rawErrorSanitized)}
                  </p>
                )}
                {diagReport.technicalReason &&
                  diagReport.technicalReason !== diagReport.rawErrorSanitized && (
                    <p className="mt-0.5 break-words">
                      {sanitizeDiagnosticText(diagReport.technicalReason)}
                    </p>
                  )}
              </div>
            )}

            <button
              type="button"
              onClick={() => void handleCopySanitizedLog()}
              className="mt-3 inline-flex min-h-[44px] w-full items-center justify-center gap-1.5 rounded-2xl border border-[#7C5CFF]/25 bg-[#7C5CFF]/10 px-4 text-xs font-semibold text-[#5E43F3] transition hover:bg-[#7C5CFF]/20 dark:text-[#C4B5FD]"
            >
              {diagCopied ? (
                <>
                  <Check className="h-4 w-4" aria-hidden="true" />
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
    </div>
  );
}
