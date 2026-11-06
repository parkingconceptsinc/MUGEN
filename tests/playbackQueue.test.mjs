import { test } from 'node:test';
import assert from 'node:assert/strict';
import { remapIds, remapUniqueIds } from '../src/fileIdentity.ts';
import { restoreQueue, readQueueSnapshot } from '../src/playbackQueue.ts';

test('playlist alias substitution preserves intentional repeats and order', () => {
  assert.deepEqual(remapIds(['A','B','A'], {}), ['A','B','A']);
  const aliases = { oldA:'midA', midA:'A' };
  assert.deepEqual(remapIds(['oldA','B','A'], aliases), ['A','B','A']);
  assert.deepEqual(remapUniqueIds(['oldA','B','A'], aliases), ['A','B']);
});

test('queue restore selects the exact repeated occurrence after aliases converge', () => {
  const saved = restoreQueue(['oldA','B','A','oldA'], 2, { oldA:'A' }, new Set(['A','B']));
  assert.deepEqual(saved, { version:1, ids:['A','B','A','A'], index:2 });
  assert.deepEqual(restoreQueue(saved.ids, saved.index, { oldA:'A' }, new Set(['A','B'])), saved, 'restoration is idempotent');
});

test('missing entries before the selected occurrence shift its position without jumping to the first copy', () => {
  assert.deepEqual(restoreQueue(['gone','A','B','gone','A'], 4, {}, new Set(['A','B'])), { version:1, ids:['A','B','A'], index:2 });
});

test('missing selection falls forward, or to the last item; empty queues stay valid', () => {
  const available = new Set(['A','B']);
  assert.equal(restoreQueue(['A','gone','B','A'], 1, {}, available).index, 1);
  assert.equal(restoreQueue(['A','B','gone'], 2, {}, available).index, 1);
  assert.deepEqual(restoreQueue(['gone'], 0, {}, available), { version:1, ids:[], index:0 });
});

test('legacy queue migrates as a complete snapshot and ignores stale legacy keys thereafter', () => {
  const snapshot = readQueueSnapshot(null, ['A','B','A'], 2);
  assert.deepEqual(readQueueSnapshot(JSON.parse(JSON.stringify(snapshot)), ['B'], 0), snapshot);
  // An interrupted first write leaves the legacy pair; aliases still recover
  // every occurrence on retry, without depending on partially remapped prefs.
  const legacy = readQueueSnapshot(null, ['oldA','B','A'], 2);
  const restored = restoreQueue(legacy.ids, legacy.index, {oldA:'A'});
  assert.deepEqual(restored, {version:1, ids:['A','B','A'], index:2});
  assert.deepEqual(restoreQueue(restored.ids, restored.index, {oldA:'A'}), restored);
});

test('invalid snapshots fall back to legacy data and invalid positions/IDs are normalized', () => {
  assert.deepEqual(readQueueSnapshot({version:2,ids:['B'],index:0}, ['A'], 0), {version:1,ids:['A'],index:0});
  assert.equal(restoreQueue(['A','B'], Infinity).index, 0);
  assert.equal(restoreQueue(['A','B'], 99).index, 1);
  assert.equal(restoreQueue(['A','B'], -1).index, 0);
  assert.deepEqual(restoreQueue(['A',null,'B','A'], 3), {version:1,ids:['A','B','A'],index:2});
});
