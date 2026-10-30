export type SubtitleCue = { startMs: number; endMs: number; text: string };
export type SubtitleSync = { offsetMs: number; scale: number };
export type SubtitleReference = { subtitleMs: number; videoMs: number };
export const DEFAULT_SUBTITLE_SYNC: SubtitleSync = { offsetMs: 0, scale: 1 };

export function validateSync(sync: SubtitleSync): SubtitleSync {
  if (!Number.isFinite(sync.offsetMs) || Math.abs(sync.offsetMs) > 86_400_000 || !Number.isFinite(sync.scale) || sync.scale < 0.5 || sync.scale > 2) {
    throw new Error("Offset must be within 24 hours and timing scale between 0.5 and 2.");
  }
  return { offsetMs: Math.round(sync.offsetMs), scale: sync.scale };
}
export function calibrateSubtitles(first: SubtitleReference, second?: SubtitleReference): SubtitleSync {
  for (const ref of [first, second].filter((r): r is SubtitleReference => !!r)) {
    if (![ref.subtitleMs, ref.videoMs].every((n) => Number.isFinite(n) && n >= 0)) throw new Error("Reference times must be positive numbers.");
  }
  const gap = second ? second.subtitleMs - first.subtitleMs : 0;
  const videoGap = second ? second.videoMs - first.videoMs : 0;
  if (second && (gap < 1000 || videoGap < 1000)) throw new Error("Choose the second phrase at least one second after the first.");
  const scale = second ? videoGap / gap : 1;
  return validateSync({ scale, offsetMs: first.videoMs - first.subtitleMs * scale });
}
export function subtitleTimestamp(raw: string): number | null {
  const match = /^(?:(\d{1,6}):)?(\d{2}):(\d{2})[.,](\d{1,3})$/.exec(raw);
  if (!match || +match[2] > 59 || +match[3] > 59) return null;
  return ((+(match[1] || 0) * 60 + +match[2]) * 60 + +match[3]) * 1000 + +match[4].padEnd(3, "0");
}
export function parseSubtitles(text: string): SubtitleCue[] {
  if (text.length > 4 * 1024 * 1024) throw new Error("Subtitle file is too large (maximum 4 MiB).");
  const lines = text.replace(/^\uFEFF/, "").replace(/\r\n?/g, "\n").split("\n");
  const cues: SubtitleCue[] = [];
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (/^(NOTE(?:\s|$)|STYLE$|REGION$)/.test(line)) { while (i < lines.length && lines[i].trim()) i++; continue; }
    if (!line.includes("-->")) continue;
    const [a, b] = line.split("-->", 2);
    const startMs = subtitleTimestamp(a.trim());
    const endMs = subtitleTimestamp(b.trim().split(/\s/)[0]);
    const body: string[] = [];
    while (++i < lines.length && lines[i].trim()) body.push(lines[i]);
    if (startMs == null || endMs == null || endMs <= startMs) continue;
    const plain = body.join("\n").replace(/<[^>]*>/g, "").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&nbsp;/g, " ").replace(/&amp;/g, "&").trim();
    if (plain.length > 8192) throw new Error("A subtitle phrase is too long.");
    if (plain) cues.push({ startMs, endMs, text: plain });
    if (cues.length > 20_000) throw new Error("Subtitle file contains too many phrases.");
  }
  if (!cues.length) throw new Error("No valid SRT or WebVTT subtitles were found.");
  return cues.sort((a, b) => a.startMs - b.startMs);
}
export function decodeSubtitles(bytes: ArrayBuffer): string {
  const data = new Uint8Array(bytes);
  const encoding = data[0] === 0xff && data[1] === 0xfe ? "utf-16le" : data[0] === 0xfe && data[1] === 0xff ? "utf-16be" : "utf-8";
  const text = new TextDecoder(encoding).decode(bytes);
  return encoding === "utf-8" && text.includes("\uFFFD") ? new TextDecoder("windows-1252").decode(bytes) : text;
}
export function serializeSubtitles(cues: SubtitleCue[], sync: SubtitleSync, format: "srt" | "vtt", clipStartMs = 0, clipEndMs = Infinity): string {
  validateSync(sync);
  if (!Number.isFinite(clipStartMs) || clipStartMs < 0 || clipEndMs <= clipStartMs || Number.isNaN(clipEndMs)) throw new Error("Invalid clip range.");
  const stamp = (ms: number) => {
    ms = Math.round(ms);
    return `${String(Math.floor(ms / 3600000)).padStart(2, "0")}:${String(Math.floor(ms / 60000) % 60).padStart(2, "0")}:${String(Math.floor(ms / 1000) % 60).padStart(2, "0")}${format === "srt" ? "," : "."}${String(ms % 1000).padStart(3, "0")}`;
  };
  const blocks: string[] = [];
  for (const cue of cues) {
    const start = Math.max(clipStartMs, Math.round(cue.startMs * sync.scale + sync.offsetMs));
    const end = Math.min(clipEndMs, Math.round(cue.endMs * sync.scale + sync.offsetMs));
    if (end <= start) continue;
    const text = cue.text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    blocks.push(`${blocks.length + 1}\n${stamp(start - clipStartMs)} --> ${stamp(end - clipStartMs)}\n${text}\n`);
  }
  return (format === "vtt" ? "WEBVTT\n\n" : "") + blocks.join("\n");
}
