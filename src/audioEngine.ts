export const EQ_BANDS = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];
export const EQ_RANGE = 12;

export const EQ_PRESETS: Record<string, number[]> = {
  Flat: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
  "Bass Boost": [7, 6, 5, 3, 1, 0, 0, 0, 0, 0],
  "Bass Reducer": [-6, -5, -4, -2, -1, 0, 0, 0, 0, 0],
  "Treble Boost": [0, 0, 0, 0, 0, 1, 3, 5, 6, 7],
  Rock: [5, 4, 3, 1, -1, -1, 1, 3, 4, 5],
  Pop: [-1, 1, 3, 4, 3, 1, 0, -1, -1, -1],
  Jazz: [3, 2, 1, 2, -1, -1, 0, 1, 2, 3],
  Classical: [4, 3, 2, 1, -1, -1, 0, 2, 3, 4],
  "Hip-Hop": [6, 5, 2, 3, -1, -1, 1, 0, 2, 3],
  Electronic: [5, 4, 1, 0, -2, 2, 1, 1, 4, 5],
  Dance: [4, 6, 4, 0, 0, -2, -2, -2, 0, 0],
  Latin: [3, 2, 0, 0, -1, -1, -1, 0, 3, 4],
  Reggaeton: [6, 5, 3, 0, -1, 0, 1, 2, 3, 3],
  Vocal: [-2, -3, -2, 1, 4, 4, 3, 1, 0, -2],
  Acoustic: [4, 4, 3, 1, 2, 1, 3, 3, 3, 2],
  Loudness: [6, 4, 0, 0, -2, 0, -1, -5, 5, 1],
  Headphones: [4, 6, 3, -1, -2, 1, 3, 6, 7, 7],
  "Small Speakers": [-4, -2, 0, 3, 4, 4, 3, 2, 1, 0],
};

type AudioOptions = { enabled: boolean; bands: number[]; preamp: number; balance: number; boost: number };
import { DEFAULT_AUDIO_LAB, DEFAULT_AUDIO_LAB_EQ, audioLabHeadroomDb, audioLabTailSeconds, encodeAudioLabWav, sanitizeAudioLab, sanitizeAudioLabEq, type AudioLabSettings } from "./audioLabModel";

type Graph = ReturnType<typeof createAudioLabGraph>;
let graph: Graph | null = null;
const graphs = new Set<Graph>();
const sources = new WeakMap<HTMLMediaElement, MediaElementAudioSourceNode>();
let latestEq: AudioOptions = DEFAULT_AUDIO_LAB_EQ;
let lab = loadAudioLabSettings();
let bypass = false;
const listeners = new Set<() => void>();
function loadAudioLabSettings(): AudioLabSettings {
  try { return sanitizeAudioLab(JSON.parse(localStorage.getItem("mortimer:audioLab:v1") || "null")); }
  catch { return { ...DEFAULT_AUDIO_LAB }; }
}
export function getAudioLabSettings() { return lab; }
export function getAudioLabBypass() { return bypass; }
export function subscribeAudioLab(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener); }; }
export function setAudioLabSettings(settings: AudioLabSettings): boolean {
  lab = sanitizeAudioLab(settings);
  let saved = true;
  try { localStorage.setItem("mortimer:audioLab:v1", JSON.stringify(lab)); } catch { saved = false; }
  for (const item of graphs) item.update(latestEq, lab, bypass);
  for (const listener of listeners) listener();
  return saved;
}
export function setAudioLabBypass(value: boolean) {
  bypass = value;
  for (const item of graphs) item.update(latestEq, lab, bypass);
  for (const listener of listeners) listener();
}

