/*
 * Service worker de TiendaPOSS: la aplicación abre al instante y sigue abriendo sin internet.
 *  - La página (el "cascarón") se guarda y se usa cuando no hay red.
 *  - Los archivos con huella (/assets/...) se guardan para siempre: nunca cambian.
 *  - NUNCA toca /api: los datos y las ventas sin conexión los maneja la propia aplicación (IndexedDB).
 * (Adaptado del POS de Ecomerxio.)
 */
const VERSION = 'v2';
const SHELL_CACHE = `shell-${VERSION}`;
const ASSET_CACHE = 'assets';
const SHELL_KEY = '/__shell';
const STATIC = ['/manifest.webmanifest', '/icon.svg', '/icon-192.png', '/icon-512.png', '/icon-maskable-192.png', '/icon-maskable-512.png', '/apple-touch-icon.png'];
const MAX_ASSETS = 300;

async function refreshShell() {
  const cache = await caches.open(SHELL_CACHE);
  const response = await fetch('/index.html', { cache: 'no-store' });
  if (!response.ok) return;
  const html = await response.clone().text();
  await cache.put(SHELL_KEY, response);
  const assets = await caches.open(ASSET_CACHE);
  const urls = new Set(html.match(/\/assets\/[^"'\s)]+/g) || []);
  // Todas las pantallas (cargadas por partes) quedan guardadas, no solo la que se abrió.
  try {
    const list = await fetch('/precache.json', { cache: 'no-store' });
    if (list.ok) for (const f of (await list.json()).files || []) urls.add(f);
  } catch { /* sin lista: se guardan a medida que se usan */ }
  await Promise.all(Array.from(urls).map(async (url) => {
    if (await assets.match(url)) return;
    try { const r = await fetch(url); if (r.ok) await assets.put(url, r); } catch { /* se intentará luego */ }
  }));
}

self.addEventListener('install', (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open(SHELL_CACHE);
    await cache.addAll(STATIC).catch(() => undefined);
    await refreshShell().catch(() => undefined);
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    for (const key of await caches.keys()) {
      if (key.startsWith('shell-') && key !== SHELL_CACHE) await caches.delete(key);
    }
    await self.clients.claim();
  })());
});

async function trimAssets() {
  const cache = await caches.open(ASSET_CACHE);
  const keys = await cache.keys();
  if (keys.length > MAX_ASSETS) for (const k of keys.slice(0, keys.length - MAX_ASSETS)) await cache.delete(k);
}

async function cacheFirstAsset(request) {
  const cache = await caches.open(ASSET_CACHE);
  const hit = await cache.match(request);
  if (hit) return hit;
  const response = await fetch(request);
  if (response.ok) { cache.put(request, response.clone()); trimAssets(); }
  return response;
}

async function navigate(request) {
  try {
    const response = await fetch(request);
    refreshShell().catch(() => undefined);
    return response;
  } catch {
    const cached = await (await caches.open(SHELL_CACHE)).match(SHELL_KEY);
    if (cached) return cached;
    throw new Error('Sin conexión y sin copia guardada');
  }
}

async function staleWhileRevalidate(request) {
  const cache = await caches.open(SHELL_CACHE);
  const hit = await cache.match(request);
  const network = fetch(request).then((r) => { if (r.ok) cache.put(request, r.clone()); return r; }).catch(() => hit);
  return hit || network;
}

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname.startsWith('/api/')) return;
  if (request.mode === 'navigate') { event.respondWith(navigate(request)); return; }
  if (url.pathname.startsWith('/assets/')) { event.respondWith(cacheFirstAsset(request)); return; }
  if (STATIC.includes(url.pathname)) event.respondWith(staleWhileRevalidate(request));
});
