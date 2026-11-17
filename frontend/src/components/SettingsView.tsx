import { useEffect, useState } from 'react';
import {
  Globe,
  HardDrive,
  KeyRound,
  ShieldCheck,
  Smartphone,
  Trash2,
} from 'lucide-react';
import { nativeBridge } from '../services/nativeBridge';
import { normalizeVpsUrl } from '../services/settingsStorage';
import type {
  ExecutionMode,
  PlatformId,
  PlatformSessionInfo,
  QualityOption,
  StatusSegmentDuration,
  ThemeMode,
  UserSettings,
  VpsEngineName,
} from '../types/api';
import { formatBytes } from '../utils/format';

interface SettingsViewProps {
  settings: UserSettings;
  isNativeAndroid: boolean;
  vpsAuthenticated: boolean;
  onUpdateSettings: (patch: Partial<UserSettings>) => Promise<void>;
  onRequestVpsLogin: () => void;
}

const ALL_ENGINES: { id: VpsEngineName; label: string; desc: string }[] = [
  { id: 'yt-dlp', label: 'yt-dlp (VPS)', desc: 'Vídeos, Reels, Shorts e áudio' },
  { id: 'gallery-dl', label: 'gallery-dl (VPS)', desc: 'Fotos, posts e carrosséis' },
  { id: 'cobalt', label: 'Cobalt (VPS)', desc: 'Instância própria configurada no servidor' },
];

const EXECUTION_MODES: { id: ExecutionMode; label: string; desc: string }[] = [
  { id: 'auto', label: 'Automático', desc: 'Prioriza Motor Local no Android com fallback para VPS' },
  { id: 'local', label: 'Local (Dispositivo)', desc: 'Somente yt-dlp + FFmpeg no Android (offline da VPS)' },
  { id: 'vps', label: 'Remoto (VPS)', desc: 'Executa na VPS (Cobalt, yt-dlp, gallery-dl)' },
];

const VAULT_PLATFORMS: { id: PlatformId; label: string }[] = [
  { id: 'instagram', label: 'Instagram' },
  { id: 'youtube', label: 'YouTube' },
  { id: 'tiktok', label: 'TikTok' },
  { id: 'facebook', label: 'Facebook' },
  { id: 'twitter', label: 'X / Twitter' },
];

