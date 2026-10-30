import { test } from 'node:test';
import assert from 'node:assert/strict';
import { DEFAULT_AUDIO_LAB, DEFAULT_AUDIO_LAB_EQ, sanitizeAudioLab, sanitizeAudioLabEq, audioLabHeadroomDb, audioLabTailSeconds, encodeAudioLabWav, importAudioLabPresetFile, parseAudioLabPresets, serializeAudioLabPresets, mergeAudioLabPresets } from '../src/audioLabModel.ts';
const clean = () => ({ name: 'Clean', settings: { ...DEFAULT_AUDIO_LAB, enabled: true }, eq: { ...DEFAULT_AUDIO_LAB_EQ, enabled: true, bands: Array(10).fill(0) } });
test('settings reject NaN, malformed booleans and dangerous feedback without passing invalid audio parameters', () => {
  const settings = sanitizeAudioLab({ enabled: 'yes', ratio: Infinity, ceiling: 300, delayFeedback: 1000, room: -4, highPassHz: null, compressor: true });
  assert.equal(settings.enabled, false); assert.equal(settings.ratio, 3); assert.equal(settings.ceiling, -0.1); assert.equal(settings.delayFeedback, 0.85); assert.equal(settings.room, 0); assert.equal(settings.highPassHz, 80); assert.equal(settings.compressor, true);
  const eq = sanitizeAudioLabEq({ enabled: true, bands: [NaN, 30, -300, '10'], boost: Infinity, balance: 8 });
  assert.deepEqual(eq.bands, [0,12,-12,0,0,0,0,0,0,0]); assert.equal(eq.boost, 1); assert.equal(eq.balance, 1);
});
test('headroom reserves gain only when enabled, tail is bounded and respects disabled effects', () => {
  const eq = { ...DEFAULT_AUDIO_LAB_EQ, enabled: true, bands: [6,3,0,0,0,0,0,0,0,0], preamp: 3, boost: 2 };
  assert.equal(audioLabHeadroomDb(DEFAULT_AUDIO_LAB, eq), 0);
  assert.ok(Math.abs(audioLabHeadroomDb({ ...DEFAULT_AUDIO_LAB, enabled: true, compressor: true, makeup: 4 }, eq) - (16 + 20 * Math.log10(2))) < 1e-10);
  assert.equal(audioLabTailSeconds({ ...DEFAULT_AUDIO_LAB, delayMix: 0.7 }), 0);
  assert.ok(audioLabTailSeconds({ ...DEFAULT_AUDIO_LAB, enabled: true, delayMix: 0.7, delayFeedback: 0.85, delayMs: 1500 }) <= 12);
});
test('portable presets roundtrip supported fields and use native flat settings schema', () => {
  const source = clean(); source.settings.highPass = true; source.settings.delayMix = 0.2; source.eq.bands[3] = 4;
  const text = serializeAudioLabPresets([source]); const doc = JSON.parse(text);
  assert.equal(doc.presets.Clean.version, 2); assert.equal(doc.presets.Clean.highPass, true); assert.equal(doc.presets.Clean.bands[3], 4);
  assert.deepEqual(parseAudioLabPresets(text), [source]);
  doc.presets.Clean.tune = true; doc.presets.Clean.chorusMix = 0.2;
  assert.deepEqual(importAudioLabPresetFile(JSON.stringify(doc)).unsupported, ['tune', 'chorusMix']);
});
test('imports never silently overwrite presets, reject future versions and bounded input', () => {
  assert.deepEqual(mergeAudioLabPresets([clean()], [clean(), clean()]).map(p => p.name), ['Clean', 'Clean (2)', 'Clean (3)']);
  assert.throws(() => parseAudioLabPresets('x'.repeat(256 * 1024 + 1)));
  assert.throws(() => parseAudioLabPresets('{"format":"mugen-audio-lab","version":2,"presets":{}}'));
  const malformed = JSON.parse(serializeAudioLabPresets([clean()])); malformed.presets.Clean.bands = [1];
  assert.throws(() => parseAudioLabPresets(JSON.stringify(malformed)));
  assert.throws(() => mergeAudioLabPresets(Array.from({ length: 32 }, (_,i) => ({ ...clean(), name: '' + i })), [clean()]));
});
test('disabled EQ is exported as neutral while independent advanced effects remain on', () => {
  const preset = clean(); preset.eq.enabled = false; preset.eq.bands = Array(10).fill(12); preset.eq.preamp = 12;
  const parsed = parseAudioLabPresets(serializeAudioLabPresets([preset]))[0];
  assert.deepEqual(parsed.eq.bands, Array(10).fill(0)); assert.equal(parsed.eq.preamp, 0); assert.equal(parsed.settings.enabled, true);
});
test('PCM WAV header, channel interleave, clipping and nonfinite values are correct', () => {
  const wav = encodeAudioLabWav([new Float32Array([-2, 0.5, NaN]), new Float32Array([2, -0.5, Infinity])], 48000); const view = new DataView(wav);
  const text = (a,b) => new TextDecoder().decode(new Uint8Array(wav, a, b));
  assert.equal(text(0,4), 'RIFF'); assert.equal(text(8,4), 'WAVE'); assert.equal(view.getUint16(22,true),2); assert.equal(view.getUint32(24,true),48000); assert.equal(view.getUint32(40,true),12);
  assert.deepEqual(Array.from({length:6},(_,i)=>view.getInt16(44+i*2,true)),[-32768,32767,16384,-16384,0,0]);
  assert.throws(() => encodeAudioLabWav([new Float32Array(2), new Float32Array(1)],48000));
  assert.throws(() => encodeAudioLabWav([new Float32Array(1)],NaN));
  assert.throws(() => encodeAudioLabWav([],48000));
});
