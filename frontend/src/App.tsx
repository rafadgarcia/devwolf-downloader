import { useCallback, useEffect, useRef, useState } from 'react';
import { AddLinkModal } from './components/AddLinkModal';
import { EnginesView } from './components/EnginesView';
import { Header, type NavTab } from './components/Header';
import { HistoryView } from './components/HistoryView';
import { HomeView } from './components/HomeView';
import { HowToDownloadModal } from './components/HowToDownloadModal';
import { MultiUrlModal } from './components/MultiUrlModal';
import { SettingsView } from './components/SettingsView';
import { useClipboardAutoDetect } from './hooks/useClipboardAutoDetect';
import { useJobProgress } from './hooks/useJobProgress';
import { selectExecutionPlan } from './services/hybridOrchestrator';
import {
  createInitialLocalJob,
  extractJobMediaLocations,
  findExistingReadyJobByNormalizedUrl,
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
const VALID_TABS: NavTab[] = ['home', 'history', 'engines', 'settings'];

declare global {
  interface Window {
    __devwolfHandleBack?: () => boolean;
  }
}

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
  const [scrollToAccounts, setScrollToAccounts] = useState(false);

  const [addLinkOpen, setAddLinkOpen] = useState(false);
  const [howToOpen, setHowToOpen] = useState(false);

  const autoStartLockRef = useRef(false);
  const activeTabRef = useRef<NavTab>(activeTab);
  activeTabRef.current = activeTab;
  const backStateRef = useRef({
    activeTab,
    addLinkOpen,
    howToOpen,
    hasMultiCandidates: multiCandidates.length > 0,
  });
  backStateRef.current = {
    activeTab,
    addLinkOpen,
    howToOpen,
    hasMultiCandidates: multiCandidates.length > 0,
  };

  const { job: activeJob, setActiveJob, engineSwitchNotice } = useJobProgress(null);

  const navigateToTab = useCallback((tab: NavTab) => {
    if (activeTabRef.current === tab) return;
    setActiveTab(tab);
    if (typeof window !== 'undefined' && window.history) {
      if (tab === 'home') {
        window.history.replaceState({ tab: 'home' }, '');
      } else {
        window.history.pushState({ tab }, '');
      }
    }
  }, []);

  useEffect(() => {
    if (typeof window === 'undefined') return;
    if (!window.history.state?.tab) {
      window.history.replaceState({ tab: 'home' }, '');
    }

    const handleBack = (): boolean => {
      const state = backStateRef.current;
      if (state.addLinkOpen) {
        setAddLinkOpen(false);
        return true;
      }
      if (state.howToOpen) {
        setHowToOpen(false);
        return true;
      }
      if (state.hasMultiCandidates) {
        setMultiCandidates([]);
        return true;
      }
      const openDialog = document.querySelector('dialog[open]') as HTMLDialogElement | null;
      if (openDialog) {
        if (typeof openDialog.close === 'function') {
          openDialog.close();
        } else {
          openDialog.removeAttribute('open');
        }
        return true;
      }
      if (state.activeTab !== 'home') {
        setScrollToAccounts(false);
        setActiveTab('home');
        window.history.replaceState({ tab: 'home' }, '');
        return true;
      }
      return false;
    };

    window.__devwolfHandleBack = handleBack;

    const onHardwareBack = (event: Event) => {
      const consumed = handleBack();
      if (consumed && event.cancelable) {
        event.preventDefault();
      }
    };

    const onPopState = (event: PopStateEvent) => {
      const state = event.state as { tab?: NavTab } | null;
      const targetTab: NavTab =
        state?.tab && VALID_TABS.includes(state.tab) ? state.tab : 'home';
      setActiveTab(targetTab);
    };

    window.addEventListener('devwolf:hardwareBack', onHardwareBack);
    window.addEventListener('popstate', onPopState);
    return () => {
      if (window.__devwolfHandleBack === handleBack) {
        delete window.__devwolfHandleBack;
      }
      window.removeEventListener('devwolf:hardwareBack', onHardwareBack);
      window.removeEventListener('popstate', onPopState);
    };
  }, []);

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
        allowDuplicate?: boolean;
        format?: FormatOption;
        quality?: QualityOption;
        fromShareIntent?: boolean;
      }
    ) => {
      const parsed = parseAndValidateUrl(rawUrl);
      if (!parsed) {
        throw new Error('URL inválida ou plataforma não suportada.');
      }

      if (!options.allowDuplicate) {
        const existingJob = findExistingReadyJobByNormalizedUrl(
          getLocalJobs(),
          parsed.normalizedUrl
        );
        if (existingJob) {
          const { uris, filePaths } = extractJobMediaLocations(existingJob);
          const { exists } = await nativeBridge.checkMediaExists({ uris, filePaths });
          if (exists) {
            setActiveJob(existingJob);
            navigateToTab('home');
            setAddLinkOpen(false);
            setStatusNotice('Esta mídia já foi baixada e ainda existe na sua Galeria.');
            return;
          }
        }
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
      navigateToTab,
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
    if (!isNativeAndroid) return;
    let cancelled = false;
    let unsubscribe: (() => void) | null = null;
    void nativeBridge.onClipboardBubbleResult((result) => {
      if (cancelled) return;
      if (result.url) {
        setPendingShare({ id: result.id, url: result.url, text: '', title: '', autoStart: true, fromNativeIntent: true });
      } else if (result.error) {
        setStatusNotice(result.error);
      }
    }).then((unsub) => {
      if (cancelled) unsub(); else unsubscribe = unsub;
    });
    return () => { cancelled = true; unsubscribe?.(); };
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
      navigateToTab('home');
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
      } else {
        setAddLinkOpen(true);
      }
    } else if (candidates.length > 1) {
      navigateToTab('home');
      setMultiCandidates(candidates);
    } else if (shareError) {
      navigateToTab('home');
      setStatusNotice(shareError);
    } else if (combined.trim()) {
      navigateToTab('home');
      setStatusNotice('Nenhum link suportado encontrado no item compartilhado.');
    }
  }, [navigateToTab, pendingShare, settings.auto_download, startJob]);

  const handleDetectedSingleUrl = useCallback(
    (parsed: ParsedPlatformUrl, fromExplicitPaste = false) => {
      setUrlInput(parsed.normalizedUrl);
      setStatusNotice(null);

      if (settings.auto_download && !fromExplicitPaste && !autoStartLockRef.current) {
        autoStartLockRef.current = true;
        void startJob(parsed.normalizedUrl, { forceManual: false })
          .catch((err) => {
            setStatusNotice(err instanceof Error ? err.message : 'Falha ao iniciar download.');
          })
          .finally(() => {
            autoStartLockRef.current = false;
          });
      } else if (!fromExplicitPaste && !settings.auto_download) {
        setAddLinkOpen(true);
      }
    },
    [settings.auto_download, startJob]
  );

  // No Android nativo detecta links novos ao abrir/focar mesmo com auto_download desligado (abre modal pré-preenchido);
  // na Web, leitura automática em foco só ocorre se auto_download estiver ativado.
  const { guidanceMessage, readClipboardExplicitly, setGuidanceMessage } = useClipboardAutoDetect({
    enabled:
      !booting &&
      onboardingDone &&
      activeTab === 'home' &&
      (settings.auto_download || isNativeAndroid),
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

  const handleExplicitPaste = async () => {
    setGuidanceMessage(null);
    await readClipboardExplicitly();
  };

  const handleOpenAddLinkModal = async () => {
    setGuidanceMessage(null);
    if (!urlInput.trim() && isNativeAndroid) {
      try {
        const text = await nativeBridge.readClipboard();
        if (text) {
          const candidates = extractSupportedUrlsFromText(text);
          if (candidates.length === 1) {
            setUrlInput(candidates[0].normalizedUrl);
          }
        }
      } catch {
        // Ignorar falha ao ler clipboard
      }
    }
    setAddLinkOpen(true);
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
    navigateToTab('home');
    try {
      await startJob(url, { forceManual: true, allowDuplicate: true });
    } catch (err) {
      setStatusNotice(err instanceof Error ? err.message : 'Erro ao repetir download.');
    }
  };

  const handleClearAllHistory = useCallback(() => {
    const activeStatuses = new Set(['queued', 'resolving', 'downloading', 'processing']);
    if (activeJob && !activeStatuses.has(activeJob.status)) {
      setActiveJob(null);
    }
    setLocalJobs(activeJob && activeStatuses.has(activeJob.status) ? [activeJob] : []);
  }, [activeJob, setActiveJob]);

  const handleReturnToSourceApp = async () => {
    await nativeBridge.returnToSourceApp();
  };

  if (booting) {
    return (
      <main className="flex min-h-screen items-center justify-center bg-[#F5F3FF] dark:bg-[#0D0B1E]">
        <div className="text-sm font-medium text-[#5B5482] dark:text-[#A5A1C8]">
          Carregando Devwolf Downloader…
        </div>
      </main>
    );
  }

  return (
    <div className="min-h-screen pb-8 text-[#1E1940] dark:text-[#F5F3FF]">
      <Header
        activeTab={activeTab}
        onNavigate={(tab) => {
          setScrollToAccounts(false);
          navigateToTab(tab);
        }}
        onOpenAccounts={() => {
          setFocusAccountPlatform(null);
          setScrollToAccounts(true);
          navigateToTab('settings');
        }}
        onOpenHowTo={() => setHowToOpen(true)}
      />

      <main className="mx-auto max-w-2xl px-4 pt-4">
        {activeTab === 'home' && (
          <HomeView
            localJobs={localJobs}
            activeJob={activeJob}
            engineSwitchNotice={engineSwitchNotice}
            isNativeAndroid={isNativeAndroid}
            onboardingDone={onboardingDone}
            showReturnToSourceApp={showReturnToSourceApp}
            statusNotice={statusNotice}
            onOnboardingChoice={(enable) => void handleOnboardingChoice(enable)}
            onOpenAddLink={() => void handleOpenAddLinkModal()}
            onOpenHistory={() => navigateToTab('history')}
            onOpenHowTo={() => setHowToOpen(true)}
            onOpenSettings={() => {
              setScrollToAccounts(false);
              navigateToTab('settings');
            }}
            onOpenAccountSettings={(platform) => {
              setFocusAccountPlatform(platform);
              setScrollToAccounts(true);
              navigateToTab('settings');
            }}
            onCancelJob={handleCancelJob}
            onRetryJob={(url) => startJob(url, { forceManual: true, allowDuplicate: true })}
            onReturnToSourceApp={handleReturnToSourceApp}
          />
        )}

        {activeTab === 'history' && (
          <HistoryView
            localJobs={localJobs}
            onRepeatDownload={handleRepeatFromHistory}
            onLocalJobDeleted={() => setLocalJobs(getLocalJobs())}
            onClearAllHistory={handleClearAllHistory}
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
            scrollToAccounts={scrollToAccounts}
            onClearFocusAccount={() => {
              setFocusAccountPlatform(null);
              setScrollToAccounts(false);
            }}
            onUpdateSettings={handleUpdateSettings}
          />
        )}
      </main>

      <AddLinkModal
        open={addLinkOpen}
        urlInput={urlInput}
        onUrlInputChange={setUrlInput}
        onUserEditingChange={setIsUserEditing}
        selectedFormat={selectedFormat}
        selectedQuality={selectedQuality}
        onFormatChange={setSelectedFormat}
        onQualityChange={setSelectedQuality}
        clipboardGuidance={guidanceMessage}
        statusNotice={statusNotice}
        submitting={submitting}
        onExplicitPaste={handleExplicitPaste}
        onSubmitDownload={handleManualDownload}
        onClose={() => setAddLinkOpen(false)}
      />

      <HowToDownloadModal open={howToOpen} onClose={() => setHowToOpen(false)} />

      {statusNotice && (
        <div role="status" className="fixed bottom-24 left-4 right-4 z-50 mx-auto max-w-lg rounded-2xl border border-[#7C5CFF]/30 bg-[#17132E] px-4 py-3 text-sm text-white shadow-xl">
          <div className="flex items-center justify-between gap-3">
            <span>{statusNotice}</span>
            <button type="button" aria-label="Fechar aviso" className="shrink-0 rounded-lg px-2 py-1" onClick={() => setStatusNotice(null)}>Fechar</button>
          </div>
        </div>
      )}

      <MultiUrlModal
        candidates={multiCandidates}
        onSelect={(picked) => {
          setMultiCandidates([]);
          handleDetectedSingleUrl(picked, true);
          if (!settings.auto_download) {
            setAddLinkOpen(true);
          }
        }}
        onClose={() => setMultiCandidates([])}
      />
    </div>
  );
}

export default App;
