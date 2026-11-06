import { test, before, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { indexedDB, IDBObjectStore } from 'fake-indexeddb';
import { createServer } from 'vite';
import { groupIdenticalFiles, sameFileBytes, remapIds, remapUniqueIds, remapNumbers, resolveAlias } from '../src/fileIdentity.ts';

let server, library;
before(async () => {
  globalThis.indexedDB = indexedDB;
  server = await createServer({ configFile: false, server: { middlewareMode: true } });
  library = await server.ssrLoadModule('/src/library.ts');
});
after(async () => { await server?.close(); });
beforeEach(async () => {
  await new Promise((resolve, reject) => {
    const request = indexedDB.deleteDatabase('mortimer-player');
    request.onsuccess = resolve; request.onerror = () => reject(request.error);
  });
});
const item = (id, bytes = 'abcd', overrides = {}) => ({
  id, name: 'Song.mp3', kind: 'audio', path: 'Music/Song.mp3', size: bytes.length,
  lastModified: 100, file: new Blob([bytes]), addedAt: Number(id.replace(/\D/g, '')) || 1,
  ...overrides,
});
async function records(storeName = 'library') {
  return new Promise((resolve, reject) => {
    const open = indexedDB.open('mortimer-player');
    open.onerror = () => reject(open.error);
    open.onsuccess = () => {
      const db = open.result, tx = db.transaction(storeName), request = tx.objectStore(storeName).getAll();
      tx.oncomplete = () => { db.close(); resolve(request.result); };
    };
  });
}
async function seedLegacy(items) {
  await new Promise((resolve, reject) => {
    const open = indexedDB.open('mortimer-player', 5);
    open.onupgradeneeded = () => {
      for (const name of ['library','comics','photos']) {
        const store = open.result.createObjectStore(name, { keyPath: 'id' });
        store.createIndex('identity', ['path','size','lastModified']);
      }
    };
    open.onerror = () => reject(open.error);
    open.onsuccess = () => {
      const db = open.result, tx = db.transaction('library','readwrite');
      items.forEach(item => tx.objectStore('library').put(item));
      tx.oncomplete = () => { db.close(); resolve(); };
    };
  });
}

test('individual pick, parent folder and changed timestamp import once by full contents', async () => {
  assert.equal((await library.saveLibraryFast([item('one')])).length, 1);
  assert.equal((await library.saveLibraryFast([item('two', 'abcd', { path: 'Song.mp3', lastModified: 900 })])).length, 0);
  assert.equal((await library.saveLibraryFast([item('three', 'abcd', { path: 'Disk/Music/Song.mp3' })])).length, 0);
  assert.equal((await records()).length, 1);
});
test('same path, name, size and date with different bytes must retain both files', async () => {
  assert.equal((await library.saveLibraryFast([item('one'),item('two', 'abce')])).length, 2);
  assert.equal((await library.loadLibrary()).length, 2);
});
test('simultaneous imports recheck in the write transaction and save exactly one copy', async () => {
  const result = await Promise.all(Array.from({ length: 5 }, (_, i) => library.saveLibraryFast([item('copy'+i)])));
  assert.equal(result.flat().length, 1);
  assert.equal((await records()).length, 1);
});
test('legacy cleanup merges metadata, journals aliases and preserves distinct homonyms', async () => {
  await seedLegacy([
    item('old', 'abcd', { addedAt: 1, artist: 'Artist' }),
    item('duplicate', 'abcd', { addedAt: 2, path: 'Song.mp3', album: 'Album', duration: 10 }),
    item('other', 'abce', { addedAt: 3 }),
  ]);
  const saved = await library.loadLibrary();
  assert.equal(saved.length, 2);
  assert.equal((await records()).length, 2);
  const original = saved.find(item => item.id === 'old');
  assert.equal(original.artist, 'Artist'); assert.equal(original.album, 'Album'); assert.equal(original.duration, 10);
  const aliases = await library.loadFileAliases();
  assert.deepEqual(aliases, { duplicate: 'old' });
  assert.deepEqual(remapIds(['duplicate','other','old'], aliases), ['old','other','old']);
  assert.deepEqual(remapUniqueIds(['duplicate','other','old'], aliases), ['old','other']);
  assert.deepEqual(remapNumbers({ old: 2, duplicate: 3 }, aliases, true), { old: 5 });
  assert.deepEqual(remapNumbers({ old: 5, duplicate: 2 }, aliases), { old: 5 });
  assert.equal(resolveAlias('duplicate', aliases), 'old');
  assert.deepEqual(await library.loadFileAliases(), aliases, 'aliases survive a second open');
});
test('an aborted cleanup leaves both originals and the alias journal unchanged', async () => {
  await seedLegacy([item('old','abcd',{addedAt:1}), item('duplicate','abcd',{addedAt:2})]);
  const put = IDBObjectStore.prototype.put;
  IDBObjectStore.prototype.put = function(...args) {
    if (this.name === 'aliases') { this.transaction.abort(); return; }
    return put.apply(this, args);
  };
  try { await assert.rejects(library.loadLibrary()); }
  finally { IDBObjectStore.prototype.put = put; }
  assert.equal((await records()).length, 2);
  assert.equal((await records('aliases')).length, 0);
  assert.equal((await library.loadLibrary()).length, 1);
});
test('cancelled imports cannot write, failed batches retry without duplicate records', async () => {
  assert.equal((await library.saveLibraryFast([item('cancel')], () => false)).length, 0);
  const put = IDBObjectStore.prototype.put;
  IDBObjectStore.prototype.put = function(...args) {
    if (this.name === 'library') { this.transaction.abort(); return; }
    return put.apply(this, args);
  };
  try { await assert.rejects(library.saveLibraryFast([item('failed'),item('failed2','abcd',{name:'Another.mp3'})])); }
  finally { IDBObjectStore.prototype.put = put; }
  assert.equal((await library.saveLibraryFast([item('retry')])).length, 1);
  assert.equal((await library.saveLibraryFast([item('again')])).length, 0);
});
test('books use content identity; clearing the library also clears recovery aliases', async () => {
  await seedLegacy([item('old','abcd',{addedAt:1}),item('duplicate','abcd',{addedAt:2})]);
  await library.loadLibrary();
  const book = { ...item('book'), name: 'Book.pdf', format: 'pdf' };
  assert.equal((await library.saveComicsFast([book,{...book,id:'book2',path:'Book.pdf'}])).length, 1);
  await library.clearLibrary();
  assert.equal((await records()).length, 0);
  assert.equal((await records('comics')).length, 0);
  assert.deepEqual(await library.loadFileAliases(), {});
});
test('differences beyond the first megabyte are detected, malformed files are retained', async () => {
  const x = new Uint8Array(2 * 1024 * 1024 + 1), y = x.slice(); y[y.length-1] = 1;
  assert.equal(await sameFileBytes(new Blob([x]),new Blob([y])), false);
  const groups = await groupIdenticalFiles([item('one'),item('two','abcd',{file:null})]);
  assert.equal(groups.length, 2);
});
