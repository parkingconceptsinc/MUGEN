import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
import { BackgroundWork } from '../src/backgroundWork.ts';
test('enrichment waits for both playback and import, runs serially and survives failed metadata', async () => {
  const work = new BackgroundWork(), calls = [];
  work.setPaused('playback', true); work.setPaused('import', true);
  work.enqueue(async () => { calls.push(1); await delay(20); calls.push(2); throw Error('bad tags'); });
  work.enqueue(async () => { calls.push(3); });
  await delay(80); assert.deepEqual(calls, []);
  work.setPaused('import', false);
  await delay(80); assert.deepEqual(calls, []);
  work.setPaused('playback', false);
  for(let i=0;i<30 && calls.length<3;i++) await delay(25);
  assert.deepEqual(calls, [1,2,3]);
});
test('a new play request blocks work already scheduled for the next idle turn', async () => {
  const work = new BackgroundWork(); let calls=0;
  work.enqueue(async () => { calls++; }); work.setPaused('playback', true);
  await delay(100); assert.equal(calls, 0);
  work.setPaused('playback', false);
  for(let i=0;i<20 && !calls;i++) await delay(25);
  assert.equal(calls, 1);
});
