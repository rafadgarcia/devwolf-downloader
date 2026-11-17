import type { DevwolfErrorCode } from '../types/api';

export interface ErrorContractEntry {
  code: DevwolfErrorCode;
  message: string;
  terminal: boolean;
}

export const DEVWOLF_ERROR_CONTRACT: Record<DevwolfErrorCode, ErrorContractEntry> = {
  AUTH_REQUIRED: {
    code: 'AUTH_REQUIRED',
    message:
      'Esta publicação exige login no Instagram. Você pode conectar uma sessão opcional nos Ajustes.',
    terminal: true,
  },
  PRIVATE_MEDIA: {
    code: 'PRIVATE_MEDIA',
    message: 'Conteúdo privado ou restrito pelo autor.',
    terminal: true,
  },
  RATE_LIMITED: {
    code: 'RATE_LIMITED',
    message: 'Limite temporário de requisições da plataforma (Rate Limit). Aguarde alguns minutos.',
    terminal: true,
  },
  CAPTCHA_REQUIRED: {
    code: 'CAPTCHA_REQUIRED',
    message: 'A plataforma solicitou verificação anti-bot (CAPTCHA).',
    terminal: true,
  },
  MEDIA_UNAVAILABLE: {
    code: 'MEDIA_UNAVAILABLE',
    message: 'Publicação removida, indisponível ou link expirado.',
    terminal: true,
  },
  UNSUPPORTED_MEDIA: {
    code: 'UNSUPPORTED_MEDIA',
    message: 'Formato ou tipo de mídia não suportado.',
    terminal: true,
  },
  CANCELLED: {
    code: 'CANCELLED',
    message: 'Download cancelado pelo usuário.',
    terminal: true,
  },
  STORAGE_ERROR: {
    code: 'STORAGE_ERROR',
    message: 'Erro ao salvar arquivo no armazenamento/galeria do dispositivo.',
    terminal: true,
  },
  EXTRACTOR_OUTDATED: {
    code: 'EXTRACTOR_OUTDATED',
    message: 'Motor de extração desatualizado. Atualize o motor na aba Motores.',
    terminal: false,
  },
  EXTRACTION_FAILED: {
    code: 'EXTRACTION_FAILED',
    message: 'Não foi possível extrair a mídia desta página.',
    terminal: false,
  },
  NETWORK_ERROR: {
    code: 'NETWORK_ERROR',
    message: 'Falha de conexão de rede ao baixar a mídia.',
    terminal: false,
  },
};

const LEGACY_CODE_MAP: Record<string, DevwolfErrorCode> = {
  TERMINAL_AUTH: 'AUTH_REQUIRED',
  LOGIN_REQUIRED: 'AUTH_REQUIRED',
  AGE_RESTRICTED: 'AUTH_REQUIRED',
  TERMINAL_RATE_LIMIT: 'RATE_LIMITED',
  RATE_LIMITED_PLATFORM: 'RATE_LIMITED',
  HTTP_429: 'RATE_LIMITED',
  NOT_FOUND: 'MEDIA_UNAVAILABLE',
  EXPIRED_URL: 'MEDIA_UNAVAILABLE',
  GEO_BLOCKED: 'MEDIA_UNAVAILABLE',
  ACCOUNT_SUSPENDED: 'MEDIA_UNAVAILABLE',
  UNSUPPORTED_URL: 'UNSUPPORTED_MEDIA',
  DRM_PROTECTED: 'UNSUPPORTED_MEDIA',
  NETWORK_TIMEOUT: 'NETWORK_ERROR',
};

const LEGACY_TERMINAL_CODES = new Set([
  'TERMINAL_AUTH',
  'TERMINAL_RATE_LIMIT',
  'LOGIN_REQUIRED',
  'DRM_PROTECTED',
  'GEO_BLOCKED',
  'NOT_FOUND',
  'UNSUPPORTED_URL',
  'ACCOUNT_SUSPENDED',
  'RATE_LIMITED_PLATFORM',
  'AGE_RESTRICTED',
]);

export function normalizeErrorCode(rawCode?: string | null): DevwolfErrorCode | null {
  if (!rawCode) return null;
  const upper = rawCode.trim().toUpperCase();
  if (upper in DEVWOLF_ERROR_CONTRACT) {
    return upper as DevwolfErrorCode;
  }
  if (upper in LEGACY_CODE_MAP) {
    return LEGACY_CODE_MAP[upper];
  }
  return null;
}

/**
 * Avalia se uma falha é terminal com base primária no código estruturado (`DevwolfErrorCode`).
 * Códigos recuperáveis explícitos (`EXTRACTOR_OUTDATED`, `EXTRACTION_FAILED`, `NETWORK_ERROR`)
 * retornam `false` para permitir fallback entre motores locais quando disponível.
 */
export function isTerminalPlatformError(
  errorCode?: string | null,
  errorMessage?: string | null
): boolean {
  if (errorCode) {
    const upper = errorCode.trim().toUpperCase();
    const structured = normalizeErrorCode(upper);
    if (structured) {
      return DEVWOLF_ERROR_CONTRACT[structured].terminal;
    }
    if (LEGACY_TERMINAL_CODES.has(upper)) {
      return true;
    }
  }

  if (!errorMessage) {
    return false;
  }

  // Fallback conservador apenas quando nenhum código estruturado foi fornecido:
  // nunca classificar erro genérico de extração/parser como mídia privada.
  const msg = errorMessage.trim();
  if (/captcha/i.test(msg)) return true;
  if (/rate[\s_-]?limit|too many requests|429/i.test(msg)) return true;
  if (/sign in to confirm|login required|exige login|autentica[çc][ãa]o necess[áa]ria/i.test(msg)) {
    return true;
  }
  if (/conte[úu]do privado|private account|private video|m[íi]dia privada|restrito pelo autor/i.test(msg)) {
    return true;
  }
  if (/drm|copyright|direitos autorais/i.test(msg)) return true;
  if (/removido|link expirado|publica[çc][ãa]o removida/i.test(msg)) return true;
  if (/cancelado pelo usu[áa]rio/i.test(msg)) return true;

  return false;
}

export function getUserFacingErrorMessage(
  errorCode?: string | null,
  fallbackMessage?: string | null
): string {
  const normalized = normalizeErrorCode(errorCode);
  if (normalized) {
    return DEVWOLF_ERROR_CONTRACT[normalized].message;
  }
  if (fallbackMessage && fallbackMessage.trim()) {
    return fallbackMessage.trim();
  }
  return DEVWOLF_ERROR_CONTRACT.EXTRACTION_FAILED.message;
}

export function sanitizeDiagnosticText(raw: string): string {
  if (!raw) return '';
  return raw
    .replace(/(sessionid|csrftoken|ds_user_id|rur|mid|ig_did|shbid|shbts)=([^;\s&"']+)/gi, '$1=[REDACTED]')
    .replace(/(cookie|authorization|x-csrftoken)\s*:\s*([^\r\n]+)/gi, '$1: [REDACTED]')
    .replace(/Bearer\s+[A-Za-z0-9._~+/=-]+/gi, 'Bearer [REDACTED]')
    .replace(/([?&](?:signature|sig|token|auth|oe|oh|policy|key-pair-id|x-amz-[^=]+))=([^&\s]+)/gi, '$1=[REDACTED]');
}
