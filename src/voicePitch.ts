/** Local monophonic voice analysis and overlapping-grain pitch processing.
 * This changes pitch and keeps frame count. It does not preserve formants and
 * must not be described as polyphonic or studio-grade source separation.
 */
export type VoiceSettings = {
  tune: boolean; key: number; scale: number; amount: number; retuneMs: number; shiftSemitones: number;
};
export type VoicePitch = { hz: number; confidence: number; rms: number };
export type VoiceStats = {
  voicedFrames: number; totalFrames: number; meanInputHz: number; meanTargetHz: number;
  meanConfidence: number; maxPeak: number; latencyMs: number;
};
export const DEFAULT_VOICE_SETTINGS: Readonly<VoiceSettings> = Object.freeze({ tune: false, key: 0, scale: 0, amount: 1, retuneMs: 30, shiftSemitones: 0 });
export const VOICE_PITCH_LIMITS = Object.freeze({ seconds: 60, channels: 2, maxFrames: 96_000 * 60 });
const bound = (value: unknown, low: number, high: number, fallback: number) => typeof value === 'number' && Number.isFinite(value) ? Math.max(low, Math.min(high, value)) : fallback;
export function sanitizeVoiceSettings(value: unknown): VoiceSettings {
  const s = value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
  return { tune: typeof s.tune === 'boolean' ? s.tune : false,
    key: Math.round(bound(s.key, 0, 11, 0)), scale: Math.round(bound(s.scale, 0, 3, 0)),
    amount: bound(s.amount, 0, 1, 1), retuneMs: bound(s.retuneMs, 5, 500, 30), shiftSemitones: bound(s.shiftSemitones, -12, 12, 0) };
}
export const voiceMidi = (hz: number): number => hz > 0 && Number.isFinite(hz) ? 69 + 12 * Math.log2(hz / 440) : NaN;
export const voiceHz = (note: number): number => Number.isFinite(note) ? 440 * Math.pow(2, (note - 69) / 12) : 0;
const scales = [[0,1,2,3,4,5,6,7,8,9,10,11], [0,2,4,5,7,9,11], [0,2,3,5,7,8,10], [0,2,4,7,9]];
export function nearestVoiceNote(note: number, key: number, scale: number): number {
  if (!Number.isFinite(note) || note < -128 || note > 255) throw new Error('A detected note must be within the supported musical range.');
  const root = Math.round(bound(key, 0, 11, 0)); const allowed = scales[Math.round(bound(scale, 0, 3, 0))];
  let best = Math.floor(note); let distance = Infinity;
  for (let candidate = Math.floor(note) - 12; candidate <= Math.ceil(note) + 12; candidate++) {
    if (!allowed.includes(((candidate - root) % 12 + 12) % 12)) continue;
    const next = Math.abs(candidate - note);
    if (next < distance) { distance = next; best = candidate; }
  }
  return best;
}
const safeSample = (sample: number) => Number.isFinite(sample) ? Math.max(-1, Math.min(1, sample)) : 0;
function inspectRate(rate: number) {
  if (!Number.isInteger(rate) || rate < 8000 || rate > 96000) throw new Error('Voice processing supports PCM sample rates from 8 to 96 kHz.');
}
/** YIN normalized difference, confidence gating and parabolic interpolation. */
function detectWindow(x: Float32Array, rate: number): VoicePitch {
  let energy = 0; let mean = 0;
  for (const sample of x) mean += sample;
  mean /= x.length;
  for (const sample of x) energy += (sample - mean) ** 2;
  const rms = Math.sqrt(energy / x.length);
  if (!Number.isFinite(rms) || rms < 0.004) return { hz: 0, confidence: 0, rms: Number.isFinite(rms) ? rms : 0 };
  const maxTau = Math.min(Math.floor(x.length / 2) - 1, Math.floor(rate / 60));
  const minTau = Math.max(2, Math.floor(rate / 1000));
  if (maxTau <= minTau + 1) return { hz: 0, confidence: 0, rms };
  const compared = x.length - maxTau;
  const difference = new Float64Array(maxTau + 1); difference[0] = 1;
  let sum = 0;
  for (let tau = 1; tau <= maxTau; tau++) {
    let value = 0;
    for (let j = 0; j < compared; j++) { const delta = x[j] - x[j + tau]; value += delta * delta; }
    sum += value; difference[tau] = sum > 1e-15 ? value * tau / sum : 1;
  }
  let selected = -1;
  for (let tau = minTau; tau < maxTau; tau++) {
    if (difference[tau] < 0.16 && difference[tau] <= difference[tau + 1]) { selected = tau; break; }
  }
  if (selected < 0) return { hz: 0, confidence: 0, rms };
  const left = difference[selected - 1]; const mid = difference[selected]; const right = difference[selected + 1];
  const divisor = left - 2 * mid + right;
  const adjustment = Math.abs(divisor) > 1e-9 ? Math.max(-0.5, Math.min(0.5, 0.5 * (left - right) / divisor)) : 0;
  const hz = rate / (selected + adjustment);
  if (hz < 60 || hz > 1000 || !Number.isFinite(hz)) return { hz: 0, confidence: 0, rms };
  return { hz, confidence: Math.max(0, Math.min(1, 1 - mid)), rms };
}
function referenceAudio(input: Float32Array, rate: number): { values: Float32Array; rate: number; factor: number } {
  const factor = Math.max(1, Math.ceil(rate / 24000));
  const values = new Float32Array(Math.ceil(input.length / factor));
  const lowpass = 1 - Math.exp(-2 * Math.PI * Math.min(2200, rate * 0.4) / rate);
  let first = 0; let second = 0;
  for (let i = 0; i < input.length; i++) {
    first += (safeSample(input[i]) - first) * lowpass; second += (first - second) * lowpass;
    if (i % factor === 0) values[Math.floor(i / factor)] = second;
  }
  return { values, rate: rate / factor, factor };
}
function windowAt(input: Float32Array, center: number, length = 1024): Float32Array {
  const start = Math.max(0, Math.min(input.length - length, Math.round(center - length / 2)));
  const window = new Float32Array(length);
  window.set(input.subarray(start, Math.min(input.length, start + length)));
  return window;
}
/** Analyze a centered, bounded window of a mono signal; unvoiced returns hz 0. */
export function detectVoicePitch(input: Float32Array, rate: number): VoicePitch {
  inspectRate(rate);
  if (!(input instanceof Float32Array) || !input.length || input.length > VOICE_PITCH_LIMITS.maxFrames) throw new Error('A bounded PCM voice signal is required.');
  // Public analysis deliberately inspects a centered segment without allocating a
  // filtered copy of an entire long recording.
  const length = Math.min(input.length, Math.ceil(rate * 0.12));
  const start = Math.max(0, Math.floor((input.length - length) / 2));
  const reference = referenceAudio(input.subarray(start, start + length), rate);
  return detectWindow(windowAt(reference.values, reference.values.length / 2), reference.rate);
}
function abortIfRequested(shouldAbort?: () => boolean) {
  if (shouldAbort?.()) throw new DOMException('Voice render cancelled. The original is unchanged.', 'AbortError');
}
/**
 * Offline render. Analysis uses centered windows, then two equal-power delay
 * grains follow the pitch ratio. Mean grain delay is compensated with lookahead;
 * the first/last grain can fade at boundaries, but frame count is unchanged.
 * Both stereo channels use exactly the same time/ratio envelope.
 */
