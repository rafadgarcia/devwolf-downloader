import type { EngineHealthState, EngineOrigin, JobStatus, PlatformId } from '../types/api';

export const PLATFORM_META: Record<
  PlatformId,
  { label: string; shortLabel: string; badgeClass: string }
> = {
  instagram: {
    label: 'Instagram',
    shortLabel: 'IG',
    badgeClass: 'bg-pink-500/15 text-pink-300 border-pink-500/30',
  },
  youtube: {
    label: 'YouTube',
    shortLabel: 'YT',
    badgeClass: 'bg-red-500/15 text-red-300 border-red-500/30',
  },
  tiktok: {
    label: 'TikTok',
    shortLabel: 'TT',
    badgeClass: 'bg-cyan-500/15 text-cyan-300 border-cyan-500/30',
  },
  twitter: {
    label: 'X / Twitter',
    shortLabel: 'X',
    badgeClass: 'bg-sky-500/15 text-sky-300 border-sky-500/30',
  },
  facebook: {
    label: 'Facebook',
    shortLabel: 'FB',
    badgeClass: 'bg-blue-500/15 text-blue-300 border-blue-500/30',
  },
  reddit: {
    label: 'Reddit',
    shortLabel: 'RD',
    badgeClass: 'bg-orange-500/15 text-orange-300 border-orange-500/30',
  },
  pinterest: {
    label: 'Pinterest',
    shortLabel: 'PIN',
    badgeClass: 'bg-rose-500/15 text-rose-300 border-rose-500/30',
  },
};

export function getPhaseLabel(status: JobStatus): string {
  switch (status) {
    case 'queued':
      return 'Link detectado';
    case 'resolving':
      return 'Procurando mídia';
    case 'downloading':
      return 'Baixando';
    case 'processing':
      return 'Preparando arquivo';
    case 'ready':
      return 'Pronto para baixar';
    case 'failed':
      return 'Falhou';
    case 'cancelled':
      return 'Cancelado';
    case 'expired':
      return 'Expirado';
    default:
      return 'Processando';
  }
}

export function formatDisplayEngine(
  engine: string | null | undefined,
  origin?: EngineOrigin | string | null
): string {
  if (!engine) {
    return origin === 'local' ? 'Local • yt-dlp Android' : 'VPS';
  }
  if (engine === 'local-ytdlp' || origin === 'local') {
    return 'Local • yt-dlp Android';
  }
  switch (engine) {
    case 'cobalt':
      return 'VPS • Cobalt';
    case 'yt-dlp':
      return 'VPS • yt-dlp';
    case 'gallery-dl':
      return 'VPS • gallery-dl';
    default:
      return origin === 'vps' ? `VPS • ${engine}` : engine;
  }
}

export function getEngineHealthLabel(health: EngineHealthState): {
  label: string;
  className: string;
} {
  switch (health) {
    case 'online':
      return {
        label: 'Online',
        className: 'bg-[#A3E635]/15 text-[#A3E635] border-[#A3E635]/30',
      };
    case 'degraded':
      return {
        label: 'Degradado',
        className: 'bg-amber-500/15 text-amber-300 border-amber-500/30',
      };
    case 'maintenance':
      return {
        label: 'Manutenção',
        className: 'bg-sky-500/15 text-sky-300 border-sky-500/30',
      };
    case 'offline':
    default:
      return {
        label: 'Offline',
        className: 'bg-red-500/15 text-red-300 border-red-500/30',
      };
  }
}

export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes) || bytes < 0) {
    return '';
  }
  if (bytes < 1024) return `${bytes} B`;
  const kb = bytes / 1024;
  if (kb < 1024) return `${kb.toFixed(1)} KB`;
  const mb = kb / 1024;
  if (mb < 1024) return `${mb.toFixed(1)} MB`;
  const gb = mb / 1024;
  return `${gb.toFixed(2)} GB`;
}

export function formatSpeed(bytesPerSec: number | null | undefined): string {
  if (!bytesPerSec || Number.isNaN(bytesPerSec) || bytesPerSec <= 0) {
    return '';
  }
  const formatted = formatBytes(bytesPerSec);
  return formatted ? `${formatted}/s` : '';
}

export function formatDuration(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || Number.isNaN(seconds) || seconds <= 0) {
    return '';
  }
  const total = Math.round(seconds);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  if (h > 0) {
    return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  }
  return `${m}:${String(s).padStart(2, '0')}`;
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  try {
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) return '—';
    return new Intl.DateTimeFormat('pt-BR', {
      day: '2-digit',
      month: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    }).format(date);
  } catch {
    return '—';
  }
}
