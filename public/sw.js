const CACHE = "mugen-shell-dev";
const BASE = new URL("./", self.location.href).pathname;
const BUILD_ASSETS = [];
const SHELL = [BASE, ...BUILD_ASSETS.map((asset) => BASE + asset)];

self.addEventListener("install", (event) => {
  // A failed install must leave the previous complete offline shell intact.
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE);
    try {
      await cache.addAll([...new Set(SHELL)].map((url) => new Request(url, { cache: "reload" })));
    } catch (error) {
      await caches.delete(CACHE);
      throw error;
    }
  })());
});
self.addEventListener("message", (event) => {
  if (event.data?.type === "SKIP_WAITING") event.waitUntil(self.skipWaiting());
});
self.addEventListener("activate", (event) => {
  event.waitUntil((async () => {
    await Promise.all((await caches.keys()).filter((key) => key.startsWith("mugen-shell-") && key !== CACHE).map((key) => caches.delete(key)));
    await self.clients.claim();
  })());
});
self.addEventListener("fetch", (event) => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== "GET" || request.headers.has("range") || url.origin !== self.location.origin || !url.pathname.startsWith(BASE)) return;
  const navigation = request.mode === "navigate";
  const asset = ["script", "style", "font", "image", "manifest", "worker"].includes(request.destination) || url.pathname.endsWith(".wasm");
  if (!navigation && !asset) return;
  event.respondWith((async () => {
    const cache = await caches.open(CACHE);
    // HTML and hashed assets must come from the same build, including online.
    const cached = await cache.match(navigation ? BASE : request);
    if (cached) return cached;
    return fetch(request);
  })());
});
