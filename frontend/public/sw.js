/*
 * Podium's service worker.
 *
 * Deliberately conservative. The app is an SPA with hash routing and a real API:
 *
 *  * navigations are network-first, so a deploy is picked up on the next visit
 *    instead of serving a stale index.html that points at deleted asset files;
 *  * /api/ is never touched -- cached attendance, sessions or messages would be
 *    worse than an honest error;
 *  * hashed build output under /assets/ is immutable and answered from cache;
 *  * anything else is network-first with a cached copy as the fallback.
 *
 * The cached shell exists so the app opens (rather than showing the browser's
 * offline page) when the connection drops after it has been used once.
 */

const CACHE = 'podium-shell-v1';

const SHELL = [
  '/',
  '/index.html',
  '/manifest.json',
  '/favicon.svg',
  '/icon-192x192.png',
  '/icon-512x512.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    (async () => {
      const cache = await caches.open(CACHE);
      // addAll() fails the whole install if one URL 404s, which would leave the
      // app with no service worker at all over a missing icon.
      await Promise.all(
        SHELL.map((url) =>
          cache.add(new Request(url, { cache: 'reload' })).catch(() => undefined)
        )
      );
      await self.skipWaiting();
    })()
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      const keys = await caches.keys();
      await Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key)));
      await self.clients.claim();
    })()
  );
});

function isAsset(pathname) {
  return (
    pathname.startsWith('/assets/') ||
    /\.(?:png|jpe?g|webp|gif|svg|ico|woff2?|ttf)$/i.test(pathname)
  );
}

async function networkFirst(request, fallbackShell) {
  const cache = await caches.open(CACHE);
  try {
    const response = await fetch(request);
    if (response && response.ok) cache.put(request, response.clone());
    return response;
  } catch (error) {
    const cached = await cache.match(request);
    if (cached) return cached;
    if (fallbackShell) {
      const shell = await cache.match('/index.html') || (await cache.match('/'));
      if (shell) return shell;
    }
    throw error;
  }
}

async function cacheFirst(request) {
  const cache = await caches.open(CACHE);
  const cached = await cache.match(request);
  if (cached) return cached;
  const response = await fetch(request);
  if (response && response.ok) cache.put(request, response.clone());
  return response;
}

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;

  let url;
  try {
    url = new URL(request.url);
  } catch {
    return;
  }

  // Cross-origin requests (LiveKit signalling, Google Fonts) are left alone:
  // they are not this origin's to cache, and a stale media response is useless.
  if (url.origin !== self.location.origin) return;

  // Never serve API reads from cache -- a stale class list or attendance record
  // is worse than a failed request.
  if (url.pathname.startsWith('/api/')) return;

  if (request.mode === 'navigate') {
    event.respondWith(networkFirst(request, true));
    return;
  }

  if (isAsset(url.pathname)) {
    event.respondWith(cacheFirst(request));
    return;
  }

  event.respondWith(networkFirst(request, false));
});
