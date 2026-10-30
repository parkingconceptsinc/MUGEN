import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseSubtitles, serializeSubtitles, calibrateSubtitles, subtitleTimestamp, decodeSubtitles } from '../src/subtitles.ts';
const srt = '1\n00:00:01,000 --> 00:00:03,000\nHello &amp; &lt;world&gt;\n\n2\n00:00:04,000 --> 00:00:05,000\nSecond\n';
test('SRT / VTT round trip preserves plain text and orders cues', () => {
  const cues = parseSubtitles(srt);
  assert.equal(cues[0].text, 'Hello & <world>');
  for (const format of ['srt', 'vtt']) assert.deepEqual(parseSubtitles(serializeSubtitles(cues, { offsetMs: 0, scale: 1 }, format)), cues);
});
test('offset, drift and clipped export apply exactly once', () => {
  const sync = calibrateSubtitles({ subtitleMs: 1000, videoMs: 2000 }, { subtitleMs: 11000, videoMs: 13000 });
  assert.deepEqual(sync, { scale: 1.1, offsetMs: 900 });
  const cues = parseSubtitles(serializeSubtitles(parseSubtitles(srt), sync, 'srt', 2500, 6000));
  assert.equal(cues[0].startMs, 0);
  assert.equal(cues[0].endMs, 1700);
  assert.equal(cues[1].startMs, 2800);
});
test('rejects unsafe sizes, malformed timings and invalid calibration', () => {
  assert.throws(() => parseSubtitles('x'.repeat(4 * 1024 * 1024 + 1)));
  assert.throws(() => parseSubtitles('1\n00:00:03,000 --> 00:00:01,000\nBad'));
  assert.equal(subtitleTimestamp('00:60:00.000'), null);
  assert.equal(subtitleTimestamp('01:23.4'), 83400);
  assert.throws(() => calibrateSubtitles({ subtitleMs: NaN, videoMs: 0 }));
  assert.throws(() => calibrateSubtitles({ subtitleMs: 0, videoMs: 0 }, { subtitleMs: 1000, videoMs: 4000 }));
  assert.throws(() => calibrateSubtitles({ subtitleMs: 0, videoMs: 0 }, { subtitleMs: 500, videoMs: 500 }));
});
test('ignores WebVTT metadata and invalid cues without treating them as phrases', () => {
  const vtt = 'WEBVTT\n\nNOTE comment\n00:00:00.000 --> 00:00:10.000\nignore\n\nfoo\n00:00:02.000 --> 00:00:04.000 align:start\n<b>Visible</b>\n';
  assert.deepEqual(parseSubtitles(vtt), [{ startMs: 2000, endMs: 4000, text: 'Visible' }]);
});
test('decodes UTF-16 BOM and legacy Windows-1252 subtitles', () => {
  const bytes = Uint8Array.from([255,254,72,0,105,0]);
  assert.equal(decodeSubtitles(bytes.buffer), 'Hi');
  assert.equal(decodeSubtitles(Uint8Array.from([99,97,102,233]).buffer), 'café');
});
