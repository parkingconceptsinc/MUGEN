import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
const source = readFileSync(new URL('../public/sw.js', import.meta.url), 'utf8');
function harness({ failInstall = false } = {}) {
  const listeners = {};
  const entries = new Map([['/MUGEN/', new Response('complete shell')], ['https://app.test/MUGEN/assets/app.js', new Response('bundle')]]);
  const deleted = [];
  let network = 0;
  let skipped = false;
  const cache = { addAll: async () => { if (failInstall) throw Error('offline'); }, match: async (r) => entries.get(typeof r === 'string' ? r : r.url) };
  const self = { location: { href: 'https://app.test/MUGEN/sw.js', origin: 'https://app.test' }, addEventListener: (type, fn) => listeners[type] = fn, clients: { claim: async () => {} }, skipWaiting: async () => { skipped = true; } };
  runInNewContext(source, { self, caches: { open: async () => cache, keys: async () => ['unrelated', 'mugen-shell-old', 'mugen-shell-dev'], delete: async (k) => { deleted.push(k); } }, URL, Request: class { constructor(url) { this.url = new URL(url, self.location.href).href; } }, fetch: async () => { network++; return new Response('network'); } });
  return { listeners, deleted, get network() { return network; }, get skipped() { return skipped; } };
}
test('install failure removes partial new cache, preserves old shell', async () => {
  const h = harness({ failInstall: true }); let work;
  h.listeners.install({ waitUntil: (p) => work = p });
  await assert.rejects(work, /offline/);
  assert.deepEqual(h.deleted, ['mugen-shell-dev']);
  assert.equal(h.skipped, false);
});
test('updates wait for explicit request and only own caches are removed', async () => {
  const h = harness(); let work;
  h.listeners.install({ waitUntil: (p) => work = p }); await work;
  assert.equal(h.skipped, false);
  h.listeners.message({ data: { type: 'SKIP_WAITING' }, waitUntil: (p) => work = p }); await work;
  assert.equal(h.skipped, true);
  h.listeners.activate({ waitUntil: (p) => work = p }); await work;
  assert.deepEqual(h.deleted, ['mugen-shell-old']);
});
test('navigation and assets use the installed build; APIs, external and range requests pass through', async () => {
  const h = harness();
  const request = (path, mode = 'cors', destination = '', range = false) => ({ url: 'https://app.test' + path, method: 'GET', mode, destination, headers: new Headers(range ? { range: 'bytes=0-100' } : {}) });
  let response;
  h.listeners.fetch({ request: request('/MUGEN/reader', 'navigate'), respondWith: (p) => response = p });
  assert.equal(await (await response).text(), 'complete shell');
  h.listeners.fetch({ request: request('/MUGEN/assets/app.js', 'cors', 'script'), respondWith: (p) => response = p });
  assert.equal(await (await response).text(), 'bundle');
  for (const r of [request('/MUGEN/api/data'), request('/MUGEN/movie.mp4', 'cors', 'video', true), request('/elsewhere', 'navigate'), { ...request('/MUGEN/x'), url: 'https://else.test/x' }]) {
    h.listeners.fetch({ request: r, respondWith: () => assert.fail('must not intercept') });
  }
  assert.equal(h.network, 0);
});
