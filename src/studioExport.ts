import { FFmpeg } from "@ffmpeg/ffmpeg";
import coreURL from "@ffmpeg/core?url";
import wasmURL from "@ffmpeg/core/wasm?url";
import { validateProject, type StudioProject } from "./studioModel";
import { buildClipCommand, canvasFor, checkExportBudget, concatManifest, STUDIO_EXPORT_LIMITS } from "./studioExportPlan";

let exporting = false;
const cancelled = () => new DOMException("Export cancelled. Your project is unchanged.", "AbortError");
const checkAbort = (signal: AbortSignal) => { if (signal.aborted) throw cancelled(); };

/** A private worker and filesystem keep Studio isolated from music decoding. */
export async function exportStudio(project: StudioProject, media: Map<string, Blob>, signal: AbortSignal, onProgress: (progress: number, label: string) => void): Promise<Blob> {
  checkAbort(signal);
  if (exporting) throw new Error("An export is already running. Cancel it before starting another.");
  const snapshot = validateProject(JSON.parse(JSON.stringify(project)) as StudioProject);
  const files = new Map(media);
  checkExportBudget(snapshot, files);
  const canvas = canvasFor(snapshot);
  exporting = true;
  const engine = new FFmpeg();
  const assetURLs: string[] = [];
  let stage = "Loading export engine";
  let completed = 0;
  let currentDuration = 0;
  let progressValue = 0;
  let lastLog = "";
  const totalDuration = snapshot.clips.reduce((sum, c) => sum + (c.out - c.in) / c.speed, 0);
  const report = (value: number, label: string) => { progressValue = Math.max(progressValue, Math.min(1, value)); onProgress(progressValue, label); };
  const abort = () => { try { engine.terminate(); } catch {} };
  signal.addEventListener("abort", abort, { once: true });
  const asset = async (url: string, type: string) => {
    const response = await fetch(url, { signal });
    if (!response.ok) throw new Error("The export engine could not be loaded. Go online once, reload MUGEN and retry.");
    const blob = new Blob([await response.arrayBuffer()], { type });
    checkAbort(signal);
    const result = URL.createObjectURL(blob);
    assetURLs.push(result);
    return result;
  };
  engine.on("log", ({ message }) => { lastLog = (lastLog + "\n" + message).slice(-4000); });
  engine.on("progress", ({ time }) => {
    if (currentDuration > 0 && Number.isFinite(time)) report(0.05 + 0.9 * (completed + Math.min(currentDuration, Math.max(0, time / 1_000_000))) / totalDuration, stage);
  });
  const exec = async (args: string[]) => {
    checkAbort(signal);
    lastLog = "";
    const code = await engine.exec(args);
    checkAbort(signal);
    if (code !== 0) {
      const memory = /memory|out of bounds|allocation/i.test(lastLog);
      throw new Error(memory ? "The browser ran out of export memory. Choose 720p or split the project into shorter parts." : `${stage} failed. The video may contain an unsupported codec or damaged stream. Try a standard H.264 MP4 source.`);
    }
  };
  const probe = async (input: string): Promise<{ streams: Array<{ codec_type?: string }>; format?: { size?: string } }> => {
    checkAbort(signal);
    const code = await engine.ffprobe(["-v", "error", "-show_entries", "stream=codec_type:format=size", "-of", "json", input, "-o", "probe.json"]);
    checkAbort(signal);
    if (code !== 0) throw new Error("Could not inspect this video. Its container or codec may not be supported by browser export.");
    const result = await engine.readFile("probe.json");
    const text = typeof result === "string" ? result : new TextDecoder().decode(result);
    await engine.deleteFile("probe.json");
    const value: unknown = JSON.parse(text);
    if (!value || typeof value !== "object" || !Array.isArray((value as { streams?: unknown }).streams)) throw new Error("The video returned invalid stream information.");
    return value as { streams: Array<{ codec_type?: string }>; format?: { size?: string } };
  };
  try {
    report(0, stage);
    // Sequential loading ensures any created URL is registered before cleanup.
    const js = await asset(coreURL, "text/javascript");
    const wasm = await asset(wasmURL, "application/wasm");
    checkAbort(signal);
    await engine.load({ coreURL: js, wasmURL: wasm });
    checkAbort(signal);
    let outputBytes = 0;
    const knownStreams = new Map<string, { audio: boolean; video: boolean }>();
    for (let i = 0; i < snapshot.clips.length; i++) {
      const clip = snapshot.clips[i];
      const input = `source-${i}.input`;
      const output = `clip-${i}.mp4`;
      stage = `Rendering clip ${i + 1} of ${snapshot.clips.length}`;
      currentDuration = 0;
      report(0.05 + 0.9 * completed / totalDuration, stage);
      const bytes = new Uint8Array(await files.get(clip.sourceId)!.arrayBuffer());
      checkAbort(signal);
      await engine.writeFile(input, bytes);
      let streams = knownStreams.get(clip.sourceId);
      if (!streams) {
        const metadata = await probe(input);
        streams = { audio: metadata.streams.some((s) => s.codec_type === "audio"), video: metadata.streams.some((s) => s.codec_type === "video") };
        knownStreams.set(clip.sourceId, streams);
      }
      if (!streams.video) throw new Error("A project source has no video stream. Replace it with a video file.");
      currentDuration = (clip.out - clip.in) / clip.speed;
      await exec(buildClipCommand(clip, canvas, streams.audio, input, output));
      await engine.deleteFile(input);
      currentDuration = 0;
      const metadata = await probe(output);
      const size = Number(metadata.format?.size);
      if (!Number.isFinite(size) || size <= 0) throw new Error("A rendered clip is empty. Check its trim range and original file.");
      outputBytes += size;
      if (outputBytes > STUDIO_EXPORT_LIMITS.outputBytes) throw new Error("The rendered project exceeds 384 MiB. Choose 720p or export shorter parts.");
      completed += (clip.out - clip.in) / clip.speed;
    }
    stage = "Assembling final MP4";
    report(0.96, stage);
    await engine.writeFile("clips.txt", new TextEncoder().encode(concatManifest(snapshot.clips.length)));
    await exec(["-hide_banner", "-y", "-f", "concat", "-safe", "1", "-i", "clips.txt", "-c", "copy", "-movflags", "+faststart", "MUGEN-export.mp4"]);
    const finalMetadata = await probe("MUGEN-export.mp4");
    const finalSize = Number(finalMetadata.format?.size);
    if (!Number.isFinite(finalSize) || finalSize <= 0 || finalSize > STUDIO_EXPORT_LIMITS.outputBytes) throw new Error("The exported MP4 is empty or too large for browser memory. Try a shorter project.");
    const output = await engine.readFile("MUGEN-export.mp4");
    checkAbort(signal);
    if (typeof output === "string" || !output.byteLength) throw new Error("The export did not produce a valid MP4.");
    const copy = new Uint8Array(output.byteLength);
    copy.set(output);
    const blob = new Blob([copy.buffer], { type: "video/mp4" });
    report(1, "Export ready");
    return blob;
  } catch (error) {
    if (signal.aborted) throw cancelled();
    if (error instanceof Error && /memory|out of bounds|allocation/i.test(error.message)) throw new Error("The browser ran out of export memory. Choose 720p or split the project into shorter parts.");
    throw error instanceof Error ? error : new Error("Video export failed. Your saved project is unchanged.");
  } finally {
    signal.removeEventListener("abort", abort);
    try { engine.terminate(); } catch {}
    for (const url of assetURLs) URL.revokeObjectURL(url);
    exporting = false;
  }
}
