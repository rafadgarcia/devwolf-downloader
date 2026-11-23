import type { EngineOrigin, FormatOption, PlatformId } from '../types/api';
import {
  getUserFacingErrorMessage,
  isTerminalPlatformError,
  normalizeErrorCode,
} from '../utils/errorContract';
import { canUseLocalEngineForRequest, type ParsedPlatformUrl } from '../utils/url';

export { getUserFacingErrorMessage, isTerminalPlatformError, normalizeErrorCode };

export interface PlatformEngineRoute {
  platform: PlatformId;
  label: string;
  primaryEngine: string;
  fallbackEngines: string[];
  routeDescription: string;
  localSupported: boolean;
}

export const PLATFORM_ROUTING_TABLE: PlatformEngineRoute[] = [
  {
    platform: 'youtube',
    label: 'YouTube',
    primaryEngine: 'yt-dlp',
    fallbackEngines: [],
    routeDescription: 'yt-dlp',
    localSupported: true,
  },
  {
    platform: 'tiktok',
    label: 'TikTok',
    primaryEngine: 'yt-dlp',
    fallbackEngines: [],
    routeDescription: 'yt-dlp',
    localSupported: true,
  },
  {
    platform: 'facebook',
    label: 'Facebook',
    primaryEngine: 'yt-dlp',
    fallbackEngines: [],
    routeDescription: 'yt-dlp',
    localSupported: true,
  },
  {
    platform: 'twitter',
    label: 'Twitter/X',
    primaryEngine: 'yt-dlp',
    fallbackEngines: ['gallery-dl'],
    routeDescription: 'yt-dlp',
    localSupported: true,
  },
  {
    platform: 'instagram',
    label: 'Instagram',
    primaryEngine: 'dnr',
    // Mantido em sincronia com EngineRouter.kt (Instagram -> DNR -> yt-dlp).
    fallbackEngines: ['yt-dlp'],
    routeDescription: 'DNR (fallback yt-dlp)',
    localSupported: true,
  },
  {
    platform: 'threads',
    label: 'Threads',
    primaryEngine: 'dnr',
    fallbackEngines: ['yt-dlp'],
    routeDescription: 'DNR (fallback yt-dlp)',
    localSupported: true,
  },
  {
    platform: 'pinterest',
    label: 'Pinterest',
    primaryEngine: 'gallery-dl',
    fallbackEngines: ['yt-dlp'],
    routeDescription: 'gallery-dl/nativo',
    localSupported: true,
  },
  {
    platform: 'reddit',
    label: 'Reddit',
    primaryEngine: 'gallery-dl',
    fallbackEngines: ['yt-dlp'],
    routeDescription: 'gallery-dl/yt-dlp',
    localSupported: true,
  },
];

export interface ExecutionPlanOptions {
  isNativeAndroid: boolean;
  parsed: ParsedPlatformUrl;
  format: FormatOption;
}

export interface ExecutionPlan {
  order: EngineOrigin[];
  primaryEngine: string;
  fallbackEngines: string[];
  reason: string;
  localSupported: boolean;
}

export function getPlatformEngineRoute(platform: PlatformId | string): PlatformEngineRoute {
  const found = PLATFORM_ROUTING_TABLE.find((entry) => entry.platform === platform);
  if (found) return found;
  // Plataforma desconhecida: o EngineRouter nativo rejeita com UNSUPPORTED_MEDIA.
  return {
    platform: String(platform) as PlatformId,
    label: String(platform),
    primaryEngine: 'yt-dlp',
    fallbackEngines: [],
    routeDescription: 'não suportada',
    localSupported: false,
  };
}

/**
 * Seleciona o plano de execução 100% local no dispositivo (zero dependência de VPS).
 */
export function selectExecutionPlan(options: ExecutionPlanOptions): ExecutionPlan {
  const { isNativeAndroid, parsed, format } = options;
  const route = getPlatformEngineRoute(parsed.platform);
  const localCapable = canUseLocalEngineForRequest(parsed, format);

  if (!isNativeAndroid) {
    return {
      order: [],
      primaryEngine: route.primaryEngine,
      fallbackEngines: route.fallbackEngines,
      localSupported: localCapable,
      reason: 'Execução local requer o aplicativo Android do Devwolf Downloader.',
    };
  }

  return {
    order: localCapable ? ['local'] : [],
    primaryEngine: route.primaryEngine,
    fallbackEngines: route.fallbackEngines,
    localSupported: localCapable,
    reason: `Execução 100% local via ${route.routeDescription}.`,
  };
}
