import type { VoiceSettings, VoiceStats } from './voicePitch';

export const VOICE_LIMITS = { bytes: 32 * 1024 * 1024, seconds: 60, sampleRate: 48_000 } as const;
export type VoiceWorkerRequest = { type: 'render'; channels: Float32Array[]; sampleRate: number; settings: VoiceSettings };
export type VoiceWorkerResponse =
  | { type: 'progress'; progress: number }
  | { type: 'complete'; wav: ArrayBuffer; stats: VoiceStats }
  | { type: 'error'; message: string };
export type VoiceRender = { blob: Blob; stats: VoiceStats };

const abortError = () => new DOMException('Voice render cancelled. Your original audio is unchanged.', 'AbortError');
let rendering = false;

/** Check duration before Web Audio decodes compressed input into a potentially huge PCM buffer. */
function inspectDuration(file: File, signal: AbortSignal): Promise<number> {
  signal.throwIfAborted();
  return new Promise((resolve, reject) => {
    const element = document.createElement('audio');
    const url = URL.createObjectURL(file);
    let settled = false;
    const finish = (error?: Error, duration?: number) => {
      if (settled) return;
      settled = true;
      window.clearTimeout(timer);
      signal.removeEventListener('abort', cancel);
      element.onloadedmetadata = element.onerror = null;
      element.removeAttribute('src'); element.load(); URL.revokeObjectURL(url);
      if (error) reject(error); else resolve(duration!);
    };
    const cancel = () => finish(abortError());
    const timer = window.setTimeout(() => finish(new Error('Could not inspect the recording. Try a WAV, MP3 or AAC file.')), 15_000);
    signal.addEventListener('abort', cancel, { once: true });
    element.preload = 'metadata';
    element.onloadedmetadata = () => {
      const duration = element.duration;
      if (!Number.isFinite(duration) || duration < 0.05 || duration > VOICE_LIMITS.seconds + 0.001) finish(new Error('Choose a voice recording between 0.05 and 60 seconds long.'));
      else finish(undefined, duration);
    };
    element.onerror = () => finish(new Error('This browser could not read that recording. Try WAV, MP3 or AAC.'));
    element.src = url;
  });
}

export function validateVoiceBuffer(buffer: AudioBuffer): AudioBuffer {
  if (!Number.isFinite(buffer.duration) || buffer.duration < 0.05 || buffer.duration > VOICE_LIMITS.seconds + 0.001 || buffer.sampleRate !== VOICE_LIMITS.sampleRate || buffer.numberOfChannels < 1 || buffer.numberOfChannels > 2 || buffer.length > VOICE_LIMITS.sampleRate * VOICE_LIMITS.seconds + 1) {
    throw new Error('Voice tuning supports 48 kHz mono/stereo recordings up to 60 seconds.');
  }
  return buffer;
}

export async function decodeVoiceFile(file: File, signal: AbortSignal): Promise<AudioBuffer> {
  if (!file.size || file.size > VOICE_LIMITS.bytes) throw new Error('Choose a non-empty voice file up to 32 MiB.');
  await inspectDuration(file, signal);
  signal.throwIfAborted();
  const bytes = await file.arrayBuffer(); signal.throwIfAborted();
  const context = new OfflineAudioContext(2, 1, VOICE_LIMITS.sampleRate);
  const buffer = await context.decodeAudioData(bytes);
  signal.throwIfAborted();
  return validateVoiceBuffer(buffer);
}

/** A private, terminable worker keeps YIN analysis and pitch shifting off the UI thread. */
export function renderVoiceTuning(buffer: AudioBuffer, settings: VoiceSettings, signal: AbortSignal, onProgress: (value: number) => void = () => {}): Promise<VoiceRender> {
  signal.throwIfAborted();
  if (rendering) return Promise.reject(new Error('A voice render is already running. Cancel it before starting another.'));
  if (buffer.duration <= 0 || buffer.duration > VOICE_LIMITS.seconds + 0.001 || buffer.sampleRate !== VOICE_LIMITS.sampleRate || buffer.numberOfChannels < 1 || buffer.numberOfChannels > 2) return Promise.reject(new Error('Decode a mono/stereo voice file up to 60 seconds first.'));
  return new Promise((resolve, reject) => {
    let worker: Worker;
    try { worker = new Worker(new URL('./voiceWorker.ts', import.meta.url), { type: 'module' }); }
    catch { reject(new Error('This browser could not start voice processing. Reload MUGEN and try again.')); return; }
    rendering = true;
    let settled = false;
    const dispose = () => { worker.terminate(); signal.removeEventListener('abort', abort); rendering = false; };
    const fail = (error: Error) => { if (settled) return; settled = true; dispose(); reject(error); };
    const abort = () => fail(abortError());
    signal.addEventListener('abort', abort, { once: true });
    worker.onerror = () => fail(new Error('Voice processing stopped unexpectedly. Try a shorter recording.'));
    worker.onmessageerror = () => fail(new Error('Voice processing returned unreadable data. Try again.'));
    worker.onmessage = ({ data }: MessageEvent<VoiceWorkerResponse>) => {
      if (settled) return;
      if (data.type === 'progress') onProgress(Math.max(0, Math.min(1, data.progress)));
      else if (data.type === 'error') fail(new Error(data.message));
      else if (data.type === 'complete') {
        if (!(data.wav instanceof ArrayBuffer) || data.wav.byteLength < 44) return fail(new Error('Voice processing produced no WAV audio.'));
        const blob = new Blob([data.wav], { type: 'audio/wav' });
        settled = true; dispose(); onProgress(1); resolve({ blob, stats: data.stats });
      }
    };
    try {
      // Copies are transferred: the original AudioBuffer is never detached or modified.
      const channels = Array.from({ length: buffer.numberOfChannels }, (_, i) => new Float32Array(buffer.getChannelData(i)));
      const request: VoiceWorkerRequest = { type: 'render', channels, sampleRate: buffer.sampleRate, settings };
      worker.postMessage(request, channels.map(channel => channel.buffer));
    } catch (error) { fail(error instanceof Error ? error : new Error('Could not transfer voice data to the processing worker.')); }
  });
}