/** Same graph is used for HTML playback, decoded fallback and offline WAV. */
export function createAudioLabGraph(ctx: BaseAudioContext, eqInput: AudioOptions, settingsInput: AudioLabSettings, compareOriginal = false) {
  const input = ctx.createGain(); const process = ctx.createGain(); const original = ctx.createGain(); const preamp = ctx.createGain();
  const filters = EQ_BANDS.map((frequency, i) => {
    const filter = ctx.createBiquadFilter();
    filter.type = i === 0 ? "lowshelf" : i === EQ_BANDS.length - 1 ? "highshelf" : "peaking";
    filter.frequency.value = Math.min(frequency, ctx.sampleRate * 0.48); filter.Q.value = 1.1; return filter;
  });
  const highPass = ctx.createBiquadFilter(); highPass.type = "highpass";
  const lowPass = ctx.createBiquadFilter(); lowPass.type = "lowpass";
  const compressor = ctx.createDynamicsCompressor(); const makeup = ctx.createGain(); const panner = ctx.createStereoPanner();
  const mix = ctx.createGain(); const delay = ctx.createDelay(1.5); const feedback = ctx.createGain(); const delayWet = ctx.createGain();
  const reverb = ctx.createConvolver(); const reverbWet = ctx.createGain(); const limiter = ctx.createDynamicsCompressor();
  const analyser = ctx.createAnalyser(); analyser.fftSize = 2048; analyser.smoothingTimeConstant = 0.75;
  input.connect(original); original.connect(analyser); input.connect(process); process.connect(preamp); preamp.connect(filters[0]);
  filters.reduce((prev, next) => { prev.connect(next); return next; });
  filters[filters.length - 1].connect(highPass); highPass.connect(lowPass); lowPass.connect(compressor); compressor.connect(makeup); makeup.connect(panner);
  panner.connect(mix); panner.connect(delay); delay.connect(feedback); feedback.connect(delay); delay.connect(delayWet); delayWet.connect(mix);
  panner.connect(reverb); reverb.connect(reverbWet); reverbWet.connect(mix); mix.connect(limiter); limiter.connect(analyser); analyser.connect(ctx.destination);
  let impulseKey = -1;
  let initialized = false;
  const smooth = (param: AudioParam, value: number) => { if (initialized) param.setTargetAtTime(value, ctx.currentTime, 0.015); else param.value = value; };
  function update(eqRaw: AudioOptions, raw: AudioLabSettings, originalOnly: boolean) {
    const eq = sanitizeAudioLabEq(eqRaw); const s = sanitizeAudioLab(raw); const on = s.enabled;
    smooth(original.gain, originalOnly ? 1 : 0); smooth(process.gain, originalOnly ? 0 : 1);
    filters.forEach((filter, i) => smooth(filter.gain, eq.enabled ? eq.bands[i] : 0));
    smooth(preamp.gain, Math.pow(10, ((eq.enabled ? eq.preamp : 0) - audioLabHeadroomDb(s, eq)) / 20) * eq.boost);
    smooth(panner.pan, eq.balance);
    smooth(highPass.frequency, on && s.highPass ? s.highPassHz : 1); smooth(highPass.Q, 0.707);
    smooth(lowPass.frequency, Math.min(on && s.lowPass ? s.lowPassHz : ctx.sampleRate * 0.499, ctx.sampleRate * 0.499)); smooth(lowPass.Q, 0.707);
    smooth(compressor.threshold, on && s.compressor ? s.threshold : 0); smooth(compressor.ratio, on && s.compressor ? s.ratio : 1);
    smooth(compressor.knee, 6); smooth(compressor.attack, s.attackMs / 1000); smooth(compressor.release, s.releaseMs / 1000);
    smooth(makeup.gain, on && s.compressor ? Math.pow(10, s.makeup / 20) : 1);
    smooth(delay.delayTime, s.delayMs / 1000); smooth(feedback.gain, on ? s.delayFeedback : 0); smooth(delayWet.gain, on ? s.delayMix : 0);
    smooth(reverbWet.gain, on ? s.reverbMix : 0);
    const key = Math.round(s.room * 10);
    if (key !== impulseKey && on && s.reverbMix > 0) {
      impulseKey = key;
      const length = Math.ceil(ctx.sampleRate * (0.3 + key / 10 * 2.7)); const impulse = ctx.createBuffer(2, length, ctx.sampleRate);
      let seed = 0x12345678;
      for (let channel = 0; channel < 2; channel++) {
        const values = impulse.getChannelData(channel);
        for (let i = 0; i < length; i++) { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; values[i] = (seed / 2147483648 - 1) * Math.pow(1 - i / length, 3); }
      }
      reverb.buffer = impulse;
    }
    smooth(limiter.threshold, on ? s.ceiling : -1); smooth(limiter.knee, 0);
    smooth(limiter.ratio, !on || s.limiter ? 20 : 1); smooth(limiter.attack, 0.001); smooth(limiter.release, 0.1);
  }
  update(eqInput, settingsInput, compareOriginal);
  initialized = true;
  return { ctx, input, analyser, compressor, update, disconnect: () => { for (const node of [input, process, original, preamp, ...filters, highPass, lowPass, compressor, makeup, panner, mix, delay, feedback, delayWet, reverb, reverbWet, limiter, analyser]) node.disconnect(); } };
}
function registerGraph(item: Graph) {
  graphs.add(item);
  item.ctx.addEventListener("statechange", () => { if (item.ctx.state === "closed") { item.disconnect(); graphs.delete(item); } });
  return item;
}
export function initEngine(): boolean {
  if (graph) return true;
  const Ctor = window.AudioContext ?? (window as Window & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  if (!Ctor) return false;
  graph = registerGraph(createAudioLabGraph(new Ctor(), latestEq, lab, bypass));
  return true;
}
export function attachElement(el: HTMLMediaElement | null) {
  if (!graph || !el || sources.has(el)) return;
  try { const source = (graph.ctx as AudioContext).createMediaElementSource(el); source.connect(graph.input); sources.set(el, source); } catch {}
}
/** Connect a decoded source/gain in its own context; do not also connect it to destination. */
export function connectAudioLabNode(node: AudioNode) {
  const item = registerGraph(createAudioLabGraph(node.context, latestEq, lab, bypass)); node.connect(item.input);
  return () => { node.disconnect(item.input); item.disconnect(); graphs.delete(item); };
}
export function resumeEngine() {
  if (graph && graph.ctx.state !== "running") void (graph.ctx as AudioContext).resume().catch(() => {});
}
export function applyAudio(options: AudioOptions) {
  latestEq = sanitizeAudioLabEq(options);
  for (const item of graphs) item.update(latestEq, lab, bypass);
}
export function readAudioLabMeters() {
  const running = [...graphs].filter(g => g.ctx.state === "running");
  const bins = new Float32Array(64); let peak = 0; let energy = 0; let samples = 0; let reduction = 0;
  for (const item of running) {
    const time = new Float32Array(item.analyser.fftSize); item.analyser.getFloatTimeDomainData(time);
    const frequencies = new Float32Array(item.analyser.frequencyBinCount); item.analyser.getFloatFrequencyData(frequencies);
    for (const value of time) { peak = Math.max(peak, Math.abs(value)); energy += value * value; samples++; }
    for (let i = 0; i < bins.length; i++) { const hz = 20 * Math.pow(1000, i / (bins.length - 1)); const index = Math.min(frequencies.length - 1, Math.round(hz / (item.ctx.sampleRate / 2) * frequencies.length)); bins[i] = Math.max(bins[i], Math.max(0, Math.min(1, (frequencies[index] + 90) / 90))); }
    reduction = Math.min(reduction, item.compressor.reduction);
  }
  return { active: running.length > 0, peakDb: peak ? 20 * Math.log10(peak) : -90, rmsDb: energy && samples ? 10 * Math.log10(energy / samples) : -90, reductionDb: reduction, bins };
}
export const AUDIO_LAB_MAX_BYTES = 64 * 1024 * 1024;
export async function decodeAudioLabFile(file: Blob, signal?: AbortSignal): Promise<AudioBuffer> {
  if (file.size > AUDIO_LAB_MAX_BYTES) throw new Error("Audio file exceeds 64 MiB. Choose a shorter recording.");
  signal?.throwIfAborted(); const bytes = await file.arrayBuffer(); signal?.throwIfAborted();
  const decoder = new OfflineAudioContext(2, 1, 48000);
  const buffer = await decoder.decodeAudioData(bytes); signal?.throwIfAborted();
  if (!Number.isFinite(buffer.duration) || buffer.duration <= 0 || buffer.duration > 300) throw new Error("Choose an audio file up to five minutes long.");
  return buffer;
}
export async function renderAudioLabWav(buffer: AudioBuffer, eq: AudioOptions, settings: AudioLabSettings, signal?: AbortSignal): Promise<Blob> {
  signal?.throwIfAborted();
  if (buffer.duration <= 0 || buffer.duration > 300) throw new Error("Choose an audio file up to five minutes long.");
  const s = sanitizeAudioLab(settings); const frames = Math.ceil((buffer.duration + audioLabTailSeconds(s) + 0.05) * 48000);
  const context = new OfflineAudioContext(2, frames, 48000); const chain = createAudioLabGraph(context, eq, s);
  const source = context.createBufferSource(); source.buffer = buffer; source.connect(chain.input); source.start();
  try {
    const rendered = await context.startRendering(); signal?.throwIfAborted();
    return new Blob([encodeAudioLabWav([rendered.getChannelData(0), rendered.getChannelData(1)], rendered.sampleRate)], { type: "audio/wav" });
  } finally { source.disconnect(); chain.disconnect(); }
}
