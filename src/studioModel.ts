export type StudioSource = { id: string; name: string; duration: number; width: number; height: number; size: number; type: string };
export type StudioClip = { id: string; sourceId: string; in: number; out: number; speed: number; volume: number; mute: boolean; rotation: 0 | 90 | 180 | 270; flip: boolean; brightness: number; contrast: number; saturation: number };
export type StudioProject = { version: 1; id: string; name: string; clips: StudioClip[]; sources: StudioSource[]; aspect: "source" | "16:9" | "9:16" | "1:1"; height: 720 | 1080; updatedAt: number };
export const STUDIO_LIMITS = { clips: 32, bytes: 384 * 1024 * 1024, duration: 1800, sourceDuration: 604800, minimumTrim: 0.5 } as const;
const finite = (v: unknown, min: number, max: number, label: string): number => {
  if (typeof v !== "number" || !Number.isFinite(v) || v < min || v > max) throw new Error(`${label} must be between ${min} and ${max}.`);
  return v;
};
const text = (v: unknown, max: number, label: string): string => {
  if (typeof v !== "string" || !v.trim() || v.length > max) throw new Error(`${label} is missing or too long.`);
  return v;
};
const record = (v: unknown): Record<string, unknown> => {
  if (!v || typeof v !== "object" || Array.isArray(v)) throw new Error("Invalid Studio project.");
  return v as Record<string, unknown>;
};
const flag = (v: unknown): boolean => { if (typeof v !== "boolean") throw new Error("Invalid clip option."); return v; };
export function validateProject(value: unknown): StudioProject {
  const p = record(value);
  if (p.version !== 1) throw new Error("This project format is not supported. Keep your original project file.");
  if (!Array.isArray(p.sources) || !Array.isArray(p.clips) || p.sources.length > STUDIO_LIMITS.clips || p.clips.length > STUDIO_LIMITS.clips) throw new Error("Studio supports up to 32 sources and 32 clips.");
  const sources = p.sources.map((raw): StudioSource => {
    const s = record(raw);
    return { id: text(s.id, 128, "Source identifier"), name: text(s.name, 1024, "Source name"), duration: finite(s.duration, 0.5, STUDIO_LIMITS.sourceDuration, "Source duration"), width: finite(s.width, 1, 32768, "Video width"), height: finite(s.height, 1, 32768, "Video height"), size: finite(s.size, 1, STUDIO_LIMITS.bytes, "Source bytes"), type: typeof s.type === "string" ? s.type.slice(0, 128) : "" };
  });
  if (new Set(sources.map(s => s.id)).size !== sources.length || sources.reduce((sum, s) => sum + s.size, 0) > STUDIO_LIMITS.bytes) throw new Error("Duplicate sources or project media exceeds 384 MiB.");
  const clips = p.clips.map((raw): StudioClip => {
    const c = record(raw);
    const sourceId = text(c.sourceId, 128, "Clip source");
    const source = sources.find(s => s.id === sourceId);
    if (!source) throw new Error("A clip refers to missing media.");
    const start = finite(c.in, 0, source.duration, "In point");
    const end = finite(c.out, 0, source.duration, "Out point");
    if (end - start < STUDIO_LIMITS.minimumTrim - 0.000001) throw new Error("Keep at least 0.5 source seconds in each clip.");
    if (![0, 90, 180, 270].includes(c.rotation as number)) throw new Error("Rotation must be 0, 90, 180 or 270 degrees.");
    return { id: text(c.id, 128, "Clip identifier"), sourceId, in: start, out: end, speed: finite(c.speed, 0.25, 4, "Speed"), volume: finite(c.volume, 0, 2, "Volume"), mute: flag(c.mute), rotation: c.rotation as StudioClip["rotation"], flip: flag(c.flip), brightness: finite(c.brightness, -1, 1, "Brightness"), contrast: finite(c.contrast, 0, 2, "Contrast"), saturation: finite(c.saturation, 0, 2, "Saturation") };
  });
  if (new Set(clips.map(c => c.id)).size !== clips.length) throw new Error("Clip identifiers must be unique.");
  if (clips.reduce((sum, c) => sum + clipDuration(c), 0) > STUDIO_LIMITS.duration + 0.000001) throw new Error("The edited project must be no longer than 30 minutes.");
  if (!["source", "16:9", "9:16", "1:1"].includes(p.aspect as string) || ![720, 1080].includes(p.height as number)) throw new Error("Unsupported export canvas.");
  return { version: 1, id: text(p.id, 128, "Project identifier"), name: text(p.name, 80, "Project name"), sources, clips, aspect: p.aspect as StudioProject["aspect"], height: p.height as 720 | 1080, updatedAt: finite(p.updatedAt, 0, Number.MAX_SAFE_INTEGER, "Save date") };
}
export function emptyProject(id = crypto.randomUUID()): StudioProject { return { version: 1, id, name: "Untitled project", clips: [], sources: [], aspect: "16:9", height: 720, updatedAt: Date.now() }; }
export const clipDuration = (clip: StudioClip): number => (clip.out - clip.in) / clip.speed;
export const projectDuration = (project: StudioProject): number => project.clips.reduce((sum, clip) => sum + clipDuration(clip), 0);
export function newClip(source: StudioSource, id = crypto.randomUUID()): StudioClip { return { id, sourceId: source.id, in: 0, out: source.duration, speed: 1, volume: 1, mute: false, rotation: 0, flip: false, brightness: 0, contrast: 1, saturation: 1 }; }
export function locateProject(project: StudioProject, time: number): { index: number; sourceTime: number; offset: number } | null {
  if (!Number.isFinite(time)) throw new Error("Invalid timeline position.");
  let remaining = Math.max(0, Math.min(time, projectDuration(project)));
  let offset = 0;
  for (let index = 0; index < project.clips.length; index++) {
    const clip = project.clips[index];
    const duration = clipDuration(clip);
    if (remaining < duration || index === project.clips.length - 1) return { index, sourceTime: Math.min(clip.out, clip.in + remaining * clip.speed), offset };
    remaining -= duration; offset += duration;
  }
  return null;
}
export function replaceClip(project: StudioProject, id: string, patch: Partial<StudioClip>): StudioProject {
  if (!project.clips.some(c => c.id === id)) throw new Error("Select a clip first.");
  return validateProject({ ...project, updatedAt: Date.now(), clips: project.clips.map(c => c.id === id ? { ...c, ...patch, id: c.id, sourceId: c.sourceId } : c) });
}
export function splitClip(project: StudioProject, id: string, sourceTime: number, newId = crypto.randomUUID()): StudioProject {
  const index = project.clips.findIndex(c => c.id === id);
  const clip = project.clips[index];
  if (!clip || !Number.isFinite(sourceTime) || sourceTime - clip.in < STUDIO_LIMITS.minimumTrim || clip.out - sourceTime < STUDIO_LIMITS.minimumTrim) throw new Error("Keep at least 0.5 source seconds on each side of the cut.");
  return validateProject({ ...project, updatedAt: Date.now(), clips: [...project.clips.slice(0, index), { ...clip, out: sourceTime }, { ...clip, id: newId, in: sourceTime }, ...project.clips.slice(index + 1)] });
}
export function moveClip(project: StudioProject, id: string, delta: number): StudioProject {
  const index = project.clips.findIndex(c => c.id === id);
  if (index < 0 || !Number.isInteger(delta)) throw new Error("Select a clip first.");
  const clips = [...project.clips];
  clips.splice(Math.max(0, Math.min(clips.length - 1, index + delta)), 0, clips.splice(index, 1)[0]);
  return validateProject({ ...project, clips, updatedAt: Date.now() });
}
export function projectFingerprint(project: StudioProject): string { return JSON.stringify({ ...validateProject(project), updatedAt: 0 }); }
export class StudioHistory {
  current: StudioProject;
  private past: StudioProject[] = [];
  private future: StudioProject[] = [];
  constructor(initial: StudioProject) { this.current = validateProject(initial); }
  get canUndo() { return this.past.length > 0; }
  get canRedo() { return this.future.length > 0; }
  commit(next: StudioProject) {
    const value = validateProject(next);
    if (projectFingerprint(this.current) === projectFingerprint(value)) return this.current;
    this.past.push(this.current); this.past = this.past.slice(-50); this.future = []; this.current = value; return value;
  }
  undo() { const next = this.past.pop(); if (next) { this.future.push(this.current); this.current = { ...next, updatedAt: Date.now() }; } return this.current; }
  redo() { const next = this.future.pop(); if (next) { this.past.push(this.current); this.current = { ...next, updatedAt: Date.now() }; } return this.current; }
}
