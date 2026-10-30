import { useCallback, useEffect, useRef, useState, type CSSProperties } from "react";
import { Clapperboard, Download, FilePlus, RotateCcw, RotateCw, Save, Scissors, Trash2, Upload } from "lucide-react";
import { ScreenHeader } from "../components";
import { usePlayer } from "../context";
import { clipDuration, emptyProject, locateProject, moveClip, newClip, projectDuration, projectFingerprint, replaceClip, splitClip, STUDIO_LIMITS, StudioHistory, validateProject, type StudioClip, type StudioProject, type StudioSource } from "../studioModel";
import { resetStudio, restoreStudio, saveStudio } from "../studioStore";
import "../studio.css";

const stamp = (time: number) => `${Math.floor(time / 60).toString().padStart(2, "0")}:${(time % 60).toFixed(2).padStart(5, "0")}`;
const errorText = (error: unknown) => error instanceof Error ? error.name === "QuotaExceededError" ? "There is not enough device storage. Free space and save again; your previous saved project is preserved." : error.message : "The operation could not be completed.";
function readVideo(file: File, signal: AbortSignal): Promise<Omit<StudioSource, "id">> {
  if (!file.size || file.size > STUDIO_LIMITS.bytes) return Promise.reject(new Error("Choose a non-empty video smaller than 384 MiB."));
  return new Promise((resolve, reject) => {
    const video = document.createElement("video");
    const url = URL.createObjectURL(file);
    const cleanup = () => { window.clearTimeout(timeout); signal.removeEventListener("abort", onAbort); video.onloadedmetadata = video.onerror = null; video.removeAttribute("src"); video.load(); URL.revokeObjectURL(url); };
    const fail = (error: Error) => { cleanup(); reject(error); };
    const onAbort = () => fail(new DOMException("Import cancelled", "AbortError"));
    const timeout = window.setTimeout(() => fail(new Error(`Could not read ${file.name}. Try a browser-compatible MP4 or WebM video.`)), 15000);
    signal.addEventListener("abort", onAbort, { once: true });
    video.preload = "metadata"; video.muted = true;
    video.onloadedmetadata = () => {
      if (!Number.isFinite(video.duration) || video.duration < 0.5 || !video.videoWidth || !video.videoHeight) return fail(new Error(`${file.name} has no readable video track or is shorter than 0.5 seconds.`));
      const source = { name: file.name, duration: video.duration, width: video.videoWidth, height: video.videoHeight, size: file.size, type: file.type };
      cleanup(); resolve(source);
    };
    video.onerror = () => fail(new Error(`This browser cannot preview ${file.name}. Convert it to H.264 MP4 or VP8/VP9 WebM and retry.`));
    if (signal.aborted) return onAbort();
    video.src = url;
  });
}

