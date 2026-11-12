import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createPlaybackBookmarks } from '../tizen/src/bookmarks.js';

function mockStorage() {
  const values = new Map();
  return {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => { values.set(key, value); },
    values,
  };
}

test('TV video positions survive restart, remain per-file, and clear near the end', () => {
  const store = mockStorage();
  const bookmarks = createPlaybackBookmarks(store);
  assert.equal(bookmarks.get('/usb/a.mp4'), 0);
  assert.equal(bookmarks.set('/usb/a.mp4', 40000, 80000), true);
  bookmarks.set('/usb/b.mp4', 7000, 110000);
  assert.equal(createPlaybackBookmarks(store).get('/usb/a.mp4'), 40000);
  assert.equal(createPlaybackBookmarks(store).get('/usb/b.mp4'), 7000);
  bookmarks.set('/usb/a.mp4', 79000, 80000);
  assert.equal(bookmarks.get('/usb/a.mp4'), 0);
  assert.equal(bookmarks.get('/usb/b.mp4'), 7000);
});

test('TV position journal bounds entries and ignores corrupted/oversized data', () => {
  const store = mockStorage(); const bookmarks = createPlaybackBookmarks(store, 2);
  for (let i = 0; i < 4; i++) bookmarks.set('/usb/' + i, 2000 + i, 90000);
  const entries = JSON.parse(store.getItem('mugen.tv.resume.v1')).entries;
  assert.equal(entries.length, 2);
  assert.equal(bookmarks.set('x'.repeat(2049), 2000, 90000), false);
  store.setItem('mugen.tv.resume.v1', '{garbled');
  assert.equal(bookmarks.get('/usb/3'), 0);
  assert.equal(bookmarks.set('/usb/recovered.mp4', 3000, 90000), true);
  assert.equal(bookmarks.get('/usb/recovered.mp4'), 3000);
});

test('TV position storage failure never prevents playback', () => {
  const storage = { getItem() { throw Error('blocked'); }, setItem() { throw Error('full'); } };
  const bookmarks = createPlaybackBookmarks(storage);
  assert.equal(bookmarks.get('/usb/movie.mp4'), 0);
  assert.equal(bookmarks.set('/usb/movie.mp4', 30000, 80000), false);
  assert.equal(bookmarks.clear('/usb/movie.mp4'), false);
});
