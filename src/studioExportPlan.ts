import type { StudioClip, StudioProject } from "./studioModel";

export const STUDIO_EXPORT_LIMITS = { clips: 32, duration: 30 * 60, sourceBytes: 384 * 1024 * 1024, outputBytes: 384 * 1024 * 1024 } as const;
const n = (value: number) => Number(value.toFixed(6)).toString();
const even = (value: number) => Math.max(2, Math.round(value / 2) * 2);

/** All inputs to filter expressions are validated numbers, never user-provided strings. */
export function canvasFor(project: StudioProject): { width: number; height: number } {
  const first = project.clips[0];
  const source = project.sources.find((s) => s.id === first?.sourceId);
  let ratio: number;
  switch (project.aspect) {
    case "16:9": ratio = 16 / 9; break;
    case "9:16": ratio = 9 / 16; break;
    case "1:1": ratio = 1; break;
    case "source": {
      if (!source || !Number.isFinite(source.width) || !Number.isFinite(source.height) || source.width <= 0 || source.height <= 0) throw new Error("The first clip has no video dimensions. Choose a 16:9, 9:16 or square canvas.");
      ratio = first.rotation === 90 || first.rotation === 270 ? source.height / source.width : source.width / source.height;
      break;
    }
    default: throw new Error("Choose a supported export canvas.");
  }
  if (project.height !== 720 && project.height !== 1080) throw new Error("Choose 720p or 1080p export quality.");
  const height = project.height;
  const width = even(height * ratio);
  if (!Number.isFinite(width) || width > 3840) throw new Error("This source canvas is too wide for browser export. Choose 16:9, 9:16 or square.");
  return { width, height };
}

export function audioTempoFilters(speed: number): string[] {
  if (!Number.isFinite(speed) || speed < 0.25 || speed > 4) throw new Error("Clip speed must be between 0.25× and 4×.");
  const filters: string[] = [];
  while (speed < 0.5) { filters.push("atempo=0.5"); speed /= 0.5; }
  while (speed > 2) { filters.push("atempo=2"); speed /= 2; }
  if (speed !== 1 || !filters.length) filters.push(`atempo=${n(speed)}`);
  return filters;
}

export function clipVideoFilters(clip: StudioClip, canvas: { width: number; height: number }): string[] {
  const values = [clip.in, clip.out, clip.speed, clip.volume, clip.brightness, clip.contrast, clip.saturation, canvas.width, canvas.height];
  if (!values.every(Number.isFinite) || clip.in < 0 || clip.out <= clip.in || clip.speed < 0.25 || clip.speed > 4 || clip.volume < 0 || clip.volume > 2 || clip.brightness < -1 || clip.brightness > 1 || clip.contrast < 0 || clip.contrast > 2 || clip.saturation < 0 || clip.saturation > 2 || ![0, 90, 180, 270].includes(clip.rotation)) throw new Error("Invalid clip settings. Check trim, speed and effects before exporting.");
  if (![canvas.width, canvas.height].every((v) => Number.isInteger(v) && v > 0 && v <= 3840 && v % 2 === 0)) throw new Error("Export dimensions must be positive, even values up to 3840 pixels.");
  const filters = [`setpts=(PTS-STARTPTS)/${n(clip.speed)}`];
  if (clip.rotation === 90) filters.push("transpose=1");
  if (clip.rotation === 180) filters.push("hflip", "vflip");
  if (clip.rotation === 270) filters.push("transpose=2");
  if (clip.flip) filters.push("hflip");
  filters.push(`eq=brightness=${n(clip.brightness)}:contrast=${n(clip.contrast)}:saturation=${n(clip.saturation)}`);
  filters.push(`scale=${canvas.width}:${canvas.height}:force_original_aspect_ratio=decrease:force_divisible_by=2`, `pad=${canvas.width}:${canvas.height}:(ow-iw)/2:(oh-ih)/2:color=black`, "setsar=1", "fps=30", "format=yuv420p");
  return filters;
}

export function buildClipCommand(clip: StudioClip, canvas: { width: number; height: number }, hasAudio: boolean, input: string, output: string): string[] {
  if (!/^source-\d+\.input$/.test(input) || !/^clip-\d+\.mp4$/.test(output)) throw new Error("Invalid export working-file names.");
  const video = clipVideoFilters(clip, canvas);
  const duration = (clip.out - clip.in) / clip.speed;
  const command = ["-hide_banner", "-y", "-ss", n(clip.in), "-t", n(clip.out - clip.in), "-i", input];
  if (!hasAudio) command.push("-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=48000");
  command.push("-map", "0:v:0", "-map", hasAudio ? "0:a:0" : "1:a:0", "-vf", video.join(","));
  const audio = ["asetpts=PTS-STARTPTS", ...audioTempoFilters(hasAudio ? clip.speed : 1), `volume=${clip.mute ? "0" : n(clip.volume)}`, "aresample=48000", "apad"];
  command.push("-af", audio.join(","), "-t", n(duration), "-c:v", "libx264", "-preset", "ultrafast", "-crf", "21", "-pix_fmt", "yuv420p", "-r", "30", "-video_track_timescale", "90000", "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2", "-metadata:s:v:0", "rotate=0", "-movflags", "+faststart", output);
  return command;
}

export function concatManifest(count: number): string {
  if (!Number.isInteger(count) || count < 1 || count > STUDIO_EXPORT_LIMITS.clips) throw new Error("Export needs between 1 and 32 clips.");
  return Array.from({ length: count }, (_, i) => `file 'clip-${i}.mp4'`).join("\n") + "\n";
}

export function checkExportBudget(project: StudioProject, media: Map<string, Blob>): void {
  if (!project.clips.length || project.clips.length > STUDIO_EXPORT_LIMITS.clips) throw new Error("Browser export supports up to 32 clips per project.");
  let duration = 0;
  const used = new Set<string>();
  for (const clip of project.clips) {
    if (!Number.isFinite(clip.in) || !Number.isFinite(clip.out) || !Number.isFinite(clip.speed) || clip.in < 0 || clip.out <= clip.in || clip.speed < 0.25 || clip.speed > 4) throw new Error("Invalid clip duration or speed.");
    const source = project.sources.find((s) => s.id === clip.sourceId);
    const blob = media.get(clip.sourceId);
    if (!source || !blob || blob.size === 0) throw new Error("A project source is missing. Reimport the original video before exporting.");
    if (source.size !== blob.size) throw new Error(`The saved video «${source.name}» is incomplete. Reimport its original file.`);
    if (clip.out > source.duration + 0.001) throw new Error("A clip extends past its original video.");
    used.add(clip.sourceId);
    duration += (clip.out - clip.in) / clip.speed;
  }
  if (!Number.isFinite(duration) || duration > STUDIO_EXPORT_LIMITS.duration) throw new Error("Browser export is limited to 30 minutes. Shorten or split the project.");
  const bytes = [...used].reduce((sum, id) => sum + media.get(id)!.size, 0);
  if (bytes > STUDIO_EXPORT_LIMITS.sourceBytes) throw new Error("Browser export supports up to 384 MiB of source video. Split the project or import smaller files.");
  canvasFor(project);
}
