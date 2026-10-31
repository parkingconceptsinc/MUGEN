import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validateProject, StudioHistory, newClip, locateProject, projectDuration, splitClip, moveClip, replaceClip, projectFingerprint } from '../src/studioModel.ts';
const source = { id: 'source-a', name: 'First.mp4', duration: 10, width: 1280, height: 720, size: 100, type: 'video/mp4' };
const fixture = () => ({ version: 1, id: 'project', name: 'Cut', sources: [source], clips: [newClip(source, 'a')], aspect: '16:9', height: 720, updatedAt: 1 });
test('validates projects and rejects malformed or unbounded imported state', () => {
  assert.equal(validateProject(fixture()).clips.length, 1);
  for (const patch of [{ version: 2 }, { height: 0 }, { aspect: 'bad' }, { name: '' }, { sources: [{...source, size: Infinity}] }, { clips: [{...fixture().clips[0], speed: NaN}] }, { clips: [{...fixture().clips[0], out: 0.4}] }, { clips: [fixture().clips[0], fixture().clips[0]] }]) assert.throws(() => validateProject({...fixture(), ...patch}));
});
test('source and montage time remain distinct with speed and exact boundaries', () => {
  let p = fixture(); p = splitClip(p, 'a', 4, 'b'); p = replaceClip(p, 'a', { speed: 2 });
  assert.equal(projectDuration(p), 8);
  assert.deepEqual(locateProject(p, 1), { index: 0, sourceTime: 2, offset: 0 });
  assert.deepEqual(locateProject(p, 2), { index: 1, sourceTime: 4, offset: 2 });
  assert.deepEqual(locateProject(p, 100), { index: 1, sourceTime: 10, offset: 2 });
});
test('split preserves source edit and rejects cuts too close to either boundary', () => {
  let p = replaceClip(fixture(), 'a', { rotation: 90, mute: true, speed: 1.25 });
  assert.throws(() => splitClip(p, 'a', 0.2, 'b'));
  assert.throws(() => splitClip(p, 'a', 9.8, 'b'));
  const result = splitClip(p, 'a', 5, 'b');
  assert.equal(result.clips.length, 2);
  assert.equal(projectDuration(result), projectDuration(p));
  assert.equal(result.clips[1].rotation, 90);
  assert.equal(result.clips[1].mute, true);
});
test('history snapshots are immutable, undo branches discard redo, moves preserve IDs', () => {
  const initial = fixture(); const h = new StudioHistory(initial);
  const split = h.commit(splitClip(h.current, 'a', 5, 'b'));
  h.commit(moveClip(split, 'b', -1));
  assert.deepEqual(h.current.clips.map(c => c.id), ['b','a']);
  assert.deepEqual(h.undo().clips.map(c => c.id), ['a','b']);
  assert.equal(h.canRedo, true);
  h.commit(replaceClip(h.current, 'a', { brightness: .25 }));
  assert.equal(h.canRedo, false);
  assert.equal(initial.clips[0].brightness, 0);
  assert.equal(h.current.clips[0].brightness, .25);
});
test('save date does not invalidate export but picture changes do', () => {
  const p = fixture();
  assert.equal(projectFingerprint(p), projectFingerprint({...p, updatedAt: 999}));
  assert.notEqual(projectFingerprint(p), projectFingerprint(replaceClip(p, 'a', { volume: .5 })));
});
