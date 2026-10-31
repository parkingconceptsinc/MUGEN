import type { StudioClip, StudioProject, StudioSource } from "./studioModel";

const MAX_MEDIA = 384 * 1024 * 1024;
const MAX_MANIFEST = 1024 * 1024;
const MAX_ARCHIVE = MAX_MEDIA + 2 * 1024 * 1024;
const MAX_DIRECTORY = 512 * 1024;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const encoder = new TextEncoder();
const decoder = new TextDecoder("utf-8", { fatal: true });
export type PortableStudioMedia = { id: string; path: string; name: string; duration: number; width: number; height: number; size: number; type: string };
export type PortableStudioClip = Omit<StudioClip, "sourceId"> & { mediaId: string };
export type PortableStudioManifest = { format: "mugen-studio"; version: 1; project: { id: string; name: string; aspect: StudioProject["aspect"]; height: StudioProject["height"]; clips: PortableStudioClip[] }; media: PortableStudioMedia[] };
export type PortableStudioOptions = { signal?: AbortSignal; onProgress?: (progress: number, label: string) => void };
const failure = (message: string): never => { throw new Error(message); };
const object = (value: unknown, fields: string[], label: string): Record<string, unknown> => {
  if (!value || typeof value !== "object" || Array.isArray(value)) return failure(`Invalid ${label}.`);
  const record = value as Record<string, unknown>;
  if (Object.keys(record).length !== fields.length || fields.some(field => !Object.prototype.hasOwnProperty.call(record, field)) || Object.keys(record).some(field => !fields.includes(field))) return failure(`${label} contains unsupported or missing fields. No project has been replaced.`);
  return record;
};
const text = (value: unknown, max: number, label: string): string => typeof value === "string" && value.trim() && value.length <= max && !value.includes("\0") ? value : failure(`Invalid ${label}.`);
const id = (value: unknown): string => typeof value === "string" && UUID.test(value) ? value : failure("Portable project identifiers must be lowercase UUIDs.");
const number = (value: unknown, min: number, max: number, label: string): number => typeof value === "number" && Number.isFinite(value) && value >= min && value <= max ? value : failure(`${label} is outside the portable project range.`);
const integer = (value: unknown, min: number, max: number, label: string): number => Number.isInteger(value) ? number(value, min, max, label) : failure(`${label} must be an integer.`);
const boolean = (value: unknown): boolean => typeof value === "boolean" ? value : failure("Invalid portable clip option.");
const checkAbort = (signal?: AbortSignal) => { if (signal?.aborted) throw new DOMException("Portable project operation cancelled. Your workspace is unchanged.", "AbortError"); };

