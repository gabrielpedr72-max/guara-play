// Mantém o player abrindo mesmo sem internet.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', e => e.waitUntil(clients.claim()));
self.addEventListener('fetch', e => {
  const u = new URL(e.request.url);
  if (e.request.method !== 'GET' || u.pathname !== '/player.html') return;
  e.respondWith(fetch(e.request).then(r => { const c = r.clone(); caches.open('shell').then(x => x.put('/player.html', c)); return r; })
    .catch(() => caches.match('/player.html')));
});
