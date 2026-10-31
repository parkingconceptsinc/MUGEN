import { encodeAudioLabWav } from './audioLabModel';
import { processVoice } from './voicePitch';
import type { VoiceWorkerRequest, VoiceWorkerResponse } from './voiceRuntime';

type WorkerPort = { postMessage(data: VoiceWorkerResponse, transfer?: Transferable[]): void; onmessage: ((event: MessageEvent<VoiceWorkerRequest>) => void) | null };
const port = globalThis as unknown as WorkerPort;
port.onmessage = ({ data }) => {
  if (data.type !== 'render') return;
  try {
    let previous = -1;
    const rendered = processVoice(data.channels, data.sampleRate, data.settings, value => {
      if (value - previous >= 0.01 || value === 1) { previous = value; port.postMessage({ type: 'progress', progress: value * 0.96 }); }
    });
    const wav = encodeAudioLabWav(rendered.channels, data.sampleRate);
    port.postMessage({ type: 'complete', wav, stats: rendered.stats }, [wav]);
  } catch (error) { port.postMessage({ type: 'error', message: error instanceof Error ? error.message : 'Voice processing failed. Your original is unchanged.' }); }
};