/** Strict shared PWA/Android schema; future transforms are rejected, never discarded. */
export function parsePortableManifest(value: unknown): PortableStudioManifest {
  const root = object(value, ["format", "version", "project", "media"], "portable project manifest");
  if (root.format !== "mugen-studio" || root.version !== 1) return failure("Unsupported portable project version. Keep the original package and update MUGEN.");
  const p = object(root.project, ["id", "name", "aspect", "height", "clips"], "portable project");
  if (!Array.isArray(root.media) || root.media.length < 1 || root.media.length > 32 || !Array.isArray(p.clips) || p.clips.length < 1 || p.clips.length > 32) return failure("A portable project needs 1–32 media files and 1–32 clips.");
  const media = root.media.map((raw, index): PortableStudioMedia => {
    const s = object(raw, ["id", "path", "name", "duration", "width", "height", "size", "type"], "portable source");
    if (s.path !== `media/source-${String(index).padStart(3, "0")}.bin`) return failure("Portable media paths must match their indexed source entries.");
    if (typeof s.type !== "string" || s.type.length > 128 || !/^[\x20-\x7e]*$/.test(s.type)) return failure("Invalid portable media type.");
    return { id: id(s.id), path: s.path as string, name: text(s.name, 1024, "source name"), duration: number(s.duration, 0.5, 604800, "Source duration"), width: integer(s.width, 1, 32768, "Video width"), height: integer(s.height, 1, 32768, "Video height"), size: integer(s.size, 1, MAX_MEDIA, "Source size"), type: s.type };
  });
  if (new Set(media.map(s => s.id)).size !== media.length || media.reduce((sum, s) => sum + s.size, 0) > MAX_MEDIA) return failure("Portable media identifiers are duplicated or the package exceeds 384 MiB.");
  const clips = p.clips.map((raw): PortableStudioClip => {
    const c = object(raw, ["id", "mediaId", "in", "out", "speed", "volume", "mute", "rotation", "flip", "brightness", "contrast", "saturation"], "portable clip");
    const mediaId = id(c.mediaId);
    const source = media.find(s => s.id === mediaId);
    if (!source) return failure("A portable clip references missing source media.");
    const start = number(c.in, 0, source.duration, "In point");
    const end = number(c.out, 0, source.duration, "Out point");
    if (end - start < 0.5 - 0.000001) return failure("Portable clips need at least 0.5 seconds of source media.");
    if (![0, 90, 180, 270].includes(c.rotation as number)) return failure("Unsupported portable rotation.");
    return { id: id(c.id), mediaId, in: start, out: end, speed: number(c.speed, 0.25, 4, "Speed"), volume: number(c.volume, 0, 2, "Volume"), mute: boolean(c.mute), rotation: c.rotation as StudioClip["rotation"], flip: boolean(c.flip), brightness: number(c.brightness, -1, 1, "Brightness"), contrast: number(c.contrast, 0, 2, "Contrast"), saturation: number(c.saturation, 0, 2, "Saturation") };
  });
  if (new Set(clips.map(c => c.id)).size !== clips.length || media.some(s => !clips.some(c => c.mediaId === s.id))) return failure("Portable clips are duplicated or the package contains unused media.");
  if (clips.reduce((sum, c) => sum + (c.out - c.in) / c.speed, 0) > 1800 + 0.000001) return failure("Portable projects are limited to 30 minutes of edited footage.");
  if (!["source", "16:9", "9:16", "1:1"].includes(p.aspect as string) || ![720, 1080].includes(p.height as number)) return failure("Choose a 720p or 1080p portable canvas.");
  return { format: "mugen-studio", version: 1, project: { id: id(p.id), name: text(p.name, 80, "project name"), aspect: p.aspect as StudioProject["aspect"], height: p.height as StudioProject["height"], clips }, media };
}
export function makePortableManifest(project: StudioProject): PortableStudioManifest {
  const used = project.sources.filter(source => project.clips.some(clip => clip.sourceId === source.id));
  return parsePortableManifest({ format: "mugen-studio", version: 1, project: { id: project.id, name: project.name, aspect: project.aspect, height: project.height, clips: project.clips.map(({ sourceId, ...clip }) => ({ ...clip, mediaId: sourceId })) }, media: used.map((source, index) => ({ ...source, path: `media/source-${String(index).padStart(3, "0")}.bin` })) });
}
export function projectFromPortable(manifest: PortableStudioManifest, now = Date.now()): StudioProject {
  const valid = parsePortableManifest(manifest);
  return { version: 1, id: valid.project.id, name: valid.project.name, aspect: valid.project.aspect, height: valid.project.height, updatedAt: now, sources: valid.media.map(({ path: _path, ...source }): StudioSource => source), clips: valid.project.clips.map(({ mediaId, ...clip }): StudioClip => ({ ...clip, sourceId: mediaId })) };
}

