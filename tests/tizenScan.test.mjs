import { test } from 'node:test';
import assert from 'node:assert/strict';
import { collectUsbMedia } from '../tizen/src/scan.js';
const file = (name, fullPath = '/usb/' + name) => ({ name, fullPath, isDirectory: false });
const folder = (name, children, fails = false) => ({ name, fullPath: '/usb/' + name, isDirectory: true, listFiles: (ok, fail) => fails ? fail(Error('removed')) : ok(children) });

test('USB scan skips cycles and duplicates and keeps scanning after an unreadable child', async () => {
  const one = file('one.mp3');
  const root = folder('root', []);
  root.listFiles = ok => ok([one, root, folder('broken', [], true), folder('child', [one, file('two.mp4')])]);
  const result = await collectUsbMedia(root, 'removable_sda');
  assert.deepEqual(result.items.map(item => item.name), ['one.mp3', 'two.mp4']);
  assert.equal(result.unreadable, 1);
  assert.equal(result.items[1].video, true);
});
test('USB scan limits are explicit and cancellation yields to input during large scans', async () => {
  const root = folder('root', Array.from({ length: 305 }, (_, i) => file(i + '.mp3')));
  const limited = await collectUsbMedia(root, 'removable_sda', { maxFiles: 100 });
  assert.equal(limited.items.length, 100); assert.equal(limited.limited, true);
  let cancelled = false;
  const result = await collectUsbMedia(root, 'removable_sda', {
    shouldContinue: () => !cancelled, onProgress: count => { if (count >= 50) cancelled = true; },
  });
  assert.equal(result.cancelled, true); assert.ok(result.items.length < 305);
});
