import { useCallback, useEffect, useRef, useState } from 'react';
import { BottomNav, type NavTab } from './components/BottomNav';
import { EnginesView } from './components/EnginesView';
import { Header } from './components/Header';
import { HistoryView } from './components/HistoryView';
import { HomeView } from './components/HomeView';
import { LoginScreen } from './components/LoginScreen';
import { MultiUrlModal } from './components/MultiUrlModal';
import { SettingsView } from './components/SettingsView';
import { useClipboardAutoDetect } from './hooks/useClipboardAutoDetect';
import { useJobProgress } from './hooks/useJobProgress';
import { api, setUnauthorizedHandler, setVpsBaseUrl } from './services/api';
import {
  decorateVpsJob,
  isTerminalPlatformError,
  selectExecutionPlan,
} from './services/hybridOrchestrator';
import {
  createInitialLocalJob,
  getLocalJobs,
  syncLocalSnapshots,
  upsertLocalJob,
} from './services/localQueue';
import { nativeBridge, type ShareIntentPayload } from './services/nativeBridge';
import {
  DEFAULT_USER_SETTINGS,
  loadLocalSettings,
  normalizeVpsUrl,
  saveLocalSettings,
} from './services/settingsStorage';
import {
  clearPendingShareFromSession,
  collectPendingShare,
} from './services/shareTarget';
import type {
  AuthUser,
  EngineOrigin,
  FormatOption,
  JobResponse,
  PendingSharePayload,
  QualityOption,
  UserSettings,
} from './types/api';
import {
  buildFingerprint,
  clearActiveJobFingerprint,
  isDuplicateAutoDownload,
  markActiveJobFingerprint,
  markRecentAutoDownload,
} from './utils/dedupe';
import {
  extractSupportedUrlsFromText,
  parseAndValidateUrl,
  type ParsedPlatformUrl,
} from './utils/url';

const ONBOARDING_KEY = 'devwolf_onboarding_done_v2';
const LAST_JOB_KEY = 'devwolf_last_job_id_v2';

function generateIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

interface JobRunContext {
  normalizedUrl: string;
  platform: string;
  format: FormatOption;
  quality: QualityOption;
  statusMode: boolean;
  segmentDuration: number | null;
  keepOriginal: boolean;
  fingerprint: string;
  forceManual: boolean;
  fromShareIntent: boolean;
  remainingOrigins: EngineOrigin[];
}

