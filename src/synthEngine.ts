import { encodeAudioLabWav } from './audioLabModel';
import { sanitizeSynth, synthEnvelopeAt, synthNoteHz, synthSequencePlan, type SynthSettings } from './synthModel';

type Voice = { start: number; releaseAt: number; releaseLevel: number; releaseDuration: number; end: number; patch: SynthSettings; oscillators: OscillatorNode[]; first: GainNode; second: GainNode; envelope: GainNode; disposed: boolean; stolen: boolean };
const waves: OscillatorType[] = ['sine','sawtooth','square','triangle'];
class SynthGraph {
  readonly context: BaseAudioContext;
  patch: SynthSettings;
  private filter: BiquadFilterNode;
  private output: GainNode;
  private protect: WaveShaperNode;
  private lfo: OscillatorNode;
  private vibrato: GainNode;
  private voices = new Set<Voice>();
  constructor(context: BaseAudioContext, patch: SynthSettings) {
    this.context = context; this.patch = sanitizeSynth(patch);
    this.filter = context.createBiquadFilter(); this.filter.type = 'lowpass'; this.output = context.createGain();
    this.protect = context.createWaveShaper(); const curve = new Float32Array(2049);
    for (let i = 0; i < curve.length; i++) curve[i] = Math.max(-0.95, Math.min(0.95, i / (curve.length - 1) * 2 - 1));
    this.protect.curve = curve;
    this.filter.connect(this.output); this.output.connect(this.protect); this.protect.connect(context.destination);
    this.lfo = context.createOscillator(); this.vibrato = context.createGain(); this.lfo.connect(this.vibrato); this.lfo.start();
    this.setPatch(this.patch, true);
  }
  setPatch(raw: SynthSettings, initial = false) {
    this.patch = sanitizeSynth(raw); const s = this.patch; const at = this.context.currentTime;
    const param = (p: AudioParam, value: number) => { if (initial) p.value = value; else p.setTargetAtTime(value, at, 0.015); };
    param(this.filter.frequency, Math.min(s.cutoff, this.context.sampleRate * 0.48)); param(this.filter.Q, s.resonance); param(this.output.gain, s.volume);
    param(this.lfo.frequency, s.vibratoHz); param(this.vibrato.gain, s.vibratoDepth * 100);
    for (const voice of this.voices) {
      if (voice.disposed || voice.start > at) continue;
      voice.oscillators.forEach(osc => osc.type = waves[s.wave]);
      param(voice.oscillators[1].detune, s.detuneCents); param(voice.first.gain, 1 - s.blend); param(voice.second.gain, s.blend);
    }
  }
  private levelAt(voice: Voice, at: number) {
    if (at < voice.releaseAt) return synthEnvelopeAt(at - voice.start, voice.patch);
    return Math.max(0, voice.releaseLevel * (1 - (at - voice.releaseAt) / voice.releaseDuration));
  }
  release(voice: Voice, at = this.context.currentTime, duration = this.patch.release) {
    if (voice.disposed || at >= voice.end) return;
    at = Math.max(at, voice.start);
    const level = this.levelAt(voice, at);
    voice.envelope.gain.cancelScheduledValues(at);
    // The linear ramp preserves an attack/decay that ended before this release.
    voice.envelope.gain.linearRampToValueAtTime(level * 0.18, at);
    voice.envelope.gain.setValueAtTime(level * 0.18, at);
    voice.envelope.gain.linearRampToValueAtTime(0, at + duration);
    voice.releaseAt = at; voice.releaseLevel = level; voice.releaseDuration = duration; voice.end = at + duration;
    for (const oscillator of voice.oscillators) { try { oscillator.stop(voice.end + 0.002); } catch {} }
  }
  noteOn(note: number, start = this.context.currentTime, duration?: number) {
    if (!Number.isInteger(note) || note < 24 || note > 108) throw new Error('Choose a MIDI note from 24 to 108.');
    const current = [...this.voices].filter(voice => !voice.disposed && !voice.stolen && voice.end > start && voice.start <= start);
    if (current.length >= 8) { const oldest = current.sort((a,b) => a.start - b.start)[0]; oldest.stolen = true; this.release(oldest, start, 0.005); }
    const ctx = this.context; const s = this.patch;
    const oscillators = [ctx.createOscillator(),ctx.createOscillator()]; const first = ctx.createGain(); const second = ctx.createGain(); const envelope = ctx.createGain();
    first.gain.value = 1 - s.blend; second.gain.value = s.blend;
    oscillators.forEach((osc,i) => { osc.type = waves[s.wave]; osc.frequency.value = synthNoteHz(note); osc.detune.value = i ? s.detuneCents : 0; this.vibrato.connect(osc.detune); osc.connect(i ? second : first); });
    first.connect(envelope); second.connect(envelope); envelope.connect(this.filter);
    envelope.gain.setValueAtTime(0,start); envelope.gain.linearRampToValueAtTime(0.18,start+s.attack); envelope.gain.linearRampToValueAtTime(s.sustain*0.18,start+s.attack+s.decay);
    const voice: Voice = { start, releaseAt: Infinity, releaseLevel: 0, releaseDuration: s.release, end: Infinity, patch: s, oscillators, first, second, envelope, disposed: false, stolen: false };
    this.voices.add(voice);
    oscillators[0].onended = () => {
      if (voice.disposed) return; voice.disposed = true; this.voices.delete(voice);
      for (const osc of oscillators) { try { this.vibrato.disconnect(osc.detune); } catch {} osc.disconnect(); }
      first.disconnect(); second.disconnect(); envelope.disconnect();
    };
    oscillators.forEach(osc => osc.start(start));
    if (duration != null) this.release(voice, start + duration, s.release);
    return voice;
  }
  allNotesOff(fast = false) { for (const voice of this.voices) this.release(voice, this.context.currentTime, fast ? 0.015 : this.patch.release); }
  dispose() {
    for (const voice of this.voices) {
      voice.disposed = true; voice.oscillators.forEach(osc => { osc.onended = null; try { osc.stop(); } catch {} osc.disconnect(); }); voice.first.disconnect(); voice.second.disconnect(); voice.envelope.disconnect();
    }
    this.voices.clear(); try { this.lfo.stop(); } catch {}
    this.lfo.disconnect(); this.vibrato.disconnect(); this.filter.disconnect(); this.output.disconnect(); this.protect.disconnect();
  }
}
export class LiveSynth {
  private graph: SynthGraph;
  private context: AudioContext;
  private held = new Map<string, Voice>();
  private timer: ReturnType<typeof setInterval> | null = null;
  private sequenceStart = 0;
  private nextStep = 0;
  constructor(patch: SynthSettings) { this.context = new AudioContext({ sampleRate: 48000 }); this.graph = new SynthGraph(this.context, patch); }
  async resume() { if (this.context.state === 'closed') throw new Error('Synth audio session has closed.'); await this.context.resume(); }
  get running() { return this.timer != null; }
  get step() { return this.running ? Math.max(0,Math.floor((this.context.currentTime-this.sequenceStart) / (60/this.graph.patch.bpm/4))) % 16 : -1; }
  setPatch(patch: SynthSettings) { const wasRunning = this.running; if (wasRunning) this.stopSequence(); this.graph.setPatch(patch); if (wasRunning) this.startSequence(); }
  noteOn(id: string, note: number) { this.noteOff(id); this.held.set(id,this.graph.noteOn(note)); }
  noteOff(id: string) { const voice = this.held.get(id); if (voice) this.graph.release(voice); this.held.delete(id); }
  startSequence() {
    this.stopSequence(); this.sequenceStart = this.context.currentTime + 0.03; this.nextStep = 0;
    const schedule = () => {
      const s = this.graph.patch; const seconds = 60/s.bpm/4;
      while (this.sequenceStart + this.nextStep*seconds < this.context.currentTime + 0.12) {
        const note = s.steps[this.nextStep%16]; if (note >= 0) this.graph.noteOn(note,this.sequenceStart+this.nextStep*seconds,seconds*0.65); this.nextStep++;
      }
    };
    schedule(); this.timer = setInterval(schedule,25);
  }
  stopSequence() { if (this.timer) clearInterval(this.timer); this.timer = null; this.graph.allNotesOff(true); this.held.clear(); }
  stopAll() { this.stopSequence(); this.graph.allNotesOff(true); this.held.clear(); }
  async close() { this.stopAll(); this.graph.dispose(); if (this.context.state !== 'closed') await this.context.close(); }
}
export async function renderSynthWav(patch: SynthSettings, bars: number, options: { signal?: AbortSignal; onProgress?: (progress:number)=>void } = {}): Promise<Blob> {
  options.signal?.throwIfAborted(); const plan = synthSequencePlan(patch,bars);
  const context = new OfflineAudioContext(2,plan.frames,48000); const graph = new SynthGraph(context,plan.settings);
  for (const event of plan.notes) graph.noteOn(event.note,event.start,event.duration);
  // Suspended checkpoints report rendered audio time, never wall-clock guesses.
  const checkpoints = typeof context.suspend === 'function' ? Array.from({length:7},(_,i) => context.suspend(Math.floor(plan.frames*(i+1)/8)/48000)) : [];
  try {
    options.onProgress?.(0); const completed = context.startRendering();
    // A rendering error must reject the job even if a scheduled suspension never arrives.
    const finished = completed.then(buffer => ({ done: true as const, buffer }));
    for (let i = 0; i < checkpoints.length; i++) {
      const next = await Promise.race([checkpoints[i].then(() => ({ done: false as const })), finished]);
      if (next.done) break;
      options.onProgress?.((i+1)/8); await context.resume();
    }
    const buffer = await completed; options.signal?.throwIfAborted();
    const output = new Blob([encodeAudioLabWav([buffer.getChannelData(0),buffer.getChannelData(1)],48000)],{type:'audio/wav'});
    options.onProgress?.(1); return output;
  } finally { for (const checkpoint of checkpoints) void checkpoint.catch(() => {}); graph.dispose(); }
}
