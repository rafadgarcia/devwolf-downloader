import { describe, expect, it } from 'vitest';
import type { DevwolfErrorCode } from '../types/api';
import {
  DEVWOLF_ERROR_CONTRACT,
  getErrorContract,
  getUserFacingErrorMessage,
  isTerminalPlatformError,
  normalizeErrorCode,
  sanitizeDiagnosticText,
} from '../utils/errorContract';

const ALL_11_CODES: DevwolfErrorCode[] = [
  'AUTH_REQUIRED',
  'PRIVATE_MEDIA',
  'RATE_LIMITED',
  'CAPTCHA_REQUIRED',
  'MEDIA_UNAVAILABLE',
  'UNSUPPORTED_MEDIA',
  'CANCELLED',
  'STORAGE_ERROR',
  'EXTRACTOR_OUTDATED',
  'EXTRACTION_FAILED',
  'NETWORK_ERROR',
];

describe('Structured Error Contract (DevwolfErrorCode)', () => {
  it('cobre todos os 11 códigos estruturados com mensagens pt-BR claras', () => {
    expect(Object.keys(DEVWOLF_ERROR_CONTRACT)).toHaveLength(11);
    for (const code of ALL_11_CODES) {
      expect(normalizeErrorCode(code)).toBe(code);
      const msg = getUserFacingErrorMessage(code, null);
      expect(msg).toBe(DEVWOLF_ERROR_CONTRACT[code].message);
      expect(msg.length).toBeGreaterThan(10);
      expect(msg.toLowerCase()).not.toContain('vps');
    }
  });

  it('classifica corretamente códigos terminais vs recuperáveis', () => {
    const terminalCodes: DevwolfErrorCode[] = [
      'AUTH_REQUIRED',
      'PRIVATE_MEDIA',
      'RATE_LIMITED',
      'CAPTCHA_REQUIRED',
      'MEDIA_UNAVAILABLE',
      'UNSUPPORTED_MEDIA',
      'CANCELLED',
      'STORAGE_ERROR',
    ];
    for (const code of terminalCodes) {
      expect(isTerminalPlatformError(code, null)).toBe(true);
    }

    const recoverableCodes: DevwolfErrorCode[] = [
      'EXTRACTOR_OUTDATED',
      'EXTRACTION_FAILED',
      'NETWORK_ERROR',
    ];
    for (const code of recoverableCodes) {
      expect(isTerminalPlatformError(code, null)).toBe(false);
    }
  });

  it('suporta códigos legados (TERMINAL_AUTH, TERMINAL_RATE_LIMIT) mantendo compatibilidade', () => {
    expect(normalizeErrorCode('TERMINAL_AUTH')).toBe('AUTH_REQUIRED');
    expect(normalizeErrorCode('TERMINAL_RATE_LIMIT')).toBe('RATE_LIMITED');
    expect(isTerminalPlatformError('TERMINAL_AUTH', null)).toBe(true);
    expect(isTerminalPlatformError('TERMINAL_RATE_LIMIT', null)).toBe(true);
  });

  it('nunca classifica falha genérica de extração/parser como PRIVATE_MEDIA', () => {
    expect(
      isTerminalPlatformError(
        'EXTRACTION_FAILED',
        'Failed to parse embedded JSON from Instagram page'
      )
    ).toBe(false);
    expect(
      getUserFacingErrorMessage('EXTRACTION_FAILED', 'Parser error')
    ).toBe('Não foi possível extrair a mídia desta página.');
    expect(
      isTerminalPlatformError(null, 'Unable to extract video_url from HTML script')
    ).toBe(false);
  });

  it('verifica mensagens exatas especificadas na seção 4.2', () => {
    expect(getUserFacingErrorMessage('AUTH_REQUIRED')).toBe(
      'Esta publicação exige login no Instagram. Você pode conectar uma sessão opcional nos Ajustes.'
    );
    expect(getUserFacingErrorMessage('PRIVATE_MEDIA')).toBe(
      'Conteúdo privado ou restrito pelo autor.'
    );
    expect(getUserFacingErrorMessage('RATE_LIMITED')).toBe(
      'Limite temporário de requisições da plataforma (Rate Limit). Aguarde alguns minutos.'
    );
    expect(getUserFacingErrorMessage('CAPTCHA_REQUIRED')).toBe(
      'A plataforma solicitou verificação anti-bot (CAPTCHA).'
    );
    expect(getUserFacingErrorMessage('MEDIA_UNAVAILABLE')).toBe(
      'Publicação removida, indisponível ou link expirado.'
    );
    expect(getUserFacingErrorMessage('UNSUPPORTED_MEDIA')).toBe(
      'Formato ou tipo de mídia não suportado.'
    );
    expect(getUserFacingErrorMessage('CANCELLED')).toBe('Download cancelado pelo usuário.');
    expect(getUserFacingErrorMessage('STORAGE_ERROR')).toBe(
      'Erro ao salvar arquivo no armazenamento/galeria do dispositivo.'
    );
    expect(getUserFacingErrorMessage('EXTRACTOR_OUTDATED')).toBe(
      'Motor de extração desatualizado. Atualize o motor na aba Motores.'
    );
    expect(getUserFacingErrorMessage('EXTRACTION_FAILED')).toBe(
      'Não foi possível extrair a mídia desta página.'
    );
    expect(getUserFacingErrorMessage('NETWORK_ERROR')).toBe(
      'Falha de conexão de rede ao baixar a mídia.'
    );
  });

  it('exibe mensagem AUTH_REQUIRED específica por plataforma (nunca Instagram para Reddit/Threads)', () => {
    const redditMsg = getErrorContract('AUTH_REQUIRED', null, 'reddit').message;
    expect(redditMsg).toContain('Reddit');
    expect(redditMsg.toLowerCase()).not.toContain('instagram');

    const threadsMsg = getErrorContract('AUTH_REQUIRED', null, 'threads').message;
    expect(threadsMsg).toContain('Threads');
    expect(threadsMsg.toLowerCase()).not.toContain('instagram');

    const igMsg = getErrorContract('AUTH_REQUIRED', null, 'instagram').message;
    expect(igMsg).toContain('Instagram');

    const genericMsg = getErrorContract('AUTH_REQUIRED', null, 'tiktok').message;
    expect(genericMsg.toLowerCase()).not.toContain('instagram');
  });

  it('sanitiza cookies, tokens e URLs assinadas de logs de diagnóstico', () => {
    const raw =
      'Cookie: sessionid=abc123secret; csrftoken=xyz999; reddit_session=rdsec Authorization: Bearer tok_12345 https://cdn.example.com/v.mp4?signature=deadbeef&access_token=ac123&oe=1234';
    const clean = sanitizeDiagnosticText(raw);
    expect(clean).not.toContain('abc123secret');
    expect(clean).not.toContain('xyz999');
    expect(clean).not.toContain('rdsec');
    expect(clean).not.toContain('tok_12345');
    expect(clean).not.toContain('deadbeef');
    expect(clean).not.toContain('ac123');
    expect(clean).toContain('[REDACTED]');
  });
});
