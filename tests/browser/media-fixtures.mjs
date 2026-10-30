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
  const wav = Buffer.alloc(44 + frames * 2);
  wav.write('RIFF', 0); wav.writeUInt32LE(wav.length - 8, 4); wav.write('WAVEfmt ', 8);
  wav.writeUInt32LE(16, 16); wav.writeUInt16LE(1, 20); wav.writeUInt16LE(1, 22);
  wav.writeUInt32LE(rate, 24); wav.writeUInt32LE(rate * 2, 28);
  wav.writeUInt16LE(2, 32); wav.writeUInt16LE(16, 34); wav.write('data', 36);
  wav.writeUInt32LE(frames * 2, 40);
  for (let i = 0; i < frames; i++) {
    // Two tones exercise filters as well as dynamics; below clipping on input.
    const t = i / rate;
    const fade = Math.min(1, i / 480, (frames - i - 1) / 480);
    const sample = (0.42 * Math.sin(2 * Math.PI * 440 * t) + 0.26 * Math.sin(2 * Math.PI * 6000 * t)) * fade;
    wav.writeInt16LE(Math.round(sample * 32767), 44 + i * 2);
  }
  await writeFile(audio, wav);
  return { video, audio, originalWav: wav, frames, rate };
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
