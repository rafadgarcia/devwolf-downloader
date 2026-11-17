import { useState } from 'react';
import { Smartphone } from 'lucide-react';
import type { EngineName, QualityOption, ThemeMode, UserSettings } from '../types/api';

interface SettingsViewProps {
  settings: UserSettings;
  onUpdateSettings: (patch: Partial<UserSettings>) => Promise<void>;
}

const ALL_ENGINES: { id: EngineName; label: string; desc: string }[] = [
  { id: 'yt-dlp', label: 'yt-dlp', desc: 'Vídeos, Reels, Shorts e áudio' },
  { id: 'gallery-dl', label: 'gallery-dl', desc: 'Fotos, posts e carrosséis' },
  { id: 'cobalt', label: 'Cobalt', desc: 'Instância própria configurada no servidor' },
];

export function SettingsView({ settings, onUpdateSettings }: SettingsViewProps) {
  const [savingKey, setSavingKey] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const patch = async (key: string, partial: Partial<UserSettings>) => {
    setSavingKey(key);
    setError(null);
    try {
      await onUpdateSettings(partial);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Erro ao salvar configuração.');
    } finally {
      setSavingKey(null);
    }
  };

  const toggleEngine = (engine: EngineName) => {
    const exists = settings.enabled_engines.includes(engine);
    const next = exists
      ? settings.enabled_engines.filter((e) => e !== engine)
      : [...settings.enabled_engines, engine];
    if (next.length === 0) {
      setError('Mantenha pelo menos um motor habilitado.');
      return;
    }
    void patch('enabled_engines', { enabled_engines: next });
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

      <section className="divide-y divide-zinc-200 rounded-2xl border border-zinc-200 bg-white dark:divide-zinc-800 dark:border-zinc-800 dark:bg-[#1C1C20]">
        <div className="flex items-center justify-between gap-4 p-4">
          <div>
            <p className="text-sm font-medium text-zinc-900 dark:text-zinc-100">
              Download automático
            </p>
            <p className="text-xs text-zinc-500 dark:text-zinc-400">
              Iniciar job ao detectar link válido no clipboard ou ao colar
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
              Retenção de arquivos
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

      <section className="rounded-2xl border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-[#1C1C20]">
        <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
          Motores habilitados
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

      <section className="flex items-start gap-2.5 rounded-2xl border border-zinc-200 bg-white p-4 text-xs text-zinc-600 dark:border-zinc-800 dark:bg-[#1C1C20] dark:text-zinc-400">
        <Smartphone className="mt-0.5 h-4 w-4 shrink-0 text-[#65a30d] dark:text-[#A3E635]" aria-hidden="true" />
        <p className="leading-relaxed">
          Para receber links direto do menu Compartilhar do Android, instale o DropLink como PWA
          pelo Chrome (&ldquo;Adicionar à tela inicial&rdquo;). Leitura automática de clipboard
          requer HTTPS e permissão do navegador; no iOS, use o botão Colar quando necessário.
        </p>
      </section>
    </div>
  );
}
