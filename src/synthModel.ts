export type SynthSettings = {
  wave: number; octave: number; attack: number; decay: number; sustain: number; release: number;
  cutoff: number; resonance: number; detuneCents: number; blend: number; vibratoHz: number; vibratoDepth: number;
  volume: number; bpm: number; sequence: boolean; steps: number[];
};
export const SYNTH_DEFAULTS: SynthSettings = {
  wave: 0, octave: 4, attack: 0.015, decay: 0.18, sustain: 0.65, release: 0.25,
  cutoff: 6500, resonance: 0.707, detuneCents: 5, blend: 0.3, vibratoHz: 5, vibratoDepth: 0,
  volume: 0.35, bpm: 120, sequence: false, steps: [60,-1,64,-1,67,-1,64,-1,57,-1,60,-1,64,-1,67,-1],
};
const ranges: Record<Exclude<keyof SynthSettings, 'sequence' | 'steps'>, [number, number]> = {
  wave: [0,3], octave: [2,6], attack: [0.002,1], decay: [0.01,2], sustain: [0,1], release: [0.01,2],
  cutoff: [80,20000], resonance: [0.5,4], detuneCents: [0,25], blend: [0,1], vibratoHz: [0.1,10], vibratoDepth: [0,1], volume: [0,0.8], bpm: [40,240],
};
const record = (raw: unknown): Record<string, unknown> => raw && typeof raw === 'object' && !Array.isArray(raw) ? raw as Record<string, unknown> : {};
export function sanitizeSynth(raw: unknown): SynthSettings {
  const value = record(raw); const result = { ...SYNTH_DEFAULTS, steps: [...SYNTH_DEFAULTS.steps], sequence: false };
  for (const key of Object.keys(ranges) as Array<keyof typeof ranges>) {
    const candidate = key === 'detuneCents' && value[key] == null ? value.detune : value[key];
    const number = typeof candidate === 'number' && Number.isFinite(candidate) ? candidate : SYNTH_DEFAULTS[key];
    const [min,max] = ranges[key]; result[key] = Math.min(max, Math.max(min, key === 'wave' || key === 'octave' ? Math.round(number) : number));
  }
  if (Array.isArray(value.steps)) result.steps = Array.from({ length: 16 }, (_,i) => Number.isInteger((value.steps as unknown[])[i]) && ((value.steps as number[])[i] === -1 || ((value.steps as number[])[i] >= 24 && (value.steps as number[])[i] <= 108)) ? (value.steps as number[])[i] : -1);
  return result;
}
export function exportSynthPatch(settings: SynthSettings): string {
  return JSON.stringify({ format: 'mugen-synth', version: 1, settings: sanitizeSynth(settings) }, null, 2);
}
export function importSynthPatch(text: string): SynthSettings {
  if (new TextEncoder().encode(text).length > 16 * 1024) throw new Error('Synth patch exceeds 16 KiB.');
  const doc = record(JSON.parse(text)); const value = record(doc.settings);
  if (doc.format !== 'mugen-synth' || doc.version !== 1) throw new Error('Choose a MUGEN synth patch (version 1).');
  for (const key of Object.keys(ranges) as Array<keyof typeof ranges>) {
    const n = value[key]; const [min,max] = ranges[key];
    if (typeof n !== 'number' || !Number.isFinite(n) || n < min || n > max || ((key === 'wave' || key === 'octave') && !Number.isInteger(n))) throw new Error(`Invalid synth setting: ${key}.`);
  }
  if (typeof value.sequence !== 'boolean' || !Array.isArray(value.steps) || value.steps.length !== 16 || value.steps.some(n => !Number.isInteger(n) || (n !== -1 && n < 24) || n > 108)) throw new Error('Synth patch requires 16 valid notes or rests.');
  return sanitizeSynth(value);
}
export function synthNoteName(note: number): string { return note < 0 ? 'Rest' : `${['C','C♯','D','D♯','E','F','F♯','G','G♯','A','A♯','B'][note % 12]}${Math.floor(note / 12) - 1}`; }
export function synthNoteHz(note: number): number { return 440 * Math.pow(2, (note - 69) / 12); }
export function synthEnvelopeAt(seconds: number, settings: SynthSettings): number {
  if (seconds <= 0) return 0;
  if (seconds < settings.attack) return seconds / settings.attack;
  if (seconds < settings.attack + settings.decay) return 1 - (1 - settings.sustain) * (seconds - settings.attack) / settings.decay;
  return settings.sustain;
}
export type SynthNoteEvent = { note: number; start: number; duration: number; step: number };
export function synthSequencePlan(raw: SynthSettings, bars: number): { settings: SynthSettings; notes: SynthNoteEvent[]; stepSeconds: number; duration: number; frames: number } {
  if (!Number.isInteger(bars) || bars < 1 || bars > 8) throw new Error('Choose one to eight bars.');
  const settings = sanitizeSynth(raw); const stepSeconds = 60 / settings.bpm / 4;
  const notes = Array.from({ length: 16 * bars }, (_,i) => ({ note: settings.steps[i % 16], start: i * stepSeconds, duration: stepSeconds * 0.65, step: i % 16 })).filter(event => event.note >= 0);
  const duration = bars * 16 * stepSeconds + settings.release + 0.02;
  return { settings, notes, stepSeconds, duration, frames: Math.ceil(duration * 48000) };
}
export function synthFingerprint(settings: SynthSettings, bars: number) { return JSON.stringify({ settings: sanitizeSynth(settings), bars }); }
