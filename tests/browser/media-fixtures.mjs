import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

// Synthetic, deterministic media: never access microphones, webcams or user files.
export async function createMediaFixtures(directory) {
  await mkdir(directory, { recursive: true });
  const video = join(directory, 'mugen-fixture.mp4');
  execFileSync('ffmpeg', [
    '-hide_banner', '-loglevel', 'error', '-y',
    '-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=24:duration=2',
    '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000:duration=2',
    '-c:v', 'libx264', '-preset', 'ultrafast', '-pix_fmt', 'yuv420p',
    '-c:a', 'aac', '-shortest', '-movflags', '+faststart', video,
  ], { timeout: 30_000, stdio: 'pipe' });
  const audio = join(directory, 'mugen-fixture.wav');
  const rate = 48_000;
  const frames = rate * 2;
  const wav = syntheticPcmWav({ frequencies: [440, 6000], amplitudes: [0.42, 0.26] });
  await writeFile(audio, wav);
  const voice = join(directory, 'mugen-detuned-voice.wav');
  const voiceWav = syntheticPcmWav({ frequencies: [430], amplitudes: [0.5] });
  await writeFile(voice, voiceWav);
  return { video, audio, voice, voiceWav, originalWav: wav, frames, rate };
}

export function probeMedia(path) {
  return JSON.parse(execFileSync('ffprobe', [
    '-v', 'error', '-show_entries', 'format=duration:stream=codec_type,width,height,sample_rate,channels',
    '-of', 'json', path,
  ], { encoding: 'utf8', timeout: 15_000 }));
}

export function readPcmWav(bytes) {
  if (bytes.toString('ascii', 0, 4) !== 'RIFF' || bytes.toString('ascii', 8, 12) !== 'WAVE') throw Error('Export is not a RIFF/WAVE file');
  let fmt;
  let pcm;
  for (let offset = 12; offset + 8 <= bytes.length;) {
    const kind = bytes.toString('ascii', offset, offset + 4);
    const length = bytes.readUInt32LE(offset + 4);
    const dataStart = offset + 8;
    if (dataStart + length > bytes.length) throw Error('WAV chunk extends beyond file');
    if (kind === 'fmt ') {
      if (length < 16) throw Error('WAV format chunk is truncated');
      fmt = { encoding: bytes.readUInt16LE(dataStart), channels: bytes.readUInt16LE(dataStart + 2), rate: bytes.readUInt32LE(dataStart + 4), bits: bytes.readUInt16LE(dataStart + 14) };
    }
    if (kind === 'data') pcm = bytes.subarray(dataStart, dataStart + length);
    offset = dataStart + length + (length % 2);
  }
  if (!fmt || !pcm) throw Error('WAV format/data chunk is missing');
  if (fmt.encoding !== 1 || fmt.bits !== 16) throw Error('Expected 16-bit PCM WAV');
  const frames = pcm.length / (fmt.channels * 2);
  if (!Number.isInteger(frames) || frames <= 0) throw Error('Invalid WAV frame count');
  let energy = 0;
  let peak = 0;
  for (let i = 0; i < pcm.length; i += 2) {
    const value = pcm.readInt16LE(i) / 32768;
    energy += value * value;
    peak = Math.max(peak, Math.abs(value));
  }
  return { ...fmt, frames, duration: frames / fmt.rate, rms: Math.sqrt(energy / (pcm.length / 2)), peak, pcm };
}

/** Deterministic PCM16 signal fixture independent of the app's DSP implementation. */
export function syntheticPcmWav({ frequencies = [440], amplitudes = [0.5], duration = 2, rate = 48000 } = {}) {
  if (frequencies.length !== amplitudes.length || duration <= 0 || frequencies.some((frequency) => frequency <= 0 || frequency >= rate / 2)) throw Error('Invalid synthetic signal');
  const frames = Math.round(rate * duration);
  const wav = Buffer.alloc(44 + frames * 2);
  wav.write('RIFF', 0); wav.writeUInt32LE(wav.length - 8, 4); wav.write('WAVEfmt ', 8);
  wav.writeUInt32LE(16, 16); wav.writeUInt16LE(1, 20); wav.writeUInt16LE(1, 22);
  wav.writeUInt32LE(rate, 24); wav.writeUInt32LE(rate * 2, 28);
  wav.writeUInt16LE(2, 32); wav.writeUInt16LE(16, 34); wav.write('data', 36);
  wav.writeUInt32LE(frames * 2, 40);
  for (let i = 0; i < frames; i++) {
    const time = i / rate;
    const fade = Math.min(1, i / (rate * 0.01), (frames - i - 1) / (rate * 0.01));
    const sample = frequencies.reduce((sum, frequency, index) => sum + amplitudes[index] * Math.sin(2 * Math.PI * frequency * time), 0) * fade;
    wav.writeInt16LE(Math.round(Math.max(-1, Math.min(1, sample)) * 32767), 44 + i * 2);
  }
  return wav;
}
export function monoPcmSegment(wav, startSeconds, endSeconds) {
  const start = Math.max(0, Math.floor(startSeconds * wav.rate));
  const end = Math.min(wav.frames, Math.floor(endSeconds * wav.rate));
  if (end <= start) throw Error('Empty sound analysis segment');
  const samples = new Float64Array(end - start);
  for (let frame = start; frame < end; frame++) {
    let sum = 0;
    for (let channel = 0; channel < wav.channels; channel++) sum += wav.pcm.readInt16LE((frame * wav.channels + channel) * 2) / 32768;
    samples[frame - start] = sum / wav.channels;
  }
  return samples;
}
export function segmentRms(wav, startSeconds, endSeconds) {
  const samples = monoPcmSegment(wav, startSeconds, endSeconds);
  return Math.sqrt(samples.reduce((sum, value) => sum + value * value, 0) / samples.length);
}
/** Normalized autocorrelation with a parabolic sub-sample peak; no app model code. */
export function estimateTone(wav, targetHz, startSeconds = 0.4, endSeconds = 1.4) {
  const samples = monoPcmSegment(wav, startSeconds, endSeconds);
  const minLag = Math.max(2, Math.floor(wav.rate / (targetHz * 1.2)));
  const maxLag = Math.ceil(wav.rate / (targetHz * 0.8));
  const count = samples.length - maxLag - 1;
  if (count < maxLag * 3) throw Error('Sound segment is too short for pitch analysis');
  const scores = new Map();
  let bestLag = minLag; let bestScore = -Infinity;
  for (let lag = minLag; lag <= maxLag; lag++) {
    let covariance = 0; let leftEnergy = 0; let rightEnergy = 0;
    for (let index = 0; index < count; index++) {
      const left = samples[index]; const right = samples[index + lag];
      covariance += left * right; leftEnergy += left * left; rightEnergy += right * right;
    }
    const score = leftEnergy && rightEnergy ? covariance / Math.sqrt(leftEnergy * rightEnergy) : -1;
    scores.set(lag, score);
    if (score > bestScore) { bestScore = score; bestLag = lag; }
  }
  if (bestScore < 0.5) throw Error(`No stable periodic tone found (confidence ${bestScore})`);
  const before = scores.get(bestLag - 1) ?? bestScore; const after = scores.get(bestLag + 1) ?? bestScore;
  const denominator = before - 2 * bestScore + after;
  const correction = denominator ? Math.max(-0.5, Math.min(0.5, 0.5 * (before - after) / denominator)) : 0;
  const frequency = wav.rate / (bestLag + correction);
  return { frequency, centsFromTarget: 1200 * Math.log2(frequency / targetHz), confidence: bestScore };
}
