import { useCallback, useEffect, useRef, useState } from 'react';
import { BottomNav, type NavTab } from './components/BottomNav';
import { EnginesView } from './components/EnginesView';
import { Header } from './components/Header';
import { HistoryView } from './components/HistoryView';
import { HomeView } from './components/HomeView';
import { MultiUrlModal } from './components/MultiUrlModal';
import { SettingsView } from './components/SettingsView';
import { useClipboardAutoDetect } from './hooks/useClipboardAutoDetect';
import { useJobProgress } from './hooks/useJobProgress';
import { selectExecutionPlan } from './services/hybridOrchestrator';
import {
  createInitialLocalJob,
  getLocalJobs,
  syncLocalSnapshots,
  upsertLocalJob,
} from './services/localQueue';
import { nativeBridge, type ShareIntentPayload } from './services/nativeBridge';
import { loadLocalSettings, saveLocalSettings } from './services/settingsStorage';
import {
  clearPendingShareFromSession,
  collectPendingShare,
} from './services/shareTarget';
import type {
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

function generateIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

export function App() {
  const isNativeAndroid = nativeBridge.isNativePlatform();

  const [booting, setBooting] = useState(true);
  const [pendingShare, setPendingShare] = useState<PendingSharePayload | null>(null);
  const [showReturnToSourceApp, setShowReturnToSourceApp] = useState(false);

  const [activeTab, setActiveTab] = useState<NavTab>('home');
  const [settings, setSettings] = useState<UserSettings>(() => loadLocalSettings());
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
  const [focusAccountPlatform, setFocusAccountPlatform] = useState<string | null>(null);

  const autoStartLockRef = useRef(false);
  const { job: activeJob, setActiveJob, engineSwitchNotice, connectionMode } =
    useJobProgress(null);

  useEffect(() => {
    if (activeJob) {
      setLocalJobs(getLocalJobs());
    }
  }, [activeJob]);

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
        isNativeAndroid,
        parsed,
        format: fmt,
      });

      if (plan.order.length === 0) {
        throw new Error(plan.reason);
      }

      setSubmitting(true);
      setStatusNotice(null);

      const jobId = `local-${generateIdempotencyKey()}`;
      const initialLocal = createInitialLocalJob({
        jobId,
        url: parsed.normalizedUrl,
        normalizedUrl: parsed.normalizedUrl,
        platform: parsed.platform,
        format: fmt,
        quality: qual,
        statusMode,
        segmentDuration,
        keepOriginal,
        fromShareIntent: Boolean(options.fromShareIntent),
        primaryEngine: plan.primaryEngine,
      });

      upsertLocalJob(initialLocal);
      setLocalJobs(getLocalJobs());
      setActiveJob(initialLocal);
      markActiveJobFingerprint(fingerprint, jobId);

      try {
        await nativeBridge.startLocalDownload({
          jobId,
          url: parsed.normalizedUrl,
          format: fmt,
          quality: qual,
          statusMode,
          segmentDurationSeconds: segmentDuration,
          keepOriginal,
        });

        if (!options.forceManual) {
          markRecentAutoDownload(fingerprint, jobId);
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
      selectedFormat,
      selectedQuality,
      setActiveJob,
      settings.status_keep_original,
      settings.status_max_duration_seconds,
      settings.status_mode_enabled,
    ]
  );

  useEffect(() => {
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
          // Ignorar falha ao recuperar fila nativa
        }

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
      } else {
        const share = await collectPendingShare();
        if (mounted && share) {
          setPendingShare(share);
        }
      }

      if (mounted) setBooting(false);
    };

    void bootstrap();
    return () => {
      mounted = false;
    };
  }, [isNativeAndroid, setActiveJob]);

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

  useEffect(() => {
    if (!pendingShare) return;

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
  }, [pendingShare, settings.auto_download, startJob]);

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

  const { guidanceMessage, readClipboardExplicitly, setGuidanceMessage } = useClipboardAutoDetect({
    enabled: !booting && onboardingDone && settings.auto_download && activeTab === 'home',
    isUserEditing,
    onSingleUrlDetected: (parsed, explicitUserGesture) =>
      handleDetectedSingleUrl(parsed, explicitUserGesture),
    onMultipleUrlsDetected: (candidates) => setMultiCandidates(candidates),
  });

  const handleUpdateSettings = async (patch: Partial<UserSettings>) => {
    const next: UserSettings = { ...settings, ...patch };
    setSettings(next);
    saveLocalSettings(next);

    if (patch.default_quality) {
      setSelectedQuality(next.default_quality);
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

  if (booting) {
    return (
      <main className="flex min-h-screen items-center justify-center bg-[#F4F4F5] dark:bg-[#101012]">
        <div className="text-sm text-zinc-500 dark:text-zinc-400">
          Carregando Devwolf Downloader…
        </div>
      </main>
    );
  }

  return (
    <div className="min-h-screen bg-[#F4F4F5] pb-24 text-zinc-900 dark:bg-[#101012] dark:text-zinc-100">
      <Header
        isNativeAndroid={isNativeAndroid}
        theme={settings.theme}
        onToggleTheme={() =>
          void handleUpdateSettings({
            theme: settings.theme === 'dark' ? 'light' : 'dark',
          })
        }
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
            onOpenSettings={() => setActiveTab('settings')}
            onOpenAccountSettings={(platform) => {
              setFocusAccountPlatform(platform);
              setActiveTab('settings');
            }}
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
            onRepeatDownload={handleRepeatFromHistory}
            onEditUrl={handleEditFromHistory}
            onLocalJobDeleted={() => setLocalJobs(getLocalJobs())}
          />
        )}

        {activeTab === 'engines' && (
          <EnginesView
            isNativeAndroid={isNativeAndroid}
            updateChannel={settings.update_channel}
            onChangeUpdateChannel={(channel) =>
              handleUpdateSettings({ update_channel: channel })
            }
          />
        )}

        {activeTab === 'settings' && (
          <SettingsView
            settings={settings}
            isNativeAndroid={isNativeAndroid}
            latestJob={activeJob ?? localJobs[0] ?? null}
            focusAccountPlatform={focusAccountPlatform}
            onClearFocusAccount={() => setFocusAccountPlatform(null)}
            onUpdateSettings={handleUpdateSettings}
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
