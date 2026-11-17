import type {
  EngineOrigin,
  ExecutionMode,
  FormatOption,
  JobResponse,
} from '../types/api';
import { formatDisplayEngine } from '../utils/format';
import { canUseLocalYtdlpForRequest, type ParsedPlatformUrl } from '../utils/url';

export interface ExecutionPlanOptions {
  preferredMode: ExecutionMode;
  isNativeAndroid: boolean;
  vpsAuthenticated: boolean;
  vpsConfigured: boolean;
  parsed: ParsedPlatformUrl;
  format: FormatOption;
}

export interface ExecutionPlan {
  order: EngineOrigin[];
  reason: string;
  requiresVpsLogin?: boolean;
}

const TERMINAL_ERROR_CODES = new Set([
  'AUTH_REQUIRED',
  'LOGIN_REQUIRED',
  'PRIVATE_MEDIA',
  'DRM_PROTECTED',
  'GEO_BLOCKED',
  'NOT_FOUND',
  'MEDIA_UNAVAILABLE',
  'UNSUPPORTED_URL',
  'ACCOUNT_SUSPENDED',
  'RATE_LIMITED_PLATFORM',
  'AGE_RESTRICTED',
]);

const TERMINAL_MESSAGE_PATTERNS = [
  /m[íi]dia privada/i,
  /private video|private account|login required|sign in to confirm/i,
  /requer login|autentica[çc][ãa]o necess[áa]ria/i,
  /drm/i,
  /removido|indispon[íi]vel|n[ãa]o encontrado|deleted|unavailable/i,
  /direitos autorais|copyright/i,
  /captcha/i,
];

export function isTerminalPlatformError(
  errorCode?: string | null,
  errorMessage?: string | null
): boolean {
  if (errorCode && TERMINAL_ERROR_CODES.has(errorCode.toUpperCase())) {
    return true;
  }
  if (errorMessage) {
    return TERMINAL_MESSAGE_PATTERNS.some((rx) => rx.test(errorMessage));
  }
  return false;
}

export function selectExecutionPlan(options: ExecutionPlanOptions): ExecutionPlan {
  const {
    preferredMode,
    isNativeAndroid,
    vpsAuthenticated,
    vpsConfigured,
    parsed,
    format,
  } = options;

  const localCapable = isNativeAndroid && canUseLocalYtdlpForRequest(parsed, format);
  const vpsUsable = vpsConfigured && vpsAuthenticated;

  if (preferredMode === 'local') {
    if (!isNativeAndroid) {
      return {
        order: [],
        reason: 'Modo Local exige o aplicativo Android nativo.',
      };
    }
    if (!canUseLocalYtdlpForRequest(parsed, format)) {
      return {
        order: [],
        reason: 'Esta plataforma ou formato de imagem requer o motor remoto da VPS.',
      };
    }
    return {
      order: ['local'],
      reason: 'Execução local forçada pelo usuário.',
    };
  }

  if (preferredMode === 'vps') {
    if (!vpsConfigured) {
      return {
        order: [],
        reason: 'Configure o endereço da VPS nos Ajustes para usar o motor remoto.',
      };
    }
    if (!vpsAuthenticated) {
      return {
        order: [],
        reason: 'Faça login na sua VPS para usar o motor remoto.',
        requiresVpsLogin: true,
      };
    }
    return {
      order: ['vps'],
      reason: 'Execução remota na VPS forçada pelo usuário.',
    };
  }

  // Modo Automático
  if (!isNativeAndroid) {
    if (!vpsAuthenticated) {
      return {
        order: [],
        reason: 'Autentique-se na VPS para iniciar o download.',
        requiresVpsLogin: true,
      };
    }
    return {
      order: ['vps'],
      reason: 'Web/PWA utiliza os motores da VPS.',
    };
  }

  // Android nativo em modo Automático
  if (parsed.prefersVpsFirst || !localCapable) {
    if (vpsUsable) {
      return {
        order: localCapable ? ['vps', 'local'] : ['vps'],
        reason: 'Plataforma/carrossel priorizado na VPS com fallback quando disponível.',
      };
    }
    if (localCapable) {
      return {
        order: ['local'],
        reason: 'VPS indisponível/desconectada; tentando extração local.',
      };
    }
    return {
      order: [],
      reason: vpsConfigured
        ? 'Conecte-se à VPS para baixar fotos/carrosséis ou Stories.'
        : 'Configure e conecte uma VPS nos Ajustes para baixar fotos/carrosséis.',
      requiresVpsLogin: vpsConfigured && !vpsAuthenticated,
    };
  }

  // Caso principal no Android: prioriza Motor Local e faz fallback para VPS em falha recuperável
  if (vpsUsable) {
    return {
      order: ['local', 'vps'],
      reason: 'Prioridade para motor local Android com fallback automático para VPS.',
    };
  }

  return {
    order: ['local'],
    reason: 'Execução offline no dispositivo via motor local Android.',
  };
}

export function decorateVpsJob(
  job: JobResponse,
  extra?: { statusMode?: boolean; segmentDuration?: number | null; keepOriginal?: boolean; fallbackNotice?: string | null; fromShareIntent?: boolean }
): JobResponse {
  const engine = job.current_engine || job.selected_engine;
  return {
    ...job,
    execution_origin: 'vps',
    display_engine: formatDisplayEngine(engine, 'vps'),
    status_mode: extra?.statusMode ?? job.status_mode,
    segment_duration: extra?.segmentDuration ?? job.segment_duration,
    keep_original: extra?.keepOriginal ?? job.keep_original,
    fallback_notice: extra?.fallbackNotice ?? job.fallback_notice,
    from_share_intent: extra?.fromShareIntent ?? job.from_share_intent,
  };
}