const crcTable = Uint32Array.from({ length: 256 }, (_, value) => { let crc = value; for (let bit = 0; bit < 8; bit++) crc = crc & 1 ? 0xedb88320 ^ (crc >>> 1) : crc >>> 1; return crc >>> 0; });
const updateCrc = (previous: number, bytes: Uint8Array) => { let crc = previous; for (const byte of bytes) crc = crcTable[(crc ^ byte) & 255] ^ (crc >>> 8); return crc >>> 0; };
export function studioCrc32(bytes: Uint8Array): number { return (updateCrc(0xffffffff, bytes) ^ 0xffffffff) >>> 0; }
async function blobCrc(blob: Blob, signal?: AbortSignal, progress?: (bytes: number) => void): Promise<number> {
  let crc = 0xffffffff;
  for (let offset = 0; offset < blob.size; offset += 1024 * 1024) { checkAbort(signal); const bytes = new Uint8Array(await blob.slice(offset, Math.min(blob.size, offset + 1024 * 1024)).arrayBuffer()); crc = updateCrc(crc, bytes); progress?.(bytes.byteLength); }
  checkAbort(signal); return (crc ^ 0xffffffff) >>> 0;
}
const header = (length: number) => new DataView(new ArrayBuffer(length));
function localHeader(name: Uint8Array, size: number, crc: number): Uint8Array {
  const view = header(30 + name.length); view.setUint32(0, 0x04034b50, true); view.setUint16(4, 20, true); view.setUint16(6, 0x800, true); view.setUint16(12, 33, true); view.setUint32(14, crc, true); view.setUint32(18, size, true); view.setUint32(22, size, true); view.setUint16(26, name.length, true); new Uint8Array(view.buffer).set(name, 30); return new Uint8Array(view.buffer);
}
function centralHeader(name: Uint8Array, size: number, crc: number, offset: number): Uint8Array {
  const view = header(46 + name.length); view.setUint32(0, 0x02014b50, true); view.setUint16(4, 20, true); view.setUint16(6, 20, true); view.setUint16(8, 0x800, true); view.setUint16(14, 33, true); view.setUint32(16, crc, true); view.setUint32(20, size, true); view.setUint32(24, size, true); view.setUint16(28, name.length, true); view.setUint32(42, offset, true); new Uint8Array(view.buffer).set(name, 46); return new Uint8Array(view.buffer);
}
export async function exportPortableStudio(project: StudioProject, files: Map<string, Blob>, options: PortableStudioOptions = {}): Promise<Blob> {
  checkAbort(options.signal);
  const manifest = makePortableManifest(project);
  const metadata = new Blob([JSON.stringify(manifest)], { type: "application/json" });
  if (metadata.size > MAX_MANIFEST) return failure("Portable project metadata is too large.");
  const entries: Array<{ name: string; blob: Blob }> = [{ name: "manifest.json", blob: metadata }, ...manifest.media.map(source => {
    const blob = files.get(source.id);
    if (!blob || blob.size !== source.size) return failure(`Original media for ${source.name} is missing or incomplete. The package was not exported.`);
    return { name: source.path, blob };
  })];
  const total = entries.reduce((sum, entry) => sum + entry.blob.size, 0);
  let processed = 0; let offset = 0;
  const data: BlobPart[] = []; const directory: Uint8Array[] = [];
  for (const entry of entries) {
    options.onProgress?.(processed / total, "Preparing portable project…");
    const crc = await blobCrc(entry.blob, options.signal, bytes => { processed += bytes; options.onProgress?.(processed / total, "Checking original media…"); });
    const name = encoder.encode(entry.name); const local = localHeader(name, entry.blob.size, crc);
    data.push(local.buffer as ArrayBuffer, entry.blob); directory.push(centralHeader(name, entry.blob.size, crc, offset)); offset += local.byteLength + entry.blob.size;
  }
  const directorySize = directory.reduce((sum, entry) => sum + entry.byteLength, 0);
  const end = header(22); end.setUint32(0, 0x06054b50, true); end.setUint16(8, entries.length, true); end.setUint16(10, entries.length, true); end.setUint32(12, directorySize, true); end.setUint32(16, offset, true);
  checkAbort(options.signal); options.onProgress?.(1, "Portable project ready");
  return new Blob([...data, ...directory.map(bytes => bytes.buffer as ArrayBuffer), end.buffer], { type: "application/zip" });
}

