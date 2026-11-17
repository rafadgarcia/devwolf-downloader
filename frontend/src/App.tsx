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
import { api, setUnauthorizedHandler } from './services/api';
import {
  clearPendingShareFromSession,
  collectPendingShare,
} from './services/shareTarget';
import type {
  AuthUser,
  FormatOption,
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
import { extractSupportedUrlsFromText, parseAndValidateUrl, type ParsedPlatformUrl } from './utils/url';

const ONBOARDING_KEY = 'droplink_onboarding_done_v1';
const LAST_JOB_KEY = 'droplink_last_job_id_v1';

const DEFAULT_SETTINGS: UserSettings = {
  auto_download: false,
  default_quality: 'best',
  data_saver: false,
  file_retention_hours: 24,
  theme: 'dark',
  enabled_engines: ['yt-dlp', 'gallery-dl', 'cobalt'],
};

function generateIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

export function App() {
  const [authLoading, setAuthLoading] = useState(true);
  const [user, setUser] = useState<AuthUser | null>(null);
  const [pendingShare, setPendingShare] = useState<PendingSharePayload | null>(null);

  const [activeTab, setActiveTab] = useState<NavTab>('home');
  const [settings, setSettings] = useState<UserSettings>(DEFAULT_SETTINGS);
  const [onboardingDone, setOnboardingDone] = useState<boolean>(() => {
    try {
      return window.localStorage.getItem(ONBOARDING_KEY) === '1';
    } catch {
      return false;
    }
  });

  const [urlInput, setUrlInput] = useState('');
  const [isUserEditing, setIsUserEditing] = useState(false);
  const [selectedFormat, setSelectedFormat] = useState<FormatOption>('auto');
  const [selectedQuality, setSelectedQuality] = useState<QualityOption>('best');
  const [submitting, setSubmitting] = useState(false);
  const [statusNotice, setStatusNotice] = useState<string | null>(null);
  const [multiCandidates, setMultiCandidates] = useState<ParsedPlatformUrl[]>([]);

  const { job: activeJob, setActiveJob, engineSwitchNotice, connectionMode } = useJobProgress(null);
  const autoStartLockRef = useRef(false);

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
      options: { forceManual: boolean; format?: FormatOption; quality?: QualityOption }
    ) => {
      const parsed = parseAndValidateUrl(rawUrl);
      if (!parsed) {
        throw new Error('URL inválida ou plataforma não suportada.');
      }

      const fmt = options.format ?? selectedFormat;
      const qual = options.quality ?? selectedQuality;
      const fingerprint = buildFingerprint(parsed.normalizedUrl, fmt, qual);

      if (!options.forceManual) {
        const duplicate = isDuplicateAutoDownload(fingerprint);
        if (duplicate) {
          setStatusNotice('Link já processado recentemente. Use Baixar para repetir manualmente.');
          return;
        }
      }

      setSubmitting(true);
      setStatusNotice(null);
      try {
        const created = await api.createJob({
          url: parsed.normalizedUrl,
          format: fmt,
          quality: qual,
          idempotency_key: generateIdempotencyKey(),
          force_manual: options.forceManual,
        });

        setActiveJob(created);
        try {
          window.localStorage.setItem(LAST_JOB_KEY, created.id);
        } catch {
          // Ignorar falha de localStorage
        }

        markActiveJobFingerprint(fingerprint, created.id);
        if (!options.forceManual) {
          markRecentAutoDownload(fingerprint, created.id);
        }
      } catch (err) {
        clearActiveJobFingerprint(fingerprint);
        throw err;
      } finally {
        setSubmitting(false);
      }
    },
    [selectedFormat, selectedQuality, setActiveJob]
  );

  // Carregar sessão e estado inicial ao montar
  useEffect(() => {
    setUnauthorizedHandler(() => {
      setUser(null);
    });

    let mounted = true;
    const bootstrap = async () => {
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
      } finally {
        if (mounted) setAuthLoading(false);
      }
    };
    void bootstrap();

    return () => {
      mounted = false;
      setUnauthorizedHandler(null);
    };
  }, []);

  // Carregar ajustes e recuperar job ativo/concluído ao autenticar
  useEffect(() => {
    if (!user) return;
    let mounted = true;

    const loadUserData = async () => {
      try {
        const loadedSettings = await api.getSettings();
        if (!mounted) return;
        setSettings(loadedSettings);
        setSelectedQuality(loadedSettings.default_quality);
      } catch {
        // Mantém defaults caso backend ainda esteja inicializando
      }

      try {
        const lastJobId = window.localStorage.getItem(LAST_JOB_KEY);
        if (lastJobId) {
          const recovered = await api.getJob(lastJobId);
          if (mounted && recovered) {
            setActiveJob(recovered);
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

  // Processar link compartilhado do Android Web Share Target após autenticação
  useEffect(() => {
    if (!user || !pendingShare) return;

    const shareError = pendingShare.error;
    const combined = [pendingShare.url, pendingShare.text, pendingShare.title]
      .filter(Boolean)
      .join(' ');
    const candidates = extractSupportedUrlsFromText(combined);

    clearPendingShareFromSession();
    setPendingShare(null);

    if (candidates.length === 1) {
      const chosen = candidates[0];
      setUrlInput(chosen.normalizedUrl);
      setActiveTab('home');
      if (settings.auto_download && !autoStartLockRef.current) {
        autoStartLockRef.current = true;
        void startJob(chosen.normalizedUrl, { forceManual: false }).finally(() => {
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
  }, [pendingShare, settings.auto_download, startJob, user]);

  const handleDetectedSingleUrl = useCallback(
    (parsed: ParsedPlatformUrl, fromExplicitPaste = false) => {
      setUrlInput(parsed.normalizedUrl);
      setStatusNotice(null);

      if (settings.auto_download && !autoStartLockRef.current) {
        autoStartLockRef.current = true;
        void startJob(parsed.normalizedUrl, { forceManual: fromExplicitPaste }).catch(
          (err) => {
            setStatusNotice(err instanceof Error ? err.message : 'Falha ao iniciar download.');
          }
        ).finally(() => {
          autoStartLockRef.current = false;
        });
      }
    },
    [settings.auto_download, startJob]
  );

  const { guidanceMessage, readClipboardExplicitly, setGuidanceMessage } = useClipboardAutoDetect({
    enabled: Boolean(user) && onboardingDone && settings.auto_download && activeTab === 'home',
    isUserEditing,
    onSingleUrlDetected: (parsed, explicitUserGesture) =>
      handleDetectedSingleUrl(parsed, explicitUserGesture),
    onMultipleUrlsDetected: (candidates) => setMultiCandidates(candidates),
  });

  const handleLogin = async (username: string, password: string) => {
    const res = await api.login(username, password);
    if (res.authenticated && res.user) {
      setUser(res.user);
      const serverShare = await collectPendingShare(true);
      if (serverShare) {
        setPendingShare(serverShare);
      }
    }
  };

  const handleLogout = async () => {
    await api.logout();
    setUser(null);
    setActiveJob(null);
  };

  const handleUpdateSettings = async (patch: Partial<UserSettings>) => {
    const next = await api.updateSettings(patch);
    setSettings(next);
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
    try {
      await handleUpdateSettings({ auto_download: enableAuto });
    } catch {
      setSettings((prev) => ({ ...prev, auto_download: enableAuto }));
    }
  };

  const handleToggleAutoDownload = async () => {
    const target = !settings.auto_download;
    try {
      await handleUpdateSettings({ auto_download: target });
    } catch {
      setSettings((prev) => ({ ...prev, auto_download: target }));
    }
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
      const updated = await api.cancelJob(jobId);
      setActiveJob(updated);
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

  if (authLoading) {
    return (
      <main className="flex min-h-screen items-center justify-center bg-[#F4F4F5] dark:bg-[#101012]">
        <div className="text-sm text-zinc-500 dark:text-zinc-400">Carregando DropLink…</div>
      </main>
    );
  }

  if (!user) {
    return <LoginScreen pendingShare={pendingShare} onLogin={handleLogin} />;
  }

  return (
    <div className="min-h-screen bg-[#F4F4F5] pb-24 text-zinc-900 dark:bg-[#101012] dark:text-zinc-100">
      <Header
        username={user.username}
        theme={settings.theme}
        onToggleTheme={() =>
          void handleUpdateSettings({
            theme: settings.theme === 'dark' ? 'light' : 'dark',
          }).catch(() =>
            setSettings((prev) => ({
              ...prev,
              theme: prev.theme === 'dark' ? 'light' : 'dark',
            }))
          )
        }
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
            onboardingDone={onboardingDone}
            onOnboardingChoice={(enable) => void handleOnboardingChoice(enable)}
            onToggleAutoDownload={() => void handleToggleAutoDownload()}
            onExplicitPaste={handleExplicitPaste}
            onSubmitManualDownload={handleManualDownload}
            onCancelJob={handleCancelJob}
            onRetryJob={(url) => startJob(url, { forceManual: true })}
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
            onRepeatDownload={handleRepeatFromHistory}
            onEditUrl={handleEditFromHistory}
          />
        )}

        {activeTab === 'engines' && <EnginesView />}

        {activeTab === 'settings' && (
          <SettingsView settings={settings} onUpdateSettings={handleUpdateSettings} />
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