export function Studio({ active = true }: { active?: boolean }) {
  const libraryPlayer = usePlayer();
  const [project, setProject] = useState<StudioProject>(() => emptyProject());
  const history = useRef(new StudioHistory(project));
  const liveProject = useRef(project); liveProject.current = project;
  const media = useRef(new Map<string, Blob>());
  const urls = useRef(new Map<string, string>());
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const input = useRef<HTMLInputElement | null>(null);
  const importController = useRef<AbortController | null>(null);
  const exportController = useRef<AbortController | null>(null);
  const mounted = useRef(true);
  const requestVersion = useRef(0);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [blocked, setBlocked] = useState(false);
  const [saveState, setSaveState] = useState<"saved" | "saving" | "unsaved">("saved");
  const [notice, setNotice] = useState("");
  const [importing, setImporting] = useState(false);
  const [exporting, setExporting] = useState<{ progress: number; label: string } | null>(null);
  const [result, setResult] = useState<{ url: string; fingerprint: string } | null>(null);
  const resultRef = useRef<string | null>(null);
  const [playhead, setPlayhead] = useState(0);
  const [playing, setPlaying] = useState(false);
  const playingIntent = useRef(false);
  const pendingSeek = useRef<{ clipId: string; sourceTime: number } | null>(null);
  const [previewError, setPreviewError] = useState("");
  const clipIndex = project.clips.findIndex(c => c.id === selectedId);
  const clip = project.clips[clipIndex];
  const source = project.sources.find(s => s.id === clip?.sourceId);
  const duration = projectDuration(project);
  const offset = project.clips.slice(0, Math.max(0, clipIndex)).reduce((sum, c) => sum + clipDuration(c), 0);
  const busy = importing || exporting != null;
  const firstClip = project.clips[0];
  const firstSource = project.sources.find(s => s.id === firstClip?.sourceId);
  const sourceCanvasRatio = firstSource ? (firstClip.rotation % 180 ? firstSource.height / firstSource.width : firstSource.width / firstSource.height) : 16 / 9;
  const canvasRatio = project.aspect === "source" ? sourceCanvasRatio : project.aspect === "1:1" ? 1 : project.aspect === "9:16" ? 9 / 16 : 16 / 9;
  const sourceRatio = source ? source.width / source.height : canvasRatio;
  const pictureWidth = clip?.rotation && clip.rotation % 180 ? Math.min(1, canvasRatio * sourceRatio) : Math.min(canvasRatio, sourceRatio);
  const pictureHeight = pictureWidth / sourceRatio;
  const staleExport = !!result && result.fingerprint !== projectFingerprint(project);

  const releaseUrls = useCallback(() => { urls.current.forEach(url => URL.revokeObjectURL(url)); urls.current.clear(); }, []);
  const getUrl = (sourceId: string) => {
    let url = urls.current.get(sourceId);
    if (!url) { const file = media.current.get(sourceId); if (file) { url = URL.createObjectURL(file); urls.current.set(sourceId, url); } }
    return url;
  };
  const commit = (next: StudioProject) => {
    try {
      const value = history.current.commit(next); liveProject.current = value; setProject(value); setSaveState("saving"); setNotice("");
      if (!value.clips.some(c => c.id === selectedId)) setSelectedId(value.clips[0]?.id ?? null);
    } catch (error) { setNotice(errorText(error)); }
  };
  const load = useCallback(async () => {
    setLoaded(false); setBlocked(false); setNotice("");
    try {
      const saved = await restoreStudio();
      if (!mounted.current) return;
      const value = saved.project ?? emptyProject();
      releaseUrls(); media.current = saved.media;
      history.current = new StudioHistory(value); liveProject.current = value; setProject(value); setSelectedId(value.clips[0]?.id ?? null); setSaveState("saved");
      if (saved.recovered) setNotice("Recovered the previous complete project from the backup. Review it and save when ready.");
    } catch (error) { if (mounted.current) { setBlocked(true); setNotice(errorText(error)); } }
    finally { if (mounted.current) setLoaded(true); }
  }, [releaseUrls]);
  useEffect(() => { mounted.current = true; void load(); return () => { mounted.current = false; requestVersion.current++; importController.current?.abort(); exportController.current?.abort(); releaseUrls(); if (resultRef.current) URL.revokeObjectURL(resultRef.current); }; }, [load, releaseUrls]);

  useEffect(() => {
    if (!loaded || blocked || saveState === "saved") return;
    const snapshot = project;
    const files = new Map(media.current);
    const save = () => saveStudio(snapshot, files).then(() => {
      if (mounted.current && projectFingerprint(liveProject.current) === projectFingerprint(snapshot)) setSaveState("saved");
    }).catch(error => { if (mounted.current) { setSaveState("unsaved"); setNotice(errorText(error)); } });
    const timeout = window.setTimeout(() => void save(), 650);
    return () => { window.clearTimeout(timeout); };
  }, [project, loaded, blocked]);
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => { if (busy || saveState !== "saved") { event.preventDefault(); event.returnValue = ""; } };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [busy, saveState]);
  const saveNow = async () => {
    if (blocked) return;
    setSaveState("saving");
    const snapshot = liveProject.current;
    try { await saveStudio(snapshot, media.current); if (mounted.current && projectFingerprint(liveProject.current) === projectFingerprint(snapshot)) setSaveState("saved"); }
    catch (error) { if (mounted.current) { setSaveState("unsaved"); setNotice(errorText(error)); } }
  };

  useEffect(() => {
    if (!active) { playingIntent.current = false; videoRef.current?.pause(); setPlaying(false); }
  }, [active]);
  useEffect(() => {
    const video = videoRef.current;
    if (!video || !clip) return;
    setPreviewError("");
    const url = getUrl(clip.sourceId);
    if (!url) { setPreviewError("This source is missing. Reopen the project or add the original clip again."); return; }
    video.src = url; video.currentTime = clip.in;
    const ready = () => {
      const seek = pendingSeek.current;
      video.currentTime = seek?.clipId === clip.id ? Math.max(clip.in, Math.min(clip.out, seek.sourceTime)) : clip.in;
      if (seek?.clipId === clip.id) pendingSeek.current = null;
      video.playbackRate = clip.speed; video.volume = Math.min(1, clip.volume); video.muted = clip.mute; video.preservesPitch = true;
      if (playingIntent.current) void video.play().catch(() => { playingIntent.current = false; setPlaying(false); }); };
    video.addEventListener("loadedmetadata", ready, { once: true });
    return () => { video.removeEventListener("loadedmetadata", ready); video.pause(); };
  }, [clip?.id, clip?.sourceId]);
  useEffect(() => {
    const video = videoRef.current;
    if (!video || !clip) return;
    video.playbackRate = clip.speed; video.volume = Math.min(1, clip.volume); video.muted = clip.mute;
    video.preservesPitch = true;
    if (video.currentTime < clip.in || video.currentTime > clip.out) video.currentTime = clip.in;
  }, [clip?.in, clip?.out, clip?.speed, clip?.volume, clip?.mute]);
  const advance = () => {
    if (clipIndex >= 0 && clipIndex < project.clips.length - 1) setSelectedId(project.clips[clipIndex + 1].id);
    else { playingIntent.current = false; videoRef.current?.pause(); setPlaying(false); setPlayhead(duration); }
  };
  const seekTimeline = (time: number) => {
    const at = locateProject(project, time);
    if (!at) return;
    const target = project.clips[at.index];
    playingIntent.current = false; setPlaying(false); videoRef.current?.pause();
    if (target.id !== selectedId) pendingSeek.current = { clipId: target.id, sourceTime: at.sourceTime };
    setSelectedId(target.id); setPlayhead(time);
    if (target.id === selectedId && videoRef.current) videoRef.current.currentTime = at.sourceTime;
  };
  const patch = (values: Partial<StudioClip>) => { if (clip) { try { commit(replaceClip(liveProject.current, clip.id, values)); } catch (error) { setNotice(errorText(error)); } } };

  async function importFiles(files: File[]) {
    if (!files.length || busy || blocked) return;
    const controller = new AbortController(); importController.current = controller; setImporting(true); setNotice("");
    const request = ++requestVersion.current;
    try {
      const base = liveProject.current;
      if (media.current.size + files.length > STUDIO_LIMITS.clips || [...media.current.values()].reduce((n, blob) => n + blob.size, 0) + files.reduce((n, f) => n + f.size, 0) > STUDIO_LIMITS.bytes) throw new Error("This workspace supports 32 imported sources and up to 384 MiB of media, including sources kept for undo and recovery. Start a new project to release them.");
      const sources: StudioSource[] = [];
      for (const file of files) { const info = await readVideo(file, controller.signal); sources.push({ ...info, id: crypto.randomUUID() }); }
      if (request !== requestVersion.current || controller.signal.aborted) return;
      const next = validateProject({ ...base, sources: [...base.sources, ...sources], clips: [...base.clips, ...sources.map(s => newClip(s))], updatedAt: Date.now() });
      sources.forEach((s, index) => media.current.set(s.id, files[index]));
      commit(next); setSelectedId(next.clips[base.clips.length]?.id ?? null);
    } catch (error) { if (mounted.current) setNotice(controller.signal.aborted ? "Import cancelled. The project is unchanged." : errorText(error)); }
    finally { importController.current = null; if (mounted.current) setImporting(false); }
  }
  async function exportVideo() {
    if (busy || !project.clips.length) return;
    const snapshot = validateProject(project);
    const fingerprint = projectFingerprint(snapshot);
    const controller = new AbortController(); exportController.current = controller;
    setExporting({ progress: 0, label: "Preparing export engine…" }); setNotice("");
    try {
      await saveNow();
      const { exportStudio } = await import("../studioExport");
      const blob = await exportStudio(snapshot, new Map(media.current), controller.signal, (progress, label) => { if (mounted.current) setExporting({ progress, label }); });
      if (controller.signal.aborted || !mounted.current) return;
      if (resultRef.current) URL.revokeObjectURL(resultRef.current);
      const url = URL.createObjectURL(blob); resultRef.current = url; setResult({ url, fingerprint });
      setNotice("Export complete. Download the MP4 copy; your original files are unchanged.");
    } catch (error) { if (mounted.current) setNotice(controller.signal.aborted ? "Export cancelled. Your project and original files are preserved." : errorText(error)); }
    finally { exportController.current = null; if (mounted.current) setExporting(null); }
  }
  async function startNew() {
    if (busy || (!blocked && project.sources.length && !window.confirm("Start a new Studio project? This removes the saved Studio workspace and its imported copies. Download any export you want to keep first. Your original files and media library are unchanged.")) || (blocked && !window.confirm("Remove the unreadable Studio workspace and start a new project? Your original files and media library are unchanged."))) return;
    try {
      await resetStudio(); requestVersion.current++; releaseUrls(); media.current.clear();
      if (resultRef.current) URL.revokeObjectURL(resultRef.current); resultRef.current = null; setResult(null);
      const value = emptyProject(); history.current = new StudioHistory(value); liveProject.current = value; setProject(value); setSelectedId(null); setPlayhead(0); setBlocked(false); setSaveState("saved"); setNotice("");
    } catch (error) { setNotice(errorText(error)); }
  }
  const transform = clip ? `rotate(${clip.rotation}deg) scaleX(${clip.flip ? -1 : 1})` : undefined;
  const videoStyle: CSSProperties = clip ? { width: `${pictureWidth / canvasRatio * 100}%`, height: `${pictureHeight * 100}%`, transform, filter: `brightness(${Math.max(0, 1 + clip.brightness)}) contrast(${clip.contrast}) saturate(${clip.saturation})` } : {};

  return <section className="screen studioScreen">
    <ScreenHeader title="MUGEN Studio" subtitle="Build a story. Keep your originals.">
      <button className="btn small" onClick={() => void startNew()} disabled={busy || !loaded}><Trash2 size={16} /> New project</button>
    </ScreenHeader>
    <input ref={input} type="file" accept="video/*,.mkv,.mov,.avi,.m4v" multiple hidden aria-label="Import Studio videos" onChange={e => { const files = Array.from(e.target.files ?? []); e.currentTarget.value = ""; void importFiles(files); }} />
    <div className="studioToolbar">
      <label className="studioProjectName"><span>PROJECT</span><input aria-label="Project name" maxLength={80} value={project.name} disabled={busy || blocked || !loaded} onChange={e => { const name = e.target.value; if (name.trim()) commit({ ...project, name, updatedAt: Date.now() }); }} /></label>
      <div className="buttonRow">
        <button className="btn" disabled={!loaded || blocked || busy} onClick={() => input.current?.click()}><FilePlus size={16} /> Add clips</button>
        <button className="iconBtn" aria-label="Undo" title="Undo" disabled={!history.current.canUndo || busy || blocked} onClick={() => { const p = history.current.undo(); liveProject.current = p; setProject(p); setSaveState("saving"); if (!p.clips.some(c => c.id === selectedId)) setSelectedId(p.clips[0]?.id ?? null); }}><RotateCcw size={18} /></button>
        <button className="iconBtn" aria-label="Redo" title="Redo" disabled={!history.current.canRedo || busy || blocked} onClick={() => { const p = history.current.redo(); liveProject.current = p; setProject(p); setSaveState("saving"); if (!p.clips.some(c => c.id === selectedId)) setSelectedId(p.clips[0]?.id ?? null); }}><RotateCw size={18} /></button>
        <button className="btn" disabled={blocked || !loaded || busy} onClick={() => void saveNow()}><Save size={16} /> Save project</button>
      </div>
      <span className={`studioSaveState ${saveState}`} role="status">{!loaded ? "Opening workspace…" : blocked ? "Recovery needed" : saveState === "saved" ? "Saved on this device" : saveState === "saving" ? "Saving project…" : "Not saved — retry"}</span>
    </div>
    {notice && <div className="studioNotice" role="status">{notice}{blocked && <button className="btn small" onClick={() => void load()}>Retry recovery</button>}</div>}
    {importing && <div className="studioNotice" role="status">Reading video metadata… <button className="btn small" onClick={() => importController.current?.abort()}>Cancel import</button></div>}
    {!project.clips.length ? <div className="studioEmpty"><Clapperboard size={56} /><h2>Your next cut starts here</h2><p>Import clips, arrange a timeline, adjust color and sound, then export an MP4 copy. Your workspace saves on this device.</p><button className="btn primary" disabled={!loaded || blocked || busy} onClick={() => input.current?.click()}><Upload size={18} /> Add clips</button><small>Up to 32 clips · 384 MiB original media · 30 minutes edited length. MP4 and WebM recommended.</small></div> : <>
      <div className="studioWorkspace">
        <div className="studioPreviewColumn">
          <div className="studioPreviewFrame" style={{ aspectRatio: String(canvasRatio) }}>
            <video ref={videoRef} aria-label="Studio preview" playsInline preload="metadata" style={videoStyle} onPlay={() => setPlaying(true)} onPause={() => setPlaying(false)} onError={() => { setPreviewError("The browser could not decode this preview. Try a compatible MP4/WebM source."); setPlaying(false); }} onEnded={advance} onTimeUpdate={e => { if (!clip) return; const time = e.currentTarget.currentTime; setPlayhead(offset + Math.max(0, Math.min(clipDuration(clip), (time - clip.in) / clip.speed))); if (playingIntent.current && time >= clip.out - 0.035) advance(); }} />
            {previewError && <div className="studioPreviewError" role="alert">{previewError}</div>}
          </div>
          <div className="studioTransport"><button className="btn primary" disabled={!clip || busy} onClick={() => { const v = videoRef.current; if (!v || !clip) return; if (playing) { playingIntent.current = false; v.pause(); } else { if (v.currentTime >= clip.out - 0.03) v.currentTime = clip.in; if (libraryPlayer.playing) libraryPlayer.actions.togglePlay(); playingIntent.current = true; void v.play().catch(error => setNotice(errorText(error))); } }}>{playing ? "Pause preview" : "Play timeline"}</button><output>{stamp(playhead)} <span>/ {stamp(duration)}</span></output><span className="badge">{project.height}p · 30 fps</span></div>
          <label className="studioTimelineSeek"><span className="srOnly">Timeline position</span><input className="range" aria-label="Timeline position" type="range" min={0} max={duration || 0} step={0.01} value={Math.min(playhead, duration)} onChange={e => seekTimeline(+e.target.value)} disabled={busy} /></label>
          <div className="studioTimeline" aria-label="Project clips">
            {project.clips.map((c, index) => { const s = project.sources.find(s => s.id === c.sourceId)!; return <button key={c.id} className={`studioClip ${c.id === selectedId ? "selected" : ""}`} aria-label={`Select clip ${index + 1}: ${s.name}`} aria-pressed={c.id === selectedId} disabled={busy} onClick={() => { playingIntent.current = false; videoRef.current?.pause(); setSelectedId(c.id); setPlayhead(project.clips.slice(0, index).reduce((n, c) => n + clipDuration(c), 0)); }}><span className="studioClipNumber">{String(index + 1).padStart(2, "0")}</span><b>{s.name}</b><span>{stamp(clipDuration(c))} · {c.speed}×{c.mute ? " · muted" : ""}</span><i className="studioClipStrip" aria-hidden="true" /></button>; })}
          </div>
          <p className="hint">Preview follows the edited timeline and preserves pitch. Color filters are approximate in the preview; export uses the original media. Preview volume is limited to 100%; gain above 100% is applied in the export.</p>
        </div>
        <aside className="studioInspector" aria-label="Clip inspector">
          {clip && source && <><div className="studioInspectorHeading"><span>CLIP {clipIndex + 1}</span><h2>{source.name}</h2><small>{source.width} × {source.height} · {stamp(source.duration)}</small></div>
            <fieldset disabled={busy}><legend>Trim & timing</legend>
              <NumberField label="In point (seconds)" value={clip.in} min={0} max={clip.out - 0.5} step={0.1} onChange={value => patch({ in: value })} />
              <NumberField label="Out point (seconds)" value={clip.out} min={clip.in + 0.5} max={source.duration} step={0.1} onChange={value => patch({ out: value })} />
              <NumberField label="Speed" value={clip.speed} min={0.25} max={4} step={0.05} onChange={value => patch({ speed: value })} />
              <div className="buttonRow"><button className="btn small" onClick={() => patch({ in: Math.min(videoRef.current?.currentTime ?? clip.in, clip.out - 0.5) })}>Set in here</button><button className="btn small" onClick={() => patch({ out: Math.max(videoRef.current?.currentTime ?? clip.out, clip.in + 0.5) })}>Set out here</button></div>
              <button className="btn wide" onClick={() => { try { commit(splitClip(project, clip.id, videoRef.current?.currentTime ?? clip.in)); } catch (error) { setNotice(errorText(error)); } }}><Scissors size={15} /> Split at playhead</button>
            </fieldset>
            <fieldset disabled={busy}><legend>Picture</legend>
              <label className="studioField">Rotation<select className="select" aria-label="Rotation" value={clip.rotation} onChange={e => patch({ rotation: +e.target.value as StudioClip["rotation"] })}>{[0,90,180,270].map(n => <option key={n} value={n}>{n}°</option>)}</select></label>
              <label className="studioCheck"><input type="checkbox" checked={clip.flip} onChange={e => patch({ flip: e.target.checked })} /> Mirror horizontally</label>
              <NumberField label="Brightness" value={clip.brightness} min={-1} max={1} step={0.05} onChange={value => patch({ brightness: value })} />
              <NumberField label="Contrast" value={clip.contrast} min={0} max={2} step={0.05} onChange={value => patch({ contrast: value })} />
              <NumberField label="Saturation" value={clip.saturation} min={0} max={2} step={0.05} onChange={value => patch({ saturation: value })} />
            </fieldset>
            <fieldset disabled={busy}><legend>Sound</legend><NumberField label="Volume" value={clip.volume} min={0} max={2} step={0.05} onChange={value => patch({ volume: value })} /><label className="studioCheck"><input type="checkbox" checked={clip.mute} onChange={e => patch({ mute: e.target.checked })} /> Mute clip</label></fieldset>
            <div className="studioClipActions"><button className="btn small" disabled={busy || clipIndex === 0} onClick={() => commit(moveClip(project, clip.id, -1))}>Move clip earlier</button><button className="btn small" disabled={busy || clipIndex === project.clips.length - 1} onClick={() => commit(moveClip(project, clip.id, 1))}>Move clip later</button><button className="btn small" disabled={busy || project.clips.length >= 32} onClick={() => { const clips = [...project.clips]; clips.splice(clipIndex + 1, 0, { ...clip, id: crypto.randomUUID() }); commit({ ...project, clips, updatedAt: Date.now() }); }}>Duplicate clip</button><button className="btn small danger" disabled={busy} onClick={() => commit({ ...project, clips: project.clips.filter(c => c.id !== clip.id), updatedAt: Date.now() })}>Remove clip</button></div>
          </>}
        </aside>
      </div>
      <div className="studioExportPanel"><div><span className="sectionLabel">DELIVERY</span><h2>Export your final cut</h2><p className="hint">H.264 MP4 · AAC stereo · original files preserved</p></div><label>Canvas<select className="select" aria-label="Export canvas" value={project.aspect} disabled={busy} onChange={e => commit({ ...project, aspect: e.target.value as StudioProject["aspect"], updatedAt: Date.now() })}>{["source","16:9","9:16","1:1"].map(v => <option key={v} value={v}>{v === "source" ? "First clip" : v}</option>)}</select></label><label>Resolution<select className="select" aria-label="Export resolution" value={project.height} disabled={busy} onChange={e => commit({ ...project, height: +e.target.value as 720 | 1080, updatedAt: Date.now() })}><option value={720}>720p</option><option value={1080}>1080p</option></select></label><button className="btn primary" disabled={busy || blocked} onClick={() => void exportVideo()}><Download size={17} /> Export MP4</button>
        {result && !staleExport && <a className="btn" href={result.url} download={`${project.name.replace(/[^a-zA-Z0-9 _-]/g, "_") || "MUGEN"}.mp4`}>Download MP4</a>}
        {staleExport && <p className="hint">The project changed after the last export. Export again to download the updated cut.</p>}
      </div>
    </>}
    {exporting && <div className="studioExportProgress" role="status"><div><b>{exporting.label}</b><span>{Math.round(exporting.progress * 100)}%</span></div><progress max={1} value={exporting.progress} aria-label="Studio export progress" /><button className="btn" onClick={() => exportController.current?.abort()}>Cancel export</button><p className="hint">Keep this tab open. Exporting uses this device and can take several minutes.</p></div>}
  </section>;
}
function NumberField({ label, value, min, max, step, onChange }: { label: string; value: number; min: number; max: number; step: number; onChange: (value: number) => void }) {
  const [draft, setDraft] = useState(String(value));
  useEffect(() => setDraft(String(Math.round(value * 1000) / 1000)), [value]);
  const apply = () => { const parsed = Number(draft); if (draft.trim() && Number.isFinite(parsed) && parsed >= min && parsed <= max) onChange(parsed); else setDraft(String(Math.round(value * 1000) / 1000)); };
  return <label className="studioField">{label}<input type="number" aria-label={label} min={min} max={max} step={step} value={draft} onChange={e => setDraft(e.target.value)} onBlur={apply} onKeyDown={e => { if (e.key === "Enter") { e.preventDefault(); apply(); e.currentTarget.blur(); } }} /></label>;
}