type ZipEntry = { name: string; size: number; crc: number; offset: number; dataOffset: number; end: number };
function safeName(bytes: Uint8Array): string {
  let name: string; try { name = decoder.decode(bytes); } catch { return failure("Invalid UTF-8 ZIP entry name."); }
  if (name !== "manifest.json" && !/^media\/source-\d{3}\.bin$/.test(name)) return failure("The package contains an unsupported file or unsafe path.");
  return name;
}
function checkExtra(bytes: Uint8Array) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let cursor = 0;
  while (cursor < bytes.length) { if (cursor + 4 > bytes.length) return failure("Malformed ZIP extra data."); const tag = view.getUint16(cursor, true); const length = view.getUint16(cursor + 2, true); if (tag === 1 || cursor + 4 + length > bytes.length) return failure("ZIP64 or malformed ZIP extra data is unsupported."); cursor += 4 + length; }
}
async function inspectZip(blob: Blob, signal?: AbortSignal): Promise<ZipEntry[]> {
  checkAbort(signal);
  if (blob.size < 22 || blob.size > MAX_ARCHIVE) return failure("Choose a portable project package no larger than 386 MiB.");
  const tailOffset = Math.max(0, blob.size - 65557); const tail = new Uint8Array(await blob.slice(tailOffset).arrayBuffer()); const tailView = new DataView(tail.buffer);
  let end = -1;
  for (let cursor = tail.length - 22; cursor >= 0; cursor--) if (tailView.getUint32(cursor, true) === 0x06054b50 && cursor + 22 + tailView.getUint16(cursor + 20, true) === tail.length) { end = cursor; break; }
  if (end < 0) return failure("The portable package is incomplete or has trailing data.");
  const count = tailView.getUint16(end + 10, true); const directorySize = tailView.getUint32(end + 12, true); const directoryOffset = tailView.getUint32(end + 16, true);
  if (tailView.getUint16(end + 4, true) || tailView.getUint16(end + 6, true) || tailView.getUint16(end + 8, true) !== count || count < 2 || count > 33 || directorySize > MAX_DIRECTORY || directoryOffset + directorySize !== tailOffset + end) return failure("Split archives, ZIP64 and oversized package directories are unsupported.");
  const bytes = new Uint8Array(await blob.slice(directoryOffset, directoryOffset + directorySize).arrayBuffer()); const view = new DataView(bytes.buffer); const entries: ZipEntry[] = [];
  let cursor = 0; let total = 0;
  for (let index = 0; index < count; index++) {
    checkAbort(signal);
    if (cursor + 46 > bytes.length || view.getUint32(cursor, true) !== 0x02014b50) return failure("Malformed portable package directory.");
    const flags = view.getUint16(cursor + 8, true); const method = view.getUint16(cursor + 10, true); const crc = view.getUint32(cursor + 16, true); const compressed = view.getUint32(cursor + 20, true); const size = view.getUint32(cursor + 24, true); const nameLength = view.getUint16(cursor + 28, true); const extraLength = view.getUint16(cursor + 30, true); const commentLength = view.getUint16(cursor + 32, true); const attributes = view.getUint32(cursor + 38, true); const offset = view.getUint32(cursor + 42, true);
    if (![0, 0x800].includes(flags) || method !== 0 || compressed !== size || size === 0xffffffff || offset === 0xffffffff || view.getUint16(cursor + 34, true) || extraLength > 4096 || commentLength > 4096 || cursor + 46 + nameLength + extraLength + commentLength > bytes.length || (attributes & 0x10) || ((attributes >>> 16) & 0xf000) === 0xa000) return failure("Portable projects require ordinary unencrypted ZIP STORE files without descriptors, symlinks or compression.");
    const name = safeName(bytes.subarray(cursor + 46, cursor + 46 + nameLength));
    if (entries.some(entry => entry.name === name) || (name === "manifest.json" ? size > MAX_MANIFEST : size > MAX_MEDIA) || !size) return failure("Duplicate, empty or oversized portable entry.");
    checkExtra(bytes.subarray(cursor + 46 + nameLength, cursor + 46 + nameLength + extraLength));
    total += size; if (total > MAX_MEDIA + MAX_MANIFEST) return failure("Expanded portable project exceeds its storage budget.");
    const local = new Uint8Array(await blob.slice(offset, offset + 30).arrayBuffer());
    if (local.byteLength !== 30) return failure("Truncated portable file header.");
    const lv = new DataView(local.buffer); const localNameLength = lv.getUint16(26, true); const localExtraLength = lv.getUint16(28, true);
    if (lv.getUint32(0, true) !== 0x04034b50 || lv.getUint16(6, true) !== flags || lv.getUint16(8, true) !== method || lv.getUint32(14, true) !== crc || lv.getUint32(18, true) !== size || lv.getUint32(22, true) !== size || localNameLength !== nameLength || localExtraLength > 4096) return failure("Portable local and directory headers disagree.");
    const localFields = new Uint8Array(await blob.slice(offset + 30, offset + 30 + localNameLength + localExtraLength).arrayBuffer());
    if (localFields.length !== localNameLength + localExtraLength || safeName(localFields.subarray(0, localNameLength)) !== name) return failure("Portable entry paths disagree.");
    checkExtra(localFields.subarray(localNameLength));
    const dataOffset = offset + 30 + localNameLength + localExtraLength; const fileEnd = dataOffset + size;
    if (fileEnd > directoryOffset) return failure("Portable file data overlaps the archive directory.");
    entries.push({ name, size, crc, offset, dataOffset, end: fileEnd }); cursor += 46 + nameLength + extraLength + commentLength;
  }
  if (cursor !== bytes.length) return failure("Unexpected portable directory data.");
  const sorted = [...entries].sort((a, b) => a.offset - b.offset);
  if (sorted[0].offset !== 0 || sorted.some((entry, index) => entry.end !== (sorted[index + 1]?.offset ?? directoryOffset))) return failure("Overlapping or hidden portable package data is unsupported.");
  return entries;
}
export async function importPortableStudio(blob: Blob, options: PortableStudioOptions = {}): Promise<{ project: StudioProject; media: Map<string, Blob> }> {
  const entries = await inspectZip(blob, options.signal);
  const metadata = entries.find(entry => entry.name === "manifest.json");
  if (!metadata) return failure("The portable package has no manifest.json.");
  const metadataBytes = new Uint8Array(await blob.slice(metadata.dataOffset, metadata.end).arrayBuffer());
  if (studioCrc32(metadataBytes) !== metadata.crc) return failure("Portable project metadata failed its checksum.");
  let manifest: PortableStudioManifest;
  try { manifest = parsePortableManifest(JSON.parse(decoder.decode(metadataBytes))); }
  catch (error) { throw error instanceof Error ? error : new Error("The portable project metadata is unreadable."); }
  if (entries.length !== manifest.media.length + 1) return failure("Portable package entries do not match the manifest.");
  const total = manifest.media.reduce((sum, source) => sum + source.size, 0); let checked = 0;
  const media = new Map<string, Blob>();
  for (const source of manifest.media) {
    checkAbort(options.signal);
    const entry = entries.find(entry => entry.name === source.path);
    if (!entry || entry.size !== source.size) return failure(`Portable media for ${source.name} is missing or has the wrong size.`);
    const file = blob.slice(entry.dataOffset, entry.end, source.type);
    const crc = await blobCrc(file, options.signal, bytes => { checked += bytes; options.onProgress?.(checked / total, "Verifying portable media…"); });
    if (crc !== entry.crc) return failure(`Portable media for ${source.name} failed its checksum. Your workspace is unchanged.`);
    media.set(source.id, file);
  }
  checkAbort(options.signal);
  return { project: projectFromPortable(manifest), media };
}
