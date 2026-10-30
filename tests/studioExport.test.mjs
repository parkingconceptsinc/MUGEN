import { test } from 'node:test';
import assert from 'node:assert/strict';
import { audioTempoFilters, buildClipCommand, canvasFor, checkExportBudget, clipVideoFilters, concatManifest, STUDIO_EXPORT_LIMITS } from '../src/studioExportPlan.ts';
const source = { id: 's1', name: 'Original.mov', duration: 10, width: 1920, height: 1080, size: 16, type: 'video/quicktime' };
const clip = { id: 'c1', sourceId: 's1', in: 1, out: 9, speed: 1, volume: 1, mute: false, rotation: 0, flip: false, brightness: 0, contrast: 1, saturation: 1 };
const project = { version: 1, id: 'p1', name: 'My edit', clips: [clip], sources: [source], aspect: 'source', height: 720, updatedAt: 0 };

test('export uses even source/canvas dimensions and handles quarter turns', () => {
  assert.deepEqual(canvasFor(project), { width: 1280, height: 720 });
  assert.deepEqual(canvasFor({ ...project, clips: [{ ...clip, rotation: 90 }] }), { width: 406, height: 720 });
  assert.deepEqual(canvasFor({ ...project, aspect: '1:1', height: 1080 }), { width: 1080, height: 1080 });
  assert.throws(() => canvasFor({ ...project, sources: [{ ...source, width: 0 }] }), /dimensions/);
  assert.throws(() => canvasFor({ ...project, sources: [{ ...source, width: 20000, height: 100 }] }), /too wide/);
});

test('time stretch chains preserve pitch across the supported speed range', () => {
  assert.deepEqual(audioTempoFilters(0.25), ['atempo=0.5', 'atempo=0.5']);
  assert.deepEqual(audioTempoFilters(4), ['atempo=2', 'atempo=2']);
  assert.deepEqual(audioTempoFilters(1.25), ['atempo=1.25']);
  for (const value of [NaN, Infinity, 0, -1, 10]) assert.throws(() => audioTempoFilters(value));
});

test('clip render trims source time and separately bounds output time', () => {
  const args = buildClipCommand({ ...clip, speed: 2, mute: true, rotation: 90, flip: true }, { width: 720, height: 720 }, true, 'source-0.input', 'clip-0.mp4');
  assert.equal(args[args.indexOf('-ss') + 1], '1');
  const times = args.flatMap((v, i) => v === '-t' ? [args[i + 1]] : []);
  assert.deepEqual(times, ['8', '4']);
  const filters = args[args.indexOf('-vf') + 1];
  assert.match(filters, /setpts=\(PTS-STARTPTS\)\/2/);
  assert.match(filters, /transpose=1,hflip/);
  assert.match(filters, /pad=720:720/);
  assert.match(args[args.indexOf('-af') + 1], /atempo=2,volume=0/);
  assert.equal(args[args.indexOf('-pix_fmt') + 1], 'yuv420p');
});

test('silent videos receive an explicit stereo track with project duration', () => {
  const args = buildClipCommand({ ...clip, speed: 0.5 }, { width: 1280, height: 720 }, false, 'source-1.input', 'clip-1.mp4');
  assert.ok(args.includes('anullsrc=channel_layout=stereo:sample_rate=48000'));
  assert.ok(args.includes('1:a:0'));
  assert.match(args[args.indexOf('-af') + 1], /atempo=1/);
  assert.ok(args.includes('16'));
});

test('filter and filename injection, invalid values and malformed dimensions are rejected', () => {
  assert.throws(() => buildClipCommand(clip, { width: 1280, height: 720 }, true, "evil';rm.input", 'clip-0.mp4'));
  assert.throws(() => clipVideoFilters({ ...clip, brightness: NaN }, { width: 1280, height: 720 }));
  assert.throws(() => clipVideoFilters({ ...clip, rotation: 45 }, { width: 1280, height: 720 }));
  assert.throws(() => clipVideoFilters({ ...clip, volume: 3 }, { width: 1280, height: 720 }));
  assert.throws(() => clipVideoFilters(clip, { width: 1279, height: 720 }));
  assert.equal(concatManifest(2), "file 'clip-0.mp4'\nfile 'clip-1.mp4'\n");
  for (const count of [0, 33, 1.2]) assert.throws(() => concatManifest(count));
});

test('export budget deduplicates shared source files and rejects missing/corrupt/oversize sources', () => {
  const media = new Map([['s1', new Blob([new Uint8Array(16)])]]);
  assert.doesNotThrow(() => checkExportBudget({ ...project, clips: [clip, { ...clip, id: 'c2' }] }, media));
  assert.throws(() => checkExportBudget(project, new Map()), /missing/);
  assert.throws(() => checkExportBudget(project, new Map([['s1', new Blob(['short'])]])), /incomplete/);
  assert.throws(() => checkExportBudget({ ...project, clips: Array.from({ length: 33 }, () => clip) }, media), /32/);
  const huge = { size: STUDIO_EXPORT_LIMITS.sourceBytes + 1 };
  assert.throws(() => checkExportBudget({ ...project, sources: [{ ...source, size: huge.size }] }, new Map([['s1', huge]])), /384 MiB/);
  assert.throws(() => checkExportBudget({ ...project, sources: [{ ...source, duration: 2000 }], clips: [{ ...clip, in: 0, out: 2000 }] }, media), /30 minutes/);
  assert.throws(() => checkExportBudget({ ...project, clips: [{ ...clip, out: 11 }] }, media), /past/);
});
