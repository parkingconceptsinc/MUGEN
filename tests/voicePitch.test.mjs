import { test } from 'node:test';
import assert from 'node:assert/strict';
import { DEFAULT_VOICE_SETTINGS, detectVoicePitch, nearestVoiceNote, processVoice, sanitizeVoiceSettings, voiceHz, voiceMidi } from '../src/voicePitch.ts';
const rate = 48000;
const tone = (hz, seconds = 1, amplitude = 0.4) => Float32Array.from({ length: rate * seconds }, (_, i) => amplitude * Math.sin(2 * Math.PI * hz * i / rate));
const settings = (patch) => ({ ...DEFAULT_VOICE_SETTINGS, ...patch });
const frequency = (signal) => detectVoicePitch(signal.subarray(Math.floor(signal.length * 0.35), Math.floor(signal.length * 0.8)), rate).hz;
// Independent signal measurement: upward zero crossings after the retune ramp.
const crossingFrequency = (signal) => {
  const start = Math.floor(signal.length * 0.35); const end = Math.floor(signal.length * 0.8);
  let first = 0; let last = 0; let count = 0;
  for (let i = start + 1; i < end; i++) if (signal[i - 1] <= 0 && signal[i] > 0) {
    const crossing = i - 1 - signal[i - 1] / (signal[i] - signal[i - 1]);
    if (!count) first = crossing; last = crossing; count++;
  }
  return count > 1 ? (count - 1) * rate / (last - first) : 0;
};
const close = (actual, expected, margin = 3) => assert.ok(Math.abs(actual - expected) < margin, `Expected ${expected} Hz, got ${actual}`);

test('bounded settings and scales reject malformed values while retaining valid musical choices', () => {
  const cleaned = sanitizeVoiceSettings({ tune: 'yes', key: 999, scale: NaN, amount: Infinity, retuneMs: -5, shiftSemitones: -99 });
  assert.deepEqual(cleaned, { tune: false, key: 11, scale: 0, amount: 1, retuneMs: 5, shiftSemitones: -12 });
  close(voiceHz(voiceMidi(450)), 450, 0.0001);
  assert.equal(nearestVoiceNote(61.2, 0, 1), 62);
  assert.equal(nearestVoiceNote(61.2, 0, 2), 62);
  assert.equal(nearestVoiceNote(61.2, 1, 1), 61);
  assert.throws(() => nearestVoiceNote(NaN, 0, 0));
  assert.throws(() => nearestVoiceNote(1e308, 0, 0));
});
test('detector distinguishes voiced fundamentals, silence and deterministic noise', () => {
  for (const hz of [70, 110, 220, 450, 880]) { const detected = detectVoicePitch(tone(hz), rate); close(detected.hz, hz, 2); assert.ok(detected.confidence > 0.9); }
  assert.equal(detectVoicePitch(new Float32Array(rate), rate).hz, 0);
  let seed = 77;
  const noise = Float32Array.from({ length: rate }, () => { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return (seed / 2**32 - 0.5) * 0.5; });
  assert.equal(detectVoicePitch(noise, rate).hz, 0);
});
test('octave transposition changes actual signal frequency without changing frame count', () => {
  const original = tone(220, 1.2); const before = original.slice();
  for (const [shift, expected] of [[12, 440], [-12, 110]]) {
    const result = processVoice([original], rate, settings({ shiftSemitones: shift, retuneMs: 5 }));
    assert.equal(result.channels[0].length, original.length); close(frequency(result.channels[0]), expected); close(crossingFrequency(result.channels[0]), expected);
    assert.ok(result.channels[0].every(Number.isFinite)); assert.ok(result.stats.maxPeak <= 1);
  }
  assert.deepEqual(original, before);
});
test('key/scale correction and amount affect rendered pitch, not only reported target', () => {
  const original = tone(450, 1.2);
  const full = processVoice([original], rate, settings({ tune: true, key: 0, scale: 1, amount: 1, retuneMs: 5 }));
  const half = processVoice([original], rate, settings({ tune: true, key: 0, scale: 1, amount: 0.5, retuneMs: 5 }));
  close(frequency(full.channels[0]), 440); close(crossingFrequency(full.channels[0]), 440);
  close(frequency(half.channels[0]), Math.sqrt(450 * 440));
  close(full.stats.meanInputHz, 450, 2); close(full.stats.meanTargetHz, 440, 2);
  assert.ok(full.stats.voicedFrames > full.stats.totalFrames * 0.9);
});
test('default retune corrects a flat 430 Hz recording to A4 without changing duration', () => {
  const original = tone(430, 2);
  const result = processVoice([original], rate, settings({ tune: true, key: 0, scale: 0 }));
  const stable = result.channels[0].subarray(Math.floor(rate * 0.4), Math.floor(rate * 1.4));
  close(detectVoicePitch(stable, rate).hz, 440, 4);
  close(crossingFrequency(stable), 440, 4);
  assert.equal(result.channels[0].length, original.length);
});
test('neutral render is sample-exact and stereo channels share timing including anti-phase sources', () => {
  const original = tone(220); const opposite = original.map(v => -v);
  const dry = processVoice([original, opposite], rate, settings({}));
  assert.deepEqual(dry.channels[0], original); assert.deepEqual(dry.channels[1], opposite);
  assert.deepEqual(processVoice([original], rate, settings({ tune: true, amount: 0 })).channels[0], original);
  const centered = tone(440);
  assert.deepEqual(processVoice([centered], rate, settings({ tune: true })).channels[0], centered);
  const wet = processVoice([original, opposite], rate, settings({ tune: true, shiftSemitones: 12, retuneMs: 5 }));
  close(frequency(wet.channels[0]), 440);
  let error = 0; for (let i = 0; i < original.length; i++) error = Math.max(error, Math.abs(wet.channels[0][i] + wet.channels[1][i]));
  assert.ok(error < 1e-6, `Stereo timing diverged by ${error}`);
});
test('unvoiced tuning remains dry, finite transitions remain bounded and cancellation is honored', () => {
  const original = new Float32Array(rate * 2); original.set(tone(220, 0.5), rate * 0.5); original.set(tone(450, 0.5), rate * 1.25);
  const result = processVoice([original], rate, settings({ tune: true, scale: 1, retuneMs: 30 }));
  assert.equal(result.channels[0].length, original.length); assert.ok(result.channels[0].every(v => Number.isFinite(v) && Math.abs(v) <= 1));
  assert.ok(result.channels[0].subarray(0, rate / 4).every(v => v === 0));
  let last = 0; let updates = 0;
  assert.throws(() => processVoice([original], rate, settings({}), progress => { assert.ok(progress >= last); last = progress; updates++; }, () => updates >= 2), error => error.name === 'AbortError');
  assert.throws(() => processVoice([original, original.subarray(1)], rate, settings({})), /equal-length/);
  assert.throws(() => processVoice([original], 2000, settings({})), /sample rates/);
});