export function SettingsView({
  settings,
  isNativeAndroid,
  vpsAuthenticated,
  onUpdateSettings,
  onRequestVpsLogin,
}: SettingsViewProps) {
  const [savingKey, setSavingKey] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [vpsUrlDraft, setVpsUrlDraft] = useState(settings.vps_url);
  const [sessions, setSessions] = useState<PlatformSessionInfo[]>([]);
  const [vaultPlatform, setVaultPlatform] = useState<PlatformId>('instagram');
  const [vaultCookie, setVaultCookie] = useState('');
  const [cacheClearing, setCacheClearing] = useState(false);

  useEffect(() => {
    setVpsUrlDraft(settings.vps_url);
  }, [settings.vps_url]);

  useEffect(() => {
    if (!isNativeAndroid) return;
    void nativeBridge.getPlatformSessions().then(setSessions);
  }, [isNativeAndroid]);

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

  const toggleEngine = (engine: VpsEngineName) => {
    const exists = settings.enabled_engines.includes(engine);
    const next = exists
      ? settings.enabled_engines.filter((e) => e !== engine)
      : [...settings.enabled_engines, engine];
    if (next.length === 0) {
      setError('Mantenha pelo menos um motor da VPS habilitado.');
      return;
    }
    void patch('enabled_engines', { enabled_engines: next });
  };

  const handleSaveVpsUrl = async () => {
    setError(null);
    setNotice(null);
    const trimmed = vpsUrlDraft.trim();
    if (trimmed && !normalizeVpsUrl(trimmed)) {
      setError('Endereço da VPS inválido. Use http:// ou https://.');
      return;
    }
    await patch('vps_url', { vps_url: normalizeVpsUrl(trimmed) });
    setNotice('Endereço da VPS salvo.');
  };

  const handleSaveVaultCookie = async () => {
    if (!vaultCookie.trim()) {
      setError('Cole o cabeçalho de sessão/cookie antes de salvar no cofre local.');
      return;
    }
    setError(null);
    setNotice(null);
    try {
      await nativeBridge.savePlatformSession(vaultPlatform, vaultCookie.trim());
      setVaultCookie('');
      const refreshed = await nativeBridge.getPlatformSessions();
      setSessions(refreshed);
      setNotice(`Sessão local de ${vaultPlatform} salva com criptografia no dispositivo.`);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Erro ao salvar sessão no cofre local.');
    }
  };

  const handleRevokeVault = async (platform: PlatformId | string) => {
    setError(null);
    setNotice(null);
    try {
      await nativeBridge.revokePlatformSession(platform);
      const refreshed = await nativeBridge.getPlatformSessions();
      setSessions(refreshed);
      setNotice(`Sessão local de ${platform} revogada.`);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Erro ao revogar sessão.');
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

      {/* Motor preferencial */}
      <section className="rounded-2xl border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-[#1C1C20]">
        <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
          Motor preferencial
        </h3>
        <div className="mt-3 space-y-2">
          {EXECUTION_MODES.map((mode) => {
            const active = settings.preferred_execution_mode === mode.id;
            return (
              <button
                key={mode.id}
                type="button"
                onClick={() => void patch('preferred_execution_mode', { preferred_execution_mode: mode.id })}
                className={`flex min-h-[52px] w-full items-center justify-between gap-3 rounded-xl border px-3.5 py-2.5 text-left transition ${
                  active
                    ? 'border-[#A3E635] bg-[#A3E635]/10'
                    : 'border-zinc-200 bg-zinc-50 hover:border-zinc-400 dark:border-zinc-800 dark:bg-[#101012]'
                }`}
              >
                <div>
                  <p className="text-sm font-medium text-zinc-900 dark:text-zinc-100">
                    {mode.label}
                  </p>
                  <p className="text-xs text-zinc-500 dark:text-zinc-400">{mode.desc}</p>
                </div>
                <span
                  className={`rounded-lg px-2.5 py-1 text-[11px] font-bold ${
                    active
                      ? 'bg-[#A3E635] text-zinc-950'
                      : 'bg-zinc-200 text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400'
                  }`}
                >
                  {active ? 'ATIVO' : 'USAR'}
                </span>
              </button>
            );
          })}
        </div>
      </section>

      {/* Modo Status (WhatsApp) */}
      <section className="divide-y divide-zinc-200 rounded-2xl border border-zinc-200 bg-white dark:divide-zinc-800 dark:border-zinc-800 dark:bg-[#1C1C20]">
        <div className="flex items-center justify-between gap-4 p-4">
          <div>
            <p className="text-sm font-medium text-zinc-900 dark:text-zinc-100">
              Modo Status (WhatsApp)
            </p>
            <p className="text-xs text-zinc-500 dark:text-zinc-400">
              Garante H.264 + AAC em MP4 sem recomprimir vídeos já compatíveis
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
              Iniciar job ao detectar link válido no clipboard ou compartilhamento
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
              Atualização automática dos motores
            </p>
            <p className="text-xs text-zinc-500 dark:text-zinc-400">
              Verificar novas versões do yt-dlp local quando sem downloads ativos
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
              htmlFor="setting-retention"
              className="text-sm font-medium text-zinc-900 dark:text-zinc-100"
            >
              Retenção de arquivos (VPS)
            </label>
            <p className="text-xs text-zinc-500 dark:text-zinc-400">
              Tempo até limpeza automática no servidor
            </p>
          </div>
          <select
            id="setting-retention"
            value={settings.file_retention_hours}
            onChange={(e) =>
              void patch('file_retention_hours', {
                file_retention_hours: Number(e.target.value),
              })
            }
            className="min-h-[44px] rounded-xl border border-zinc-300 bg-zinc-50 px-3 text-xs font-medium text-zinc-800 outline-none dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200"
          >
            <option value={6}>6 horas</option>
            <option value={12}>12 horas</option>
            <option value={24}>24 horas</option>
            <option value={48}>48 horas</option>
            <option value={72}>72 horas</option>
          </select>
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

      {/* Endereço da VPS */}
      <section className="rounded-2xl border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-[#1C1C20]">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <Globe className="h-4 w-4 text-[#65a30d] dark:text-[#A3E635]" aria-hidden="true" />
            <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
              Endereço da VPS
            </h3>
          </div>
          <span className="text-xs text-zinc-500 dark:text-zinc-400">
            {vpsAuthenticated ? 'Conectada' : 'Desconectada'}
          </span>
        </div>
        <p className="mt-1 text-xs text-zinc-500 dark:text-zinc-400">
          Deixe vazio na PWA (mesma origem) ou informe a URL HTTPS da sua VPS no aplicativo Android.
        </p>
        <div className="mt-3 flex gap-2">
          <input
            type="url"
            inputMode="url"
            aria-label="Endereço da VPS"
            placeholder="https://downloader.seudominio.com"
            value={vpsUrlDraft}
            onChange={(e) => setVpsUrlDraft(e.target.value)}
            className="min-h-[44px] flex-1 rounded-xl border border-zinc-300 bg-zinc-50 px-3 text-sm text-zinc-900 outline-none focus:border-[#A3E635] dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-100"
          />
          <button
            type="button"
            onClick={() => void handleSaveVpsUrl()}
            className="min-h-[44px] rounded-xl bg-[#A3E635] px-4 text-xs font-semibold text-zinc-950 transition hover:bg-[#84CC16]"
          >
            Salvar
          </button>
        </div>
        {!vpsAuthenticated && (
          <button
            type="button"
            onClick={onRequestVpsLogin}
            className="mt-2.5 min-h-[44px] rounded-xl border border-zinc-300 bg-zinc-50 px-4 text-xs font-medium text-zinc-800 transition hover:bg-zinc-200 dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200"
          >
            Autenticar na VPS agora
          </button>
        )}
      </section>

      {/* Motores habilitados na VPS */}
      <section className="rounded-2xl border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-[#1C1C20]">
        <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
          Motores habilitados na VPS
        </h3>
        <div className="mt-3 space-y-2">
          {ALL_ENGINES.map((engine) => {
            const enabled = settings.enabled_engines.includes(engine.id);
            return (
              <div
                key={engine.id}
                className="flex items-center justify-between gap-3 rounded-xl border border-zinc-200 bg-zinc-50 px-3.5 py-2.5 dark:border-zinc-800 dark:bg-[#101012]"
              >
                <div>
                  <p className="text-sm font-medium text-zinc-900 dark:text-zinc-100">
                    {engine.label}
                  </p>
                  <p className="text-xs text-zinc-500 dark:text-zinc-400">{engine.desc}</p>
                </div>
                <button
                  type="button"
                  role="switch"
                  aria-checked={enabled}
                  onClick={() => toggleEngine(engine.id)}
                  className={`min-h-[44px] min-w-[68px] rounded-xl px-3 text-xs font-bold transition ${
                    enabled
                      ? 'bg-[#A3E635] text-zinc-950'
                      : 'border border-zinc-300 bg-white text-zinc-600 dark:border-zinc-700 dark:bg-[#1C1C20] dark:text-zinc-400'
                  }`}
                >
                  {enabled ? 'ON' : 'OFF'}
                </button>
              </div>
            );
          })}
        </div>
      </section>

      {/* Cofre local de sessões seguras (somente Android) */}
      {isNativeAndroid && (
        <section className="rounded-2xl border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-[#1C1C20]">
          <div className="flex items-center gap-2">
            <KeyRound className="h-4 w-4 text-[#65a30d] dark:text-[#A3E635]" aria-hidden="true" />
            <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
              Sessões locais seguras (cofre no dispositivo)
            </h3>
          </div>
          <p className="mt-1 flex items-center gap-1.5 text-xs text-zinc-500 dark:text-zinc-400">
            <ShieldCheck className="h-3.5 w-3.5 shrink-0 text-[#65a30d] dark:text-[#A3E635]" />
            <span>
              Criptografado localmente via EncryptedSharedPreferences. Cookies nunca são enviados à
              VPS.
            </span>
          </p>

          <div className="mt-3 space-y-2">
            <div className="flex gap-2">
              <select
                aria-label="Plataforma da sessão local"
                value={vaultPlatform}
                onChange={(e) => setVaultPlatform(e.target.value as PlatformId)}
                className="min-h-[44px] rounded-xl border border-zinc-300 bg-zinc-50 px-3 text-xs font-medium text-zinc-800 outline-none dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-200"
              >
                {VAULT_PLATFORMS.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.label}
                  </option>
                ))}
              </select>
              <input
                type="password"
                aria-label="Cookie de sessão local"
                placeholder="Cole o cookie apenas para uso local…"
                value={vaultCookie}
                onChange={(e) => setVaultCookie(e.target.value)}
                className="min-h-[44px] flex-1 rounded-xl border border-zinc-300 bg-zinc-50 px-3 text-xs text-zinc-900 outline-none focus:border-[#A3E635] dark:border-zinc-700 dark:bg-[#101012] dark:text-zinc-100"
              />
              <button
                type="button"
                onClick={() => void handleSaveVaultCookie()}
                className="min-h-[44px] rounded-xl bg-[#A3E635] px-3.5 text-xs font-semibold text-zinc-950 transition hover:bg-[#84CC16]"
              >
                Salvar
              </button>
            </div>

            {sessions.some((s) => s.configured) && (
              <ul className="space-y-1.5 pt-1">
                {sessions
                  .filter((s) => s.configured)
                  .map((s) => (
                    <li
                      key={s.platform}
                      className="flex items-center justify-between rounded-xl border border-zinc-200 bg-zinc-50 px-3 py-2 text-xs dark:border-zinc-800 dark:bg-[#101012]"
                    >
                      <span className="font-medium capitalize text-zinc-800 dark:text-zinc-200">
                        {s.platform} • Configurada
                      </span>
                      <button
                        type="button"
                        onClick={() => void handleRevokeVault(s.platform)}
                        className="inline-flex min-h-[44px] items-center gap-1 rounded-lg px-2.5 text-xs font-medium text-red-500 hover:bg-red-500/10"
                      >
                        <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
                        <span>Revogar</span>
                      </button>
                    </li>
                  ))}
              </ul>
            )}
          </div>
        </section>
      )}

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

      <section className="flex items-start gap-2.5 rounded-2xl border border-zinc-200 bg-white p-4 text-xs text-zinc-600 dark:border-zinc-800 dark:bg-[#1C1C20] dark:text-zinc-400">
        <Smartphone className="mt-0.5 h-4 w-4 shrink-0 text-[#65a30d] dark:text-[#A3E635]" aria-hidden="true" />
        <p className="leading-relaxed">
          No aplicativo Android, o Devwolf Downloader recebe links diretamente do menu Compartilhar
          e salva os arquivos no álbum &ldquo;Devwolf Downloader&rdquo; da Galeria. Na web,
          instale como PWA pelo Chrome para ativar o compartilhamento rápido.
        </p>
      </section>
    </div>
  );
}
