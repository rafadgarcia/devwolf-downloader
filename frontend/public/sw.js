/* DropLink Service Worker
 * Regras de segurança e privacidade:
 * - Cacheia apenas assets estáticos do app shell (nunca /api/*, histórico ou arquivos de mídia).
 * - Intercepta POST /share-target em cliente para preservar o link compartilhado localmente
 *   sem vazar URLs sensíveis em query strings ou logs do servidor, redirecionando para /?shared=1.
 */

const SHELL_CACHE = 'droplink-shell-v1';
const SHARE_CACHE = 'droplink-share-pending-v1';
const SHARE_KEY = '/__droplink_pending_share__';

const SHELL_ASSETS = [
  '/',
  '/index.html',
  '/manifest.webmanifest',
  '/icons/icon.svg',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(SHELL_CACHE).then((cache) => cache.addAll(SHELL_ASSETS).catch(() => undefined))
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(
        keys
          .filter((key) => key !== SHELL_CACHE && key !== SHARE_CACHE)
          .map((key) => caches.delete(key))
      )
    )
  );
  self.clients.claim();
});

async function handleShareTargetPost(request) {
  try {
    const formData = await request.formData();
    const payload = {
      title: String(formData.get('title') || ''),
      text: String(formData.get('text') || ''),
      url: String(formData.get('url') || ''),
      receivedAt: Date.now(),
    };
    const cache = await caches.open(SHARE_CACHE);
    await cache.put(
      SHARE_KEY,
      new Response(JSON.stringify(payload), {
        headers: { 'Content-Type': 'application/json' },
      })
    );
  } catch {
    // Fallback silencioso: redireciona para o app mesmo se o payload estiver vazio
  }
  return Response.redirect('/?shared=1', 303);
}

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);

  if (url.origin !== self.location.origin) {
    return;
  }

  // Intercepta Web Share Target POST localmente sem enviar ao servidor nem expor na query string
  if (url.pathname === '/share-target' && event.request.method === 'POST') {
    event.respondWith(handleShareTargetPost(event.request));
    return;
  }

  // Nunca cacheia API, share-target, health checks ou rotas mutáveis
  if (
    event.request.method !== 'GET' ||
    url.pathname.startsWith('/api/') ||
    url.pathname.startsWith('/share-target') ||
    url.pathname === '/healthz' ||
    url.pathname === '/readyz'
  ) {
    return;
  }

  // Navegação SPA: network-first com fallback para app shell em cache
  if (event.request.mode === 'navigate') {
    event.respondWith(
      fetch(event.request).catch(async () => {
        const cache = await caches.open(SHELL_CACHE);
        return (await cache.match('/index.html')) || (await cache.match('/')) || Response.error();
      })
    );
    return;
  }

  // Assets estáticos: cache-first com revalidação em segundo plano
  event.respondWith(
    caches.open(SHELL_CACHE).then(async (cache) => {
      const cached = await cache.match(event.request);
      const networkFetch = fetch(event.request)
        .then((response) => {
          if (response && response.status === 200 && response.type === 'basic') {
            cache.put(event.request, response.clone()).catch(() => undefined);
          }
          return response;
        })
        .catch(() => undefined);
      return cached || (await networkFetch) || Response.error();
    })
  );
});
