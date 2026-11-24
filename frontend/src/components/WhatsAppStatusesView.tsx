import { useCallback, useEffect, useRef, useState } from 'react';
import { Check, Film, Image as ImageIcon, Loader2, Play, RefreshCw, FolderOpen } from 'lucide-react';
import { nativeBridge, type WhatsAppStatusItem } from '../services/nativeBridge';

function StatusThumbnail({ item }: { item: WhatsAppStatusItem }) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [visible, setVisible] = useState(false);
  const [thumbnail, setThumbnail] = useState<string | null>(null);
  const isVideo = item.mimeType.startsWith('video/');
  const Icon = isVideo ? Film : ImageIcon;

  useEffect(() => {
    const element = containerRef.current;
    if (!element) return;
    if (typeof IntersectionObserver === 'undefined') {
      setVisible(true);
      return;
    }
    const observer = new IntersectionObserver(([entry]) => {
      if (entry.isIntersecting) {
        setVisible(true);
        observer.disconnect();
      }
    }, { rootMargin: '160px' });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (!visible) return;
    let cancelled = false;
    void nativeBridge.getWhatsAppStatusThumbnail(item.uri)
      .then((dataUrl) => { if (!cancelled) setThumbnail(dataUrl); })
      .catch(() => undefined);
    return () => { cancelled = true; };
  }, [item.uri, visible]);

  return (
    <div ref={containerRef} className="relative aspect-[3/4] w-full overflow-hidden bg-gradient-to-br from-[#362C63] to-[#120E29]">
      {thumbnail ? (
        <img src={thumbnail} alt={`Prévia de ${item.name}`} loading="lazy" className="h-full w-full object-cover" />
      ) : (
        <div className="flex h-full w-full items-center justify-center text-[#C4B5FF]">
          <Icon className="h-10 w-10 opacity-80" aria-hidden="true" />
        </div>
      )}
      {isVideo && <span className="absolute bottom-2 right-2 flex h-8 w-8 items-center justify-center rounded-full bg-black/65 text-white shadow" aria-label="Vídeo">
        <Play className="ml-0.5 h-4 w-4 fill-current" aria-hidden="true" />
      </span>}
    </div>
  );
}

