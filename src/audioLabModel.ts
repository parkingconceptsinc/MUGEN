/** Shared, bounded values for live processing, presets and offline rendering. */
export type AudioLabSettings = {
  enabled: boolean; autoHeadroom: boolean;
  highPass: boolean; highPassHz: number; lowPass: boolean; lowPassHz: number;
  compressor: boolean; threshold: number; ratio: number; attackMs: number; releaseMs: number; makeup: number;
  limiter: boolean; ceiling: number;
  delayMix: number; delayMs: number; delayFeedback: number;
  reverbMix: number; room: number;
};
export type AudioLabEq = { enabled: boolean; bands: number[]; preamp: number; balance: number; boost: number };
export type AudioLabPreset = { name: string; settings: AudioLabSettings; eq: AudioLabEq };
export const DEFAULT_AUDIO_LAB: AudioLabSettings = {
  enabled: false, autoHeadroom: true,
  highPass: false, highPassHz: 80, lowPass: false, lowPassHz: 18000,
  compressor: false, threshold: -18, ratio: 3, attackMs: 10, releaseMs: 150, makeup: 0,
  limiter: true, ceiling: -0.5, delayMix: 0, delayMs: 300, delayFeedback: 0.35, reverbMix: 0, room: 0.5,
};
export const DEFAULT_AUDIO_LAB_EQ: AudioLabEq = { enabled: false, bands: Array(10).fill(0), preamp: 0, balance: 0, boost: 1 };
const object = (value: unknown): Record<string, unknown> => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
const finite = (value: unknown, min: number, max: number, fallback: number) => typeof value === 'number' && Number.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
const bool = (value: unknown, fallback: boolean) => typeof value === 'boolean' ? value : fallback;
export function sanitizeAudioLab(value: unknown): AudioLabSettings {
  const v = object(value); const d = DEFAULT_AUDIO_LAB;
  return {
    enabled: bool(v.enabled, d.enabled), autoHeadroom: bool(v.autoHeadroom, d.autoHeadroom),
    highPass: bool(v.highPass, d.highPass), highPassHz: finite(v.highPassHz, 20, 1200, d.highPassHz),
    lowPass: bool(v.lowPass, d.lowPass), lowPassHz: finite(v.lowPassHz, 1500, 22000, d.lowPassHz),
    compressor: bool(v.compressor, d.compressor), threshold: finite(v.threshold, -60, 0, d.threshold), ratio: finite(v.ratio, 1, 20, d.ratio),
    attackMs: finite(v.attackMs, 0.5, 100, d.attackMs), releaseMs: finite(v.releaseMs, 20, 1000, d.releaseMs), makeup: finite(v.makeup, 0, 12, d.makeup),
    limiter: bool(v.limiter, d.limiter), ceiling: finite(v.ceiling, -12, -0.1, d.ceiling),
    delayMix: finite(v.delayMix, 0, 0.7, d.delayMix), delayMs: finite(v.delayMs, 10, 1500, d.delayMs), delayFeedback: finite(v.delayFeedback, 0, 0.85, d.delayFeedback),
    reverbMix: finite(v.reverbMix, 0, 0.6, d.reverbMix), room: finite(v.room, 0, 1, d.room),
  };
}
export function sanitizeAudioLabEq(value: unknown): AudioLabEq {
  const v = object(value); const bands = Array.isArray(v.bands) ? v.bands : [];
  return { enabled: bool(v.enabled, false), bands: Array.from({ length: 10 }, (_, i) => finite(bands[i], -12, 12, 0)), preamp: finite(v.preamp, -24, 12, 0), balance: finite(v.balance, -1, 1, 0), boost: finite(v.boost, 1, 2, 1) };
}
/** Conservative broadband trim avoids stacking multiple positive boosts into clipping. */
export function audioLabHeadroomDb(settings: AudioLabSettings, eq: AudioLabEq): number {
  if (!settings.enabled || !settings.autoHeadroom) return 0;
  const eqGain = eq.enabled ? eq.bands.reduce((sum, db) => sum + Math.max(0, db), 0) + Math.max(0, eq.preamp) : 0;
  return Math.min(48, eqGain + 20 * Math.log10(eq.boost) + (settings.compressor ? settings.makeup : 0));
}
export function audioLabTailSeconds(settings: AudioLabSettings): number {
  if (!settings.enabled) return 0;
  const delay = settings.delayMix > 0 ? settings.delayMs / 1000 * Math.max(1, Math.ceil(Math.log(0.001) / Math.log(Math.max(0.001, settings.delayFeedback)))) : 0;
  return Math.min(12, Math.max(delay, settings.reverbMix > 0 ? 0.3 + settings.room * 2.7 : 0));
}
export function serializeAudioLabPresets(presets: AudioLabPreset[]): string {
  if (presets.length > 32) throw new Error('Store at most 32 Audio Lab presets.');
  const entries: Array<[string, unknown]> = []; const names = new Set<string>();
  for (const preset of presets) {
    const name = preset.name.trim();
    if (!name || name.length > 48 || names.has(name)) throw new Error('Preset names must be unique, non-empty and at most 48 characters.');
    names.add(name); const s = sanitizeAudioLab(preset.settings); const eq = sanitizeAudioLabEq(preset.eq);
    entries.push([name, { ...s, version: 2, preset: name,
      enabled: s.enabled || eq.enabled || eq.balance !== 0 || eq.boost !== 1,
      bands: eq.enabled ? eq.bands : Array(10).fill(0), preamp: eq.enabled ? eq.preamp : 0, balance: eq.balance, boost: eq.boost,
      autoHeadroom: s.enabled && s.autoHeadroom, highPass: s.enabled && s.highPass, lowPass: s.enabled && s.lowPass, compressor: s.enabled && s.compressor,
      delayMix: s.enabled ? s.delayMix : 0, reverbMix: s.enabled ? s.reverbMix : 0,
    }]);
  }
  return JSON.stringify({ format: 'mugen-audio-lab', version: 1, presets: Object.fromEntries(entries) }, null, 2);
}
export function importAudioLabPresetFile(text: string): { presets: AudioLabPreset[]; unsupported: string[] } {
  if (text.length > 256 * 1024) throw new Error('Preset file exceeds 256 KiB.');
  const doc = object(JSON.parse(text));
  if (doc.format !== 'mugen-audio-lab' || doc.version !== 1 || !doc.presets || typeof doc.presets !== 'object' || Array.isArray(doc.presets)) throw new Error('Choose a MUGEN Audio Lab preset file (version 1).');
  const entries = Object.entries(doc.presets);
  if (entries.length > 32) throw new Error('Store at most 32 Audio Lab presets.');
  const unsupported = new Set<string>();
  const presets = entries.map(([name, raw]) => {
    const v = object(raw);
    if (!name.trim() || name.length > 48 || v.version !== 2 || !Array.isArray(v.bands) || v.bands.length !== 10 || !v.bands.every(n => typeof n === 'number' && Number.isFinite(n)) || typeof v.enabled !== 'boolean') throw new Error('A preset is incomplete or uses an unsupported settings version.');
    for (const key of ['gate', 'tune', 'delaySync']) if (v[key] === true) unsupported.add(key);
    for (const key of ['chorusMix', 'driveMix', 'crusherMix', 'tremoloMix', 'robotMix', 'shiftSemitones']) if (typeof v[key] === 'number' && v[key] !== 0) unsupported.add(key);
    if (typeof v.width === 'number' && v.width !== 1) unsupported.add('width');
    if (typeof v.damping === 'number' && v.damping !== 0.45) unsupported.add('damping');
    return { name: name.trim(), settings: sanitizeAudioLab(v), eq: sanitizeAudioLabEq(v) };
  });
  if (new Set(presets.map(p => p.name)).size !== presets.length) throw new Error('Preset names must be unique.');
  return { presets, unsupported: [...unsupported] };
}
export function parseAudioLabPresets(text: string): AudioLabPreset[] { return importAudioLabPresetFile(text).presets; }
export function mergeAudioLabPresets(current: AudioLabPreset[], incoming: AudioLabPreset[]): AudioLabPreset[] {
  if (current.length + incoming.length > 32) throw new Error('Import would exceed 32 saved presets. Remove some presets first.');
  const names = new Set(current.map(p => p.name));
  return current.concat(incoming.map(p => {
    let name = p.name; let suffix = 2;
    while (names.has(name)) { const ending = ` (${suffix++})`; name = p.name.slice(0, 48 - ending.length) + ending; }
    names.add(name); return { ...p, name };
  }));
}
/** PCM16 little-endian WAV; rejects large/invalid buffers before allocating. */
export function encodeAudioLabWav(channels: Float32Array[], sampleRate: number): ArrayBuffer {
  if (!Number.isInteger(sampleRate) || sampleRate < 8000 || sampleRate > 192000 || channels.length < 1 || channels.length > 2) throw new Error('WAV requires mono/stereo PCM at 8–192 kHz.');
  const frames = channels[0].length;
  if (!frames || channels.some(c => c.length !== frames) || frames > sampleRate * 313 || frames * channels.length * 2 > 128 * 1024 * 1024) throw new Error('WAV duration or channel length is outside the supported limit.');
  const buffer = new ArrayBuffer(44 + frames * channels.length * 2); const view = new DataView(buffer);
  const ascii = (offset: number, text: string) => { for (let i = 0; i < text.length; i++) view.setUint8(offset + i, text.charCodeAt(i)); };
  ascii(0, 'RIFF'); view.setUint32(4, buffer.byteLength - 8, true); ascii(8, 'WAVE'); ascii(12, 'fmt '); view.setUint32(16, 16, true);
  view.setUint16(20, 1, true); view.setUint16(22, channels.length, true); view.setUint32(24, sampleRate, true); view.setUint32(28, sampleRate * channels.length * 2, true); view.setUint16(32, channels.length * 2, true); view.setUint16(34, 16, true); ascii(36, 'data'); view.setUint32(40, buffer.byteLength - 44, true);
  for (let frame = 0, i = 44; frame < frames; frame++) for (const channel of channels) {
    const raw = channel[frame]; const sample = Number.isFinite(raw) ? Math.max(-1, Math.min(1, raw)) : 0;
    view.setInt16(i, sample < 0 ? Math.round(sample * 32768) : Math.round(sample * 32767), true); i += 2;
  }
  return buffer;
}