export function App() {
  const isNativeAndroid = nativeBridge.isNativePlatform();

  const [authLoading, setAuthLoading] = useState(true);
  const [user, setUser] = useState<AuthUser | null>(null);
  const [showVpsLogin, setShowVpsLogin] = useState(false);
  const [pendingShare, setPendingShare] = useState<PendingSharePayload | null>(null);
  const [showReturnToSourceApp, setShowReturnToSourceApp] = useState(false);

  const [activeTab, setActiveTab] = useState<NavTab>('home');
  const [settings, setSettings] = useState<UserSettings>(() => {
    const initial = loadLocalSettings();
    setVpsBaseUrl(initial.vps_url);
    return initial;
  });
  const [onboardingDone, setOnboardingDone] = useState<boolean>(() => {
    try {
      return window.localStorage.getItem(ONBOARDING_KEY) === '1';
    } catch {
      return false;
    }
  });

  const [localJobs, setLocalJobs] = useState<JobResponse[]>(() => getLocalJobs());
  const [urlInput, setUrlInput] = useState('');
  const [isUserEditing, setIsUserEditing] = useState(false);
  const [selectedFormat, setSelectedFormat] = useState<FormatOption>('auto');
  const [selectedQuality, setSelectedQuality] = useState<QualityOption>(
    () => loadLocalSettings().default_quality
  );
  const [submitting, setSubmitting] = useState(false);
  const [statusNotice, setStatusNotice] = useState<string | null>(null);
  const [multiCandidates, setMultiCandidates] = useState<ParsedPlatformUrl[]>([]);

  const autoStartLockRef = useRef(false);
  const runContextMapRef = useRef<Map<string, JobRunContext>>(new Map());
  const setActiveJobRef = useRef<(job: JobResponse | null) => void>(() => undefined);

  const vpsAuthenticated = Boolean(user);
  const vpsConfigured = isNativeAndroid ? Boolean(normalizeVpsUrl(settings.vps_url)) : true;

  const launchOnOrigin = useCallback(
    async (
      origin: EngineOrigin,
      ctx: JobRunContext,
      fallbackNotice?: string | null
    ): Promise<JobResponse> => {
      if (origin === 'local') {
        const jobId = `local-${generateIdempotencyKey()}`;
        const initialLocal = createInitialLocalJob({
          jobId,
          url: ctx.normalizedUrl,
          normalizedUrl: ctx.normalizedUrl,
          platform: ctx.platform,
          format: ctx.format,
          quality: ctx.quality,
          statusMode: ctx.statusMode,
          segmentDuration: ctx.segmentDuration,
          keepOriginal: ctx.keepOriginal,
          fromShareIntent: ctx.fromShareIntent,
        });
        initialLocal.fallback_notice = fallbackNotice ?? null;

        runContextMapRef.current.set(jobId, ctx);
        upsertLocalJob(initialLocal);
        setLocalJobs(getLocalJobs());
        setActiveJobRef.current(initialLocal);
        markActiveJobFingerprint(ctx.fingerprint, jobId);

        await nativeBridge.startLocalDownload({
          jobId,
          url: ctx.normalizedUrl,
          format: ctx.format,
          quality: ctx.quality,
          statusMode: ctx.statusMode,
          segmentDurationSeconds: ctx.segmentDuration,
          keepOriginal: ctx.keepOriginal,
        });

        return initialLocal;
      }

      const created = await api.createJob({
        url: ctx.normalizedUrl,
        format: ctx.format,
        quality: ctx.quality,
        idempotency_key: generateIdempotencyKey(),
        force_manual: ctx.forceManual,
        status_mode: ctx.statusMode,
        segment_duration: ctx.segmentDuration,
        keep_original: ctx.keepOriginal,
      });

      const decorated = decorateVpsJob(created, {
        statusMode: ctx.statusMode,
        segmentDuration: ctx.segmentDuration,
        keepOriginal: ctx.keepOriginal,
        fromShareIntent: ctx.fromShareIntent,
        fallbackNotice: fallbackNotice ?? null,
      });

      runContextMapRef.current.set(decorated.id, ctx);
      setActiveJobRef.current(decorated);
      try {
        window.localStorage.setItem(LAST_JOB_KEY, decorated.id);
      } catch {
        // Ignorar falha de localStorage
      }
      markActiveJobFingerprint(ctx.fingerprint, decorated.id);
      return decorated;
    },
    []
  );

  const handleRecoverableFailure = useCallback(
    async (failedJob: JobResponse): Promise<boolean> => {
      const ctx = runContextMapRef.current.get(failedJob.id);
      if (!ctx || ctx.remainingOrigins.length === 0) {
        return false;
      }

      if (isTerminalPlatformError(failedJob.error_code, failedJob.error_message)) {
        return false;
      }

      const [nextOrigin, ...restOrigins] = ctx.remainingOrigins;
      const nextCtx: JobRunContext = {
        ...ctx,
        remainingOrigins: restOrigins,
      };

      try {
        const notice =
          failedJob.execution_origin === 'local'
            ? 'Motor local falhou — alternando automaticamente para VPS…'
            : 'Motor da VPS falhou — alternando para Motor Local…';
        await launchOnOrigin(nextOrigin, nextCtx, notice);
        return true;
      } catch {
        return false;
      }
    },
    [launchOnOrigin]
  );

  const { job: activeJob, setActiveJob, engineSwitchNotice, connectionMode } = useJobProgress(null, {
    onRecoverableFailure: handleRecoverableFailure,
  });
  setActiveJobRef.current = setActiveJob;

  // Manter lista local sincronizada sempre que o job ativo local atualizar
  useEffect(() => {
    if (activeJob?.execution_origin === 'local') {
      setLocalJobs(getLocalJobs());
    }
  }, [activeJob]);

  // Aplicar tema claro/escuro no <html>
  useEffect(() => {
    const root = document.documentElement;
    if (settings.theme === 'light') {
      root.classList.remove('dark');
    } else {
      root.classList.add('dark');
    }
  }, [settings.theme]);

  const startJob = useCallback(
    async (
      rawUrl: string,
      options: {
        forceManual: boolean;
        format?: FormatOption;
        quality?: QualityOption;
        fromShareIntent?: boolean;
      }
    ) => {
      const parsed = parseAndValidateUrl(rawUrl);
      if (!parsed) {
        throw new Error('URL inválida ou plataforma não suportada.');
      }

      const fmt = options.format ?? selectedFormat;
      const qual = options.quality ?? selectedQuality;
      const statusMode = settings.status_mode_enabled;
      const segmentDuration =
        statusMode && settings.status_max_duration_seconds > 0
          ? settings.status_max_duration_seconds
          : null;
      const keepOriginal = settings.status_keep_original;

      const fingerprint = buildFingerprint(
        parsed.normalizedUrl,
        fmt,
        qual,
        statusMode,
        segmentDuration
      );

      if (!options.forceManual) {
        const duplicate = isDuplicateAutoDownload(fingerprint);
        if (duplicate) {
          setStatusNotice('Link já processado recentemente. Use Baixar para repetir manualmente.');
          return;
        }
      }

      const plan = selectExecutionPlan({
        preferredMode: settings.preferred_execution_mode,
        isNativeAndroid,
        vpsAuthenticated,
        vpsConfigured,
        parsed,
        format: fmt,
      });

      if (plan.order.length === 0) {
        if (plan.requiresVpsLogin && isNativeAndroid) {
          setStatusNotice(plan.reason);
        }
        throw new Error(plan.reason);
      }

      setSubmitting(true);
      setStatusNotice(null);

      const [firstOrigin, ...remainingOrigins] = plan.order;
      const ctx: JobRunContext = {
        normalizedUrl: parsed.normalizedUrl,
        platform: parsed.platform,
        format: fmt,
        quality: qual,
        statusMode,
        segmentDuration,
        keepOriginal,
        fingerprint,
        forceManual: options.forceManual,
        fromShareIntent: Boolean(options.fromShareIntent),
        remainingOrigins,
      };

      try {
        let launched: JobResponse;
        try {
          launched = await launchOnOrigin(firstOrigin, ctx);
        } catch (firstErr) {
          const msg = firstErr instanceof Error ? firstErr.message : '';
          if (remainingOrigins.length > 0 && !isTerminalPlatformError(null, msg)) {
            const [fallbackOrigin, ...rest] = remainingOrigins;
            launched = await launchOnOrigin(
              fallbackOrigin,
              { ...ctx, remainingOrigins: rest },
              firstOrigin === 'local'
                ? 'Motor local indisponível — usando VPS…'
                : 'VPS indisponível — usando Motor Local…'
            );
          } else {
            throw firstErr;
          }
        }

        if (!options.forceManual) {
          markRecentAutoDownload(fingerprint, launched.id);
        }
      } catch (err) {
        clearActiveJobFingerprint(fingerprint);
        throw err;
      } finally {
        setSubmitting(false);
      }
    },
    [
      isNativeAndroid,
      launchOnOrigin,
      selectedFormat,
      selectedQuality,
      settings.preferred_execution_mode,
      settings.status_keep_original,
      settings.status_max_duration_seconds,
      settings.status_mode_enabled,
      vpsAuthenticated,
      vpsConfigured,
    ]
  );

  // Carregar sessão VPS, fila local e share pendente ao montar
  useEffect(() => {
    setUnauthorizedHandler(() => {
      setUser(null);
    });

    let mounted = true;
    const bootstrap = async () => {
      if (isNativeAndroid) {
        try {
          const snapshots = await nativeBridge.getLocalDownloadQueue();
          if (mounted && snapshots.length > 0) {
            const merged = syncLocalSnapshots(snapshots);
            setLocalJobs(merged);
            const inProgress = merged.find((j) =>
              ['queued', 'resolving', 'downloading', 'processing'].includes(j.status)
            );
            if (inProgress) {
              setActiveJob(inProgress);
            }
          }
        } catch {
          // Ignorar erro na recuperação da fila nativa
        }
      }

      const currentVps = normalizeVpsUrl(loadLocalSettings().vps_url);
      const shouldCheckVps = !isNativeAndroid || Boolean(currentVps);

      if (shouldCheckVps) {
        try {
          const me = await api.getMe();
          if (!mounted) return;
          if (me.authenticated && me.user) {
            setUser(me.user);
            const share = await collectPendingShare(true);
            if (mounted && share) {
              setPendingShare(share);
            }
          } else {
            setUser(null);
            const share = await collectPendingShare(false);
            if (mounted && share) {
              setPendingShare(share);
            }
          }
        } catch {
          if (mounted) setUser(null);
        }
      }

      if (isNativeAndroid) {
        const nativeIntent = await nativeBridge.getPendingShareIntent();
        if (mounted && nativeIntent) {
          await nativeBridge.clearPendingShareIntent(nativeIntent.id);
          setPendingShare({
            id: nativeIntent.id,
            url: nativeIntent.url,
            text: nativeIntent.rawText ?? '',
            title: nativeIntent.title ?? '',
            autoStart: nativeIntent.autoStart ?? true,
            fromNativeIntent: true,
          });
        }
      }

      if (mounted) setAuthLoading(false);
    };

    void bootstrap();

    return () => {
      mounted = false;
      setUnauthorizedHandler(null);
    };
  }, [isNativeAndroid, setActiveJob]);

  // Ouvir novos Share Intents enquanto o app Android estiver aberto
  useEffect(() => {
    if (!isNativeAndroid) return;
    let cancelled = false;
    let unsubscribe: (() => void) | null = null;

    const handleNativeShare = (intent: ShareIntentPayload) => {
      if (cancelled) return;
      void nativeBridge.clearPendingShareIntent(intent.id);
      setPendingShare({
        id: intent.id,
        url: intent.url,
        text: intent.rawText ?? '',
        title: intent.title ?? '',
        autoStart: intent.autoStart ?? true,
        fromNativeIntent: true,
      });
    };

    void nativeBridge.onShareIntentReceived(handleNativeShare).then((unsub) => {
      if (cancelled) unsub();
      else unsubscribe = unsub;
    });

    return () => {
      cancelled = true;
      unsubscribe?.();
    };
  }, [isNativeAndroid]);

  // Carregar ajustes da VPS e recuperar job remoto ativo ao autenticar
  useEffect(() => {
    if (!user) return;
    let mounted = true;

    const loadUserData = async () => {
      try {
        const loadedSettings = await api.getSettings();
        if (!mounted) return;
        setSettings((prev) => {
          const merged: UserSettings = {
            ...DEFAULT_USER_SETTINGS,
            ...prev,
            ...loadedSettings,
            // Preserva configuração local "Desativado (0)" se escolhida localmente
            status_max_duration_seconds:
              prev.status_max_duration_seconds === 0
                ? 0
                : (loadedSettings.status_max_duration_seconds as UserSettings['status_max_duration_seconds']) ??
                  prev.status_max_duration_seconds,
            vps_url: prev.vps_url,
          };
          saveLocalSettings(merged);
          return merged;
        });
        setSelectedQuality(loadedSettings.default_quality);
      } catch {
        // Mantém ajustes locais caso VPS esteja inacessível
      }

      try {
        const lastJobId = window.localStorage.getItem(LAST_JOB_KEY);
        if (lastJobId) {
          const recovered = await api.getJob(lastJobId);
          if (mounted && recovered) {
            setActiveJob(decorateVpsJob(recovered));
          }
        }
      } catch {
        // Job antigo pode ter sido removido
      }
    };

    void loadUserData();
    return () => {
      mounted = false;
    };
  }, [user, setActiveJob]);

  // Processar link compartilhado (Web Share Target ou Android Share Intent)
  useEffect(() => {
    if (!pendingShare) return;
    if (!isNativeAndroid && !user) return;

    const shareError = pendingShare.error;
    const fromNative = Boolean(pendingShare.fromNativeIntent);
    const shouldAutoStart = fromNative ? pendingShare.autoStart !== false : settings.auto_download;
    const combined = [pendingShare.url, pendingShare.text, pendingShare.title]
      .filter(Boolean)
      .join(' ');
    const candidates = extractSupportedUrlsFromText(combined);

    clearPendingShareFromSession();
    setPendingShare(null);

    if (fromNative) {
      setShowReturnToSourceApp(true);
    }

    if (candidates.length === 1) {
      const chosen = candidates[0];
      setUrlInput(chosen.normalizedUrl);
      setActiveTab('home');
      if (shouldAutoStart && !autoStartLockRef.current) {
        autoStartLockRef.current = true;
        void startJob(chosen.normalizedUrl, {
          forceManual: fromNative,
          fromShareIntent: fromNative,
        })
          .catch((err) => {
            setStatusNotice(err instanceof Error ? err.message : 'Falha ao iniciar download.');
          })
          .finally(() => {
            autoStartLockRef.current = false;
          });
      }
    } else if (candidates.length > 1) {
      setActiveTab('home');
      setMultiCandidates(candidates);
    } else if (shareError) {
      setActiveTab('home');
      setStatusNotice(shareError);
    } else if (combined.trim()) {
      setActiveTab('home');
      setStatusNotice('Nenhum link suportado encontrado no item compartilhado.');
    }
  }, [isNativeAndroid, pendingShare, settings.auto_download, startJob, user]);

  const handleDetectedSingleUrl = useCallback(
    (parsed: ParsedPlatformUrl, fromExplicitPaste = false) => {
      setUrlInput(parsed.normalizedUrl);
      setStatusNotice(null);

      if (settings.auto_download && !autoStartLockRef.current) {
        autoStartLockRef.current = true;
        void startJob(parsed.normalizedUrl, { forceManual: fromExplicitPaste })
          .catch((err) => {
            setStatusNotice(err instanceof Error ? err.message : 'Falha ao iniciar download.');
          })
          .finally(() => {
            autoStartLockRef.current = false;
          });
      }
    },
    [settings.auto_download, startJob]
  );

  const canUseApp = isNativeAndroid || Boolean(user);

  const { guidanceMessage, readClipboardExplicitly, setGuidanceMessage } = useClipboardAutoDetect({
    enabled: canUseApp && onboardingDone && settings.auto_download && activeTab === 'home',
    isUserEditing,
    onSingleUrlDetected: (parsed, explicitUserGesture) =>
      handleDetectedSingleUrl(parsed, explicitUserGesture),
    onMultipleUrlsDetected: (candidates) => setMultiCandidates(candidates),
  });

  const handleLogin = async (username: string, password: string, vpsUrl?: string) => {
    if (vpsUrl !== undefined) {
      const normalized = normalizeVpsUrl(vpsUrl);
      setVpsBaseUrl(normalized);
      setSettings((prev) => {
        const next = { ...prev, vps_url: normalized };
        saveLocalSettings(next);
        return next;
      });
    }
    const res = await api.login(username, password);
    if (res.authenticated && res.user) {
      setUser(res.user);
      setShowVpsLogin(false);
      const serverShare = await collectPendingShare(true);
      if (serverShare) {
        setPendingShare(serverShare);
      }
    }
  };

  const handleLogout = async () => {
    await api.logout();
    setUser(null);
    if (!isNativeAndroid) {
      setActiveJob(null);
    }
  };

  const handleUpdateSettings = async (patch: Partial<UserSettings>) => {
    if (patch.vps_url !== undefined) {
      setVpsBaseUrl(patch.vps_url);
    }

    const optimistic: UserSettings = { ...settings, ...patch };
    setSettings(optimistic);
    saveLocalSettings(optimistic);

    if (patch.default_quality) {
      setSelectedQuality(optimistic.default_quality);
    }

    if (vpsAuthenticated && Object.keys(patch).some((k) => k !== 'vps_url')) {
      try {
        const synced = await api.updateSettings(patch);
        const merged: UserSettings = {
          ...optimistic,
          ...synced,
          status_max_duration_seconds:
            optimistic.status_max_duration_seconds === 0
              ? 0
              : (synced.status_max_duration_seconds as UserSettings['status_max_duration_seconds']) ??
                optimistic.status_max_duration_seconds,
          vps_url: optimistic.vps_url,
        };
        setSettings(merged);
        saveLocalSettings(merged);
      } catch {
        // Mantém configuração salva localmente quando offline da VPS
      }
    }
  };

  const handleOnboardingChoice = async (enableAuto: boolean) => {
    try {
      window.localStorage.setItem(ONBOARDING_KEY, '1');
    } catch {
      // Ignorar erro de localStorage
    }
    setOnboardingDone(true);
    await handleUpdateSettings({ auto_download: enableAuto });
  };

  const handleToggleAutoDownload = async () => {
    await handleUpdateSettings({ auto_download: !settings.auto_download });
  };

  const handleToggleStatusMode = async () => {
    await handleUpdateSettings({ status_mode_enabled: !settings.status_mode_enabled });
  };

  const handleExplicitPaste = async () => {
    setGuidanceMessage(null);
    await readClipboardExplicitly();
  };

  const handleManualDownload = async (rawUrl: string) => {
    await startJob(rawUrl, { forceManual: true });
  };

  const handleCancelJob = async (jobId: string) => {
    try {
      if (activeJob?.execution_origin === 'local' || jobId.startsWith('local-')) {
        await nativeBridge.cancelLocalDownload(jobId);
        if (activeJob && activeJob.id === jobId) {
          const cancelled: JobResponse = {
            ...activeJob,
            status: 'cancelled',
            phase_label: 'Cancelado',
            updated_at: new Date().toISOString(),
          };
          upsertLocalJob(cancelled);
          setLocalJobs(getLocalJobs());
          setActiveJob(cancelled);
        }
        return;
      }
      const updated = await api.cancelJob(jobId);
      setActiveJob(decorateVpsJob(updated));
    } catch (err) {
      setStatusNotice(err instanceof Error ? err.message : 'Erro ao cancelar.');
    }
  };

  const handleRepeatFromHistory = async (url: string) => {
    setUrlInput(url);
    setActiveTab('home');
    try {
      await startJob(url, { forceManual: true });
    } catch (err) {
      setStatusNotice(err instanceof Error ? err.message : 'Erro ao repetir download.');
    }
  };

  const handleEditFromHistory = (url: string) => {
    setUrlInput(url);
    setStatusNotice('Link carregado para edição.');
    setActiveTab('home');
  };

  const handleReturnToSourceApp = async () => {
    await nativeBridge.returnToSourceApp();
  };

  if (authLoading) {
    return (
      <main className="flex min-h-screen items-center justify-center bg-[#F4F4F5] dark:bg-[#101012]">
        <div className="text-sm text-zinc-500 dark:text-zinc-400">
          Carregando Devwolf Downloader…
        </div>
      </main>
    );
  }

  if ((!user && !isNativeAndroid) || showVpsLogin) {
    return (
      <LoginScreen
        pendingShare={pendingShare}
        isNativeAndroid={isNativeAndroid}
        initialVpsUrl={settings.vps_url}
        onLogin={handleLogin}
        onContinueOffline={isNativeAndroid ? () => setShowVpsLogin(false) : undefined}
      />
    );
  }

  return (
    <div className="min-h-screen bg-[#F4F4F5] pb-24 text-zinc-900 dark:bg-[#101012] dark:text-zinc-100">
      <Header
        username={user?.username ?? null}
        isNativeAndroid={isNativeAndroid}
        vpsAuthenticated={vpsAuthenticated}
        theme={settings.theme}
        onToggleTheme={() =>
          void handleUpdateSettings({
            theme: settings.theme === 'dark' ? 'light' : 'dark',
          })
        }
        onRequestVpsLogin={() => setShowVpsLogin(true)}
        onLogout={() => void handleLogout()}
      />

      <main className="mx-auto max-w-2xl px-4 pt-4">
        {activeTab === 'home' && (
          <HomeView
            urlInput={urlInput}
            onUrlInputChange={setUrlInput}
            onUserEditingChange={setIsUserEditing}
            selectedFormat={selectedFormat}
            selectedQuality={selectedQuality}
            onFormatChange={setSelectedFormat}
            onQualityChange={setSelectedQuality}
            settings={settings}
            isNativeAndroid={isNativeAndroid}
            onboardingDone={onboardingDone}
            onOnboardingChoice={(enable) => void handleOnboardingChoice(enable)}
            onToggleAutoDownload={() => void handleToggleAutoDownload()}
            onToggleStatusMode={() => void handleToggleStatusMode()}
            onExplicitPaste={handleExplicitPaste}
            onSubmitManualDownload={handleManualDownload}
            onCancelJob={handleCancelJob}
            onRetryJob={(url) => startJob(url, { forceManual: true })}
            onReturnToSourceApp={handleReturnToSourceApp}
            showReturnToSourceApp={showReturnToSourceApp}
            clipboardGuidance={guidanceMessage}
            statusNotice={statusNotice}
            submitting={submitting}
            activeJob={activeJob}
            engineSwitchNotice={engineSwitchNotice}
            connectionMode={connectionMode}
          />
        )}

        {activeTab === 'history' && (
          <HistoryView
            localJobs={localJobs}
            vpsAuthenticated={vpsAuthenticated}
            onRepeatDownload={handleRepeatFromHistory}
            onEditUrl={handleEditFromHistory}
            onLocalJobDeleted={() => setLocalJobs(getLocalJobs())}
          />
        )}

        {activeTab === 'engines' && (
          <EnginesView
            isNativeAndroid={isNativeAndroid}
            vpsAuthenticated={vpsAuthenticated}
            onRequestVpsLogin={() => setShowVpsLogin(true)}
          />
        )}

        {activeTab === 'settings' && (
          <SettingsView
            settings={settings}
            isNativeAndroid={isNativeAndroid}
            vpsAuthenticated={vpsAuthenticated}
            onUpdateSettings={handleUpdateSettings}
            onRequestVpsLogin={() => setShowVpsLogin(true)}
          />
        )}
      </main>

      <MultiUrlModal
        candidates={multiCandidates}
        onSelect={(picked) => {
          setMultiCandidates([]);
          handleDetectedSingleUrl(picked, true);
        }}
        onClose={() => setMultiCandidates([])}
      />

      <BottomNav activeTab={activeTab} onSelectTab={setActiveTab} />
    </div>
  );
}

export default App;