export function WhatsAppStatusesView() {
  const [items, setItems] = useState<WhatsAppStatusItem[]>([]);
  const [linked, setLinked] = useState(false);
  const [loading, setLoading] = useState(true);
  const [choosing, setChoosing] = useState(false);
  const [savingUri, setSavingUri] = useState<string | null>(null);
  const [saved, setSaved] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await nativeBridge.getWhatsAppStatuses();
      setLinked(result.linked);
      setItems(result.items);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Não foi possível ler a pasta de Status.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void refresh(); }, [refresh]);

  const chooseFolder = async (defaultLocation: boolean) => {
    setChoosing(true);
    setError(null);
    setNotice(null);
    try {
      const result = await nativeBridge.pickWhatsAppStatusesFolder(defaultLocation);
      if (!result.cancelled) {
        setLinked(true);
        setItems(result.items);
        setNotice(`Pasta vinculada. ${result.items.length} Status encontrados.`);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Não foi possível abrir a pasta de Status.');
    } finally {
      setChoosing(false);
    }
  };

  const save = async (item: WhatsAppStatusItem) => {
    setSavingUri(item.uri);
    setError(null);
    setNotice(null);
    try {
      const result = await nativeBridge.importWhatsAppStatus(item.uri, item.mimeType);
      setSaved((current) => ({ ...current, [item.uri]: result.fileName }));
      setNotice(`${result.fileName} salvo em ${result.displayPath}.`);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Não foi possível salvar esta mídia.');
    } finally {
      setSavingUri(null);
    }
  };

  return (
    <section className="space-y-4 pb-8" aria-label="WhatsApp Status">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h2 className="text-base font-bold text-[#1E1940] dark:text-[#F5F3FF]">WhatsApp Status</h2>
          <p className="mt-1 text-xs text-[#5B5482] dark:text-[#A5A1C8]">Toque em uma mídia para salvar na Galeria.</p>
        </div>
        {linked && <button type="button" onClick={() => void refresh()} disabled={loading}
          className="inline-flex min-h-[44px] items-center gap-2 rounded-xl px-3 text-xs font-semibold text-[#5E43F3] hover:bg-[#7C5CFF]/10 disabled:opacity-50 dark:text-[#C4B5FF]">
          <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} aria-hidden="true" /> Atualizar
        </button>}
      </div>

      {error && <p role="alert" className="rounded-2xl border border-rose-500/30 bg-rose-500/10 p-3 text-sm text-rose-600 dark:text-rose-300">{error}</p>}
      {notice && <p role="status" className="rounded-2xl border border-[#7C5CFF]/25 bg-[#7C5CFF]/10 p-3 text-xs text-[#5E43F3] dark:text-[#C4B5FF]">{notice}</p>}

      {!linked && !loading && (
        <div className="dw-glass-card rounded-3xl p-5 text-center">
          <FolderOpen className="mx-auto h-9 w-9 text-[#7C5CFF]" aria-hidden="true" />
          <h3 className="mt-3 text-sm font-bold">Vincule a pasta de Status</h3>
          <p className="mx-auto mt-1 max-w-sm text-xs text-[#5B5482] dark:text-[#A5A1C8]">O Android pedirá para confirmar a pasta. Só aparecem Status que já foram carregados no aparelho.</p>
          <div className="mx-auto mt-4 grid max-w-sm gap-2 sm:grid-cols-2">
            <button type="button" disabled={choosing} onClick={() => void chooseFolder(true)} className="dw-btn-primary min-h-[46px] rounded-xl px-3 text-xs font-semibold disabled:opacity-50">{choosing ? 'Abrindo…' : 'Pasta padrão'}</button>
            <button type="button" disabled={choosing} onClick={() => void chooseFolder(false)} className="min-h-[46px] rounded-xl border border-[#7C5CFF]/30 px-3 text-xs font-semibold text-[#5E43F3] disabled:opacity-50 dark:text-[#C4B5FF]">Escolher pasta</button>
          </div>
        </div>
      )}

      {loading ? <div className="dw-glass-card rounded-3xl p-6 text-center text-sm text-[#5B5482] dark:text-[#A5A1C8]">Lendo pasta de Status…</div>
        : linked && items.length === 0 ? <div className="dw-glass-card rounded-3xl p-6 text-center text-sm text-[#5B5482] dark:text-[#A5A1C8]">Nenhum Status encontrado. Abra a mídia no WhatsApp e toque em Atualizar lista.</div>
          : linked && <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
            {items.map((item) => {
              const isVideo = item.mimeType.startsWith('video/');
              const isSaving = savingUri === item.uri;
              const isSaved = Boolean(saved[item.uri]);
              return <button key={item.uri} type="button" disabled={isSaving} onClick={() => void save(item)}
                className="dw-glass-card group overflow-hidden rounded-3xl text-left transition hover:border-[#7C5CFF]/50 hover:bg-[#7C5CFF]/10 disabled:opacity-60">
                <StatusThumbnail item={item} />
                <span className="block w-full truncate px-3 pt-2.5 text-xs font-semibold text-[#1E1940] dark:text-[#F5F3FF]">{item.name}</span>
                <span className="flex min-h-9 items-center gap-1.5 px-3 pb-2 text-[11px] font-medium text-[#5B5482] dark:text-[#A5A1C8]">
                  {isSaving ? <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden="true" /> : isSaved ? <Check className="h-3.5 w-3.5 text-emerald-500" aria-hidden="true" /> : null}
                  {isSaving ? 'Salvando…' : isSaved ? 'Salvo na Galeria' : isVideo ? 'Toque para salvar vídeo' : 'Toque para salvar foto'}
                </span>
              </button>;
            })}
          </div>}
    </section>
  );
}