export function processVoice(channels: Float32Array[], rate: number, rawSettings: VoiceSettings,
  onProgress?: (fraction: number) => void, shouldAbort?: () => boolean): { channels: Float32Array[]; stats: VoiceStats } {
  inspectRate(rate); abortIfRequested(shouldAbort);
  if (!Array.isArray(channels) || channels.length < 1 || channels.length > 2 || channels.some(c => !(c instanceof Float32Array))) throw new Error('Choose mono or stereo voice PCM.');
  const frames = channels[0].length;
  if (!frames || frames > rate * VOICE_PITCH_LIMITS.seconds || channels.some(c => c.length !== frames)) throw new Error('Voice processing supports equal-length channels up to 60 seconds.');
  const settings = sanitizeVoiceSettings(rawSettings);
  // Avoid cancellation in stereo sources by analyzing the stronger channel.
  let referenceIndex = 0; let strongest = -1;
  channels.forEach((channel, index) => {
    let energy = 0; for (let i = 0; i < frames; i += 32) energy += safeSample(channel[i]) ** 2;
    if (energy > strongest) { strongest = energy; referenceIndex = index; }
  });
  const reference = referenceAudio(channels[referenceIndex], rate);
  const hop = Math.max(1, Math.round(rate * 0.02));
  const count = Math.ceil(frames / hop);
  const curve: Array<{ ratio: number; window: number; wet: number }> = [];
  const transpose = Math.pow(2, settings.shiftSemitones / 12);
  let voicedFrames = 0; let inputTotal = 0; let targetTotal = 0; let confidenceTotal = 0;
  const neutral = (!settings.tune || settings.amount === 0) && Math.abs(settings.shiftSemitones) < 0.000001;
  for (let frame = 0; frame < count; frame++) {
    abortIfRequested(shouldAbort);
    const pitch = detectWindow(windowAt(reference.values, frame * hop / reference.factor), reference.rate);
    const voiced = pitch.hz > 0 && pitch.confidence >= 0.8;
    const corrected = voiced && settings.tune ? voiceHz(nearestVoiceNote(voiceMidi(pitch.hz), settings.key, settings.scale)) / pitch.hz : 1;
    const ratio = Math.max(0.4, Math.min(2.5, transpose * Math.pow(corrected, settings.tune ? settings.amount : 0)));
    const period = voiced ? rate / pitch.hz : rate * 0.02;
    const window = Math.max(rate * 0.024, Math.min(rate * 0.07, period * (2 * Math.max(1, Math.round(rate * 0.04 / (2 * period))))));
    // A one-cent deadband avoids unnecessary grain modulation of an already
    // centered note when the estimator differs by a fraction of a cent.
    const wet = !neutral && (Math.abs(settings.shiftSemitones) > 0.000001 || (voiced && Math.abs(Math.log2(ratio)) > 1 / 1200)) ? 1 : 0;
    curve.push({ ratio, window, wet });
    if (voiced) { voicedFrames++; inputTotal += pitch.hz; targetTotal += pitch.hz * ratio; confidenceTotal += pitch.confidence; }
    if (frame % 8 === 0 || frame === count - 1) onProgress?.(0.45 * (frame + 1) / count);
  }
  const output = channels.map(() => new Float32Array(frames));
  let phase = 0.25; let ratio = 1; let window = curve[0].window; let wet = 0; let peak = 0;
  const smooth = 1 - Math.exp(-1 / (rate * settings.retuneMs * 0.001));
  const windowSmooth = 1 - Math.exp(-1 / (rate * 0.03)); const mixSmooth = 1 - Math.exp(-1 / (rate * 0.005));
  const read = (channel: Float32Array, position: number): number => {
    if (position < 0 || position > frames - 1) return 0;
    const base = Math.floor(position); const t = position - base;
    return safeSample(channel[base]) * (1 - t) + safeSample(channel[Math.min(frames - 1, base + 1)]) * t;
  };
  for (let i = 0; i < frames; i++) {
    if (i % 4096 === 0) { abortIfRequested(shouldAbort); onProgress?.(0.45 + 0.55 * i / frames); }
    if (neutral) {
      for (let ch = 0; ch < channels.length; ch++) { const value = safeSample(channels[ch][i]); output[ch][i] = value; peak = Math.max(peak, Math.abs(value)); }
      continue;
    }
    const target = curve[Math.min(count - 1, Math.floor(i / hop))];
    ratio += (target.ratio - ratio) * smooth; window += (target.window - window) * windowSmooth; wet += (target.wet - wet) * mixSmooth;
    const otherPhase = (phase + 0.5) % 1; const firstWeight = Math.sin(Math.PI * phase) ** 2; const secondWeight = 1 - firstWeight;
    const lookahead = window * 0.5;
    for (let ch = 0; ch < channels.length; ch++) {
      const dry = safeSample(channels[ch][i]);
      const shifted = read(channels[ch], i + lookahead - phase * window) * firstWeight + read(channels[ch], i + lookahead - otherPhase * window) * secondWeight;
      const value = safeSample(dry + (shifted - dry) * wet); output[ch][i] = value; peak = Math.max(peak, Math.abs(value));
    }
    phase += (1 - ratio) / window; phase -= Math.floor(phase);
  }
  abortIfRequested(shouldAbort); onProgress?.(1);
  return { channels: output, stats: { voicedFrames, totalFrames: count, meanInputHz: voicedFrames ? inputTotal / voicedFrames : 0, meanTargetHz: voicedFrames ? targetTotal / voicedFrames : 0,
    meanConfidence: voicedFrames ? confidenceTotal / voicedFrames : 0, maxPeak: peak, latencyMs: 0 } };
}
