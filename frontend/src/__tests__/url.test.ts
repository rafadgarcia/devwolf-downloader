import { describe, expect, it } from 'vitest';
import {
  canUseLocalYtdlpForRequest,
  extractSupportedUrlsFromText,
  normalizeUrl,
  parseAndValidateUrl,
} from '../utils/url';

describe('parseAndValidateUrl & normalizeUrl', () => {
  it('detecta e normaliza Reel do Instagram removendo tracking params', () => {
    const parsed = parseAndValidateUrl(
      'https://www.instagram.com/reel/ABC123xyz/?igsh=secret123&utm_source=ig_web_copy_link'
    );
    expect(parsed).not.toBeNull();
    expect(parsed?.platform).toBe('instagram');
    expect(parsed?.routeLabel).toBe('Instagram Reel');
    expect(parsed?.normalizedUrl).toBe('https://www.instagram.com/reel/ABC123xyz');
  });

  it('preserva parâmetro essencial v e t no YouTube removendo si', () => {
    const normalized = normalizeUrl(
      'https://www.youtube.com/watch?v=dQw4w9WgXcQ&si=tracking123&t=42'
    );
    expect(normalized).toBe('https://www.youtube.com/watch?t=42&v=dQw4w9WgXcQ');
  });

  it('aceita link curto youtu.be e detecta Shorts', () => {
    const short = parseAndValidateUrl('https://youtu.be/dQw4w9WgXcQ?si=abc');
    expect(short?.platform).toBe('youtube');
    expect(short?.normalizedUrl).toBe('https://youtu.be/dQw4w9WgXcQ');

    const shorts = parseAndValidateUrl('https://youtube.com/shorts/xyz123');
    expect(shorts?.routeLabel).toBe('YouTube Shorts');
  });

  it('aceita domínio conhecido sem https:// convertendo localmente', () => {
    const parsed = parseAndValidateUrl('instagram.com/reel/Teste123/?utm_medium=share');
    expect(parsed?.platform).toBe('instagram');
    expect(parsed?.normalizedUrl).toBe('https://instagram.com/reel/Teste123');
  });

  it('detecta TikTok, X/Twitter, Reddit, Pinterest e Facebook (/share/r/, /share/v/, fb.watch)', () => {
    expect(parseAndValidateUrl('https://vm.tiktok.com/ZM12345/')?.platform).toBe('tiktok');
    expect(parseAndValidateUrl('https://x.com/user/status/123456?s=20')?.platform).toBe('twitter');
    expect(parseAndValidateUrl('https://redd.it/abc123')?.platform).toBe('reddit');
    expect(parseAndValidateUrl('https://pin.it/abc123')?.platform).toBe('pinterest');

    const fbWatch = parseAndValidateUrl('https://fb.watch/abc123/');
    expect(fbWatch?.platform).toBe('facebook');
    expect(fbWatch?.routeLabel).toBe('Facebook Vídeo');

    const fbShareReel = parseAndValidateUrl('https://www.facebook.com/share/r/1AbCdEf/?mibextid=wwXIfr');
    expect(fbShareReel?.platform).toBe('facebook');
    expect(fbShareReel?.routeLabel).toBe('Facebook Reel');
    expect(fbShareReel?.normalizedUrl).toBe('https://www.facebook.com/share/r/1AbCdEf?mibextid=wwXIfr');

    const fbShareVideo = parseAndValidateUrl('https://www.facebook.com/share/v/9XyZ123/');
    expect(fbShareVideo?.platform).toBe('facebook');
    expect(fbShareVideo?.routeLabel).toBe('Facebook Vídeo');
  });

  it('indica compatibilidade com motor local yt-dlp corretamente', () => {
    const reel = parseAndValidateUrl('https://www.instagram.com/reel/ABC123/')!;
    expect(canUseLocalYtdlpForRequest(reel, 'auto')).toBe(true);
    expect(canUseLocalYtdlpForRequest(reel, 'image')).toBe(false);

    const pin = parseAndValidateUrl('https://pin.it/abc123')!;
    expect(canUseLocalYtdlpForRequest(pin, 'auto')).toBe(false);
    expect(pin.prefersVpsFirst).toBe(true);
  });

  it('rejeita domínios impostores, credenciais embutidas, IPs e rotas proibidas', () => {
    expect(parseAndValidateUrl('https://instagram.com.evil.com/reel/123')).toBeNull();
    expect(parseAndValidateUrl('https://user:pass@instagram.com/reel/123')).toBeNull();
    expect(parseAndValidateUrl('http://127.0.0.1:8000/reel/123')).toBeNull();
    expect(parseAndValidateUrl('http://169.254.169.254/latest/meta-data')).toBeNull();
    expect(parseAndValidateUrl('https://youtube.com/playlist?list=PL123')).toBeNull();
    expect(parseAndValidateUrl('https://youtube.com/@canal')).toBeNull();
    expect(parseAndValidateUrl('https://example.com/video.mp4')).toBeNull();
    expect(parseAndValidateUrl('javascript:alert(1)')).toBeNull();
  });
});

describe('extractSupportedUrlsFromText', () => {
  it('extrai link válido de texto compartilhado no Android ignorando texto comum', () => {
    const text = 'Olha esse vídeo incrível! https://www.tiktok.com/@autor/video/123456789?utm_campaign=app';
    const found = extractSupportedUrlsFromText(text);
    expect(found).toHaveLength(1);
    expect(found[0].platform).toBe('tiktok');
    expect(found[0].normalizedUrl).toBe('https://www.tiktok.com/@autor/video/123456789');
  });

  it('extrai múltiplos links válidos distintos para seleção do usuário', () => {
    const text = `
      Primeiro: https://www.instagram.com/reel/AAA111/?igsh=1
      Segundo: https://youtu.be/BBB222?si=2
      Duplicado normalizado: https://www.instagram.com/reel/AAA111/
      Ignorado: https://site-desconhecido.org/algo
    `;
    const found = extractSupportedUrlsFromText(text);
    expect(found).toHaveLength(2);
    expect(found.map((f) => f.platform)).toEqual(['instagram', 'youtube']);
  });

  it('retorna lista vazia para texto comum ou clipboard vazio', () => {
    expect(extractSupportedUrlsFromText('')).toEqual([]);
    expect(extractSupportedUrlsFromText('Apenas uma anotação comum sem links')).toEqual([]);
  });
});
