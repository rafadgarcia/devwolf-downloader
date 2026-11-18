import { describe, expect, it } from 'vitest';
import {
  canUseLocalEngineForRequest,
  canUseLocalYtdlpForRequest,
  extractSupportedUrlsFromText,
  normalizeUrl,
  parseAndValidateUrl,
} from '../utils/url';

describe('parseAndValidateUrl & normalizeUrl (100% Local)', () => {
  it('detecta e normaliza Reel e Post/Carrossel do Instagram removendo tracking params', () => {
    const reel = parseAndValidateUrl(
      'https://www.instagram.com/reel/ABC123xyz/?igsh=secret123&utm_source=ig_web_copy_link'
    );
    expect(reel).not.toBeNull();
    expect(reel?.platform).toBe('instagram');
    expect(reel?.routeLabel).toBe('Instagram Reel');
    expect(reel?.localSupported).toBe(true);
    expect(reel?.normalizedUrl).toBe('https://www.instagram.com/reel/ABC123xyz');

    const post = parseAndValidateUrl('https://www.instagram.com/p/POST999/?igsh=xyz');
    expect(post).not.toBeNull();
    expect(post?.platform).toBe('instagram');
    expect(post?.routeLabel).toBe('Instagram Post / Carrossel');
    expect(post?.localSupported).toBe(true);
    expect(post?.normalizedUrl).toBe('https://www.instagram.com/p/POST999');
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
    expect(short?.localSupported).toBe(true);
    expect(short?.normalizedUrl).toBe('https://youtu.be/dQw4w9WgXcQ');

    const shorts = parseAndValidateUrl('https://youtube.com/shorts/xyz123');
    expect(shorts?.routeLabel).toBe('YouTube Shorts');
    expect(shorts?.localSupported).toBe(true);
  });

  it('aceita domínio conhecido sem https:// convertendo localmente', () => {
    const parsed = parseAndValidateUrl('instagram.com/reel/Teste123/?utm_medium=share');
    expect(parsed?.platform).toBe('instagram');
    expect(parsed?.localSupported).toBe(true);
    expect(parsed?.normalizedUrl).toBe('https://instagram.com/reel/Teste123');
  });

  it('detecta TikTok, X/Twitter, Reddit, Pinterest e Facebook com suporte 100% local', () => {
    const tiktok = parseAndValidateUrl('https://vm.tiktok.com/ZM12345/');
    expect(tiktok?.platform).toBe('tiktok');
    expect(tiktok?.localSupported).toBe(true);

    const twitter = parseAndValidateUrl('https://x.com/user/status/123456?s=20');
    expect(twitter?.platform).toBe('twitter');
    expect(twitter?.localSupported).toBe(true);

    const reddit = parseAndValidateUrl('https://redd.it/abc123');
    expect(reddit?.platform).toBe('reddit');
    expect(reddit?.localSupported).toBe(true);

    const pinterest = parseAndValidateUrl('https://pin.it/abc123');
    expect(pinterest?.platform).toBe('pinterest');
    expect(pinterest?.localSupported).toBe(true);

    const fbWatch = parseAndValidateUrl('https://fb.watch/abc123/');
    expect(fbWatch?.platform).toBe('facebook');
    expect(fbWatch?.routeLabel).toBe('Facebook Vídeo');
    expect(fbWatch?.localSupported).toBe(true);

    const fbShareReel = parseAndValidateUrl('https://www.facebook.com/share/r/1AbCdEf/?mibextid=wwXIfr');
    expect(fbShareReel?.platform).toBe('facebook');
    expect(fbShareReel?.routeLabel).toBe('Facebook Reel');
    expect(fbShareReel?.localSupported).toBe(true);

    const fbShareVideo = parseAndValidateUrl('https://www.facebook.com/share/v/9XyZ123/');
    expect(fbShareVideo?.platform).toBe('facebook');
    expect(fbShareVideo?.routeLabel).toBe('Facebook Vídeo');
    expect(fbShareVideo?.localSupported).toBe(true);

    const threadsNet = parseAndValidateUrl(
      'https://www.threads.net/@zuck/post/C12345abc/?xmt=AQGz123&slof=1'
    );
    expect(threadsNet?.platform).toBe('threads');
    expect(threadsNet?.routeLabel).toBe('Threads');
    expect(threadsNet?.localSupported).toBe(true);
    expect(threadsNet?.normalizedUrl).toBe('https://www.threads.net/@zuck/post/C12345abc');

    const threadsCom = parseAndValidateUrl('https://www.threads.com/@user/post/XYZ987?utm_source=ig');
    expect(threadsCom?.platform).toBe('threads');
    expect(threadsCom?.routeLabel).toBe('Threads');
    expect(threadsCom?.normalizedUrl).toBe('https://www.threads.com/@user/post/XYZ987');
  });

  it('garante localSupported === true para todas as plataformas e formatos sem VPS', () => {
    const urls = [
      'https://www.instagram.com/reel/ABC123/',
      'https://www.instagram.com/p/CAROUSEL1/',
      'https://www.threads.net/@user/post/TH123/',
      'https://www.threads.com/@user/post/TH456/',
      'https://pin.it/abc123',
      'https://www.pinterest.com/pin/123456789/',
      'https://www.reddit.com/r/pics/comments/abc123/foto/',
      'https://x.com/user/status/999',
      'https://vm.tiktok.com/ZM123/',
      'https://youtu.be/dQw4w9WgXcQ',
      'https://www.facebook.com/reel/12345',
    ];

    for (const raw of urls) {
      const parsed = parseAndValidateUrl(raw)!;
      expect(parsed).not.toBeNull();
      expect(parsed.localSupported).toBe(true);
      expect(canUseLocalEngineForRequest(parsed, 'auto')).toBe(true);
      expect(canUseLocalEngineForRequest(parsed, 'video')).toBe(true);
      expect(canUseLocalEngineForRequest(parsed, 'image')).toBe(true);
      expect(canUseLocalEngineForRequest(parsed, 'audio')).toBe(true);
      expect(canUseLocalYtdlpForRequest(parsed, 'image')).toBe(true);
    }
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
