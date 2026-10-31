import { useEffect, useRef, useState } from 'react';
import { Download, FolderOpen, RotateCcw, Square, Trash2 } from 'lucide-react';
import { Toggle } from '../components';
import { usePlayer } from '../context';
import { DEFAULT_VOICE_SETTINGS, sanitizeVoiceSettings, type VoiceSettings, type VoiceStats } from '../voicePitch';
import { decodeVoiceFile, renderVoiceTuning, validateVoiceBuffer, VOICE_LIMITS } from '../voiceRuntime';
import '../voice.css';

const STORAGE_KEY = 'mortimer:voiceTuning:v1';
const KEYS = ['C', 'C♯', 'D', 'D♯', 'E', 'F', 'F♯', 'G', 'G♯', 'A', 'A♯', 'B'];
const SCALES = ['Chromatic', 'Major', 'Minor', 'Pentatonic'];
function initialSettings(): VoiceSettings {
  try { return sanitizeVoiceSettings(JSON.parse(localStorage.getItem(STORAGE_KEY) || 'null')); }
  catch { return { ...DEFAULT_VOICE_SETTINGS }; }
}
function downloadVoice(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a'); link.href = url; link.download = name; link.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
function readableError(error: unknown) { return error instanceof Error ? error.message : 'Voice processing failed. Your original audio is unchanged.'; }
const hz = (value: number) => Number.isFinite(value) && value > 0 ? `${value.toFixed(1)} Hz` : 'No confident pitch';
type Output = { blob: Blob; url: string; signature: string; stats: VoiceStats };

/** Independent, offline voice workbench. Originals and rendered WAV are never processed twice. */
export function VoiceTuning({ active = true, sourceFile = null, sourceBuffer = null, onStartAudio }: { active?: boolean; sourceFile?: File | null; sourceBuffer?: AudioBuffer | null; onStartAudio?: () => void }) {
  const library = usePlayer();
  const [settings, setSettings] = useState<VoiceSettings>(initialSettings);
  const [file, setFile] = useState<File | null>(null);
  const [buffer, setBuffer] = useState<AudioBuffer | null>(null);
  const [sourceUrl, setSourceUrl] = useState<string | null>(null);
  const [output, setOutput] = useState<Output | null>(null);
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState<'decode' | 'render' | null>(null);
  const [progress, setProgress] = useState(0);
  const [notice, setNotice] = useState('');
  const input = useRef<HTMLInputElement>(null);
  const original = useRef<HTMLAudioElement>(null);
  const processed = useRef<HTMLAudioElement>(null);
  const controller = useRef<AbortController | null>(null);
  const generation = useRef(0);
  const mounted = useRef(true);
  const signature = JSON.stringify({ settings, file: file ? [file.name, file.size, file.lastModified] : null });
  const signatureRef = useRef(signature); signatureRef.current = signature;
  const stale = !!output && signature !== output.signature;

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; generation.current++; controller.current?.abort(); };
  }, []);
  useEffect(() => () => { if (sourceUrl) URL.revokeObjectURL(sourceUrl); }, [sourceUrl]);
  useEffect(() => () => { if (output) URL.revokeObjectURL(output.url); }, [output]);
  useEffect(() => { if (!active) { original.current?.pause(); processed.current?.pause(); } }, [active]);
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      if (busy || (output && !saved && !stale)) { event.preventDefault(); event.returnValue = ''; }
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [busy, output, saved, stale]);

  const update = (change: Partial<VoiceSettings>) => {
    const next = sanitizeVoiceSettings({ ...settings, ...change });
    setSettings(next);
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(next)); }
    catch { setNotice('These controls apply now, but browser storage could not save them for the next session.'); }
  };
  const confirmReplace = () => !output || saved || stale || window.confirm('Replace the tuned render that has not been downloaded? Download it first to keep a copy.');
  async function loadFile(next: File, preparedBuffer: AudioBuffer | null = null) {
    if (busy || !confirmReplace()) return;
    const token = ++generation.current;
    const job = new AbortController(); controller.current = job;
    original.current?.pause(); processed.current?.pause(); setBusy('decode'); setProgress(0); setNotice(`Reading ${next.name}…`);
    try {
      if (!next.size || next.size > VOICE_LIMITS.bytes) throw new Error('Choose a non-empty voice file up to 32 MiB.');
      const decoded = preparedBuffer ? validateVoiceBuffer(preparedBuffer) : await decodeVoiceFile(next, job.signal);
      if (!mounted.current || token !== generation.current) return;
      setFile(next); setBuffer(decoded); setSourceUrl(URL.createObjectURL(next)); setOutput(null); setSaved(false);
      setNotice(`${next.name} · ${decoded.duration.toFixed(2)} s · ${decoded.numberOfChannels === 1 ? 'mono' : 'stereo'} · ready to tune. Your original file is unchanged.`);
    } catch (error) {
      if (mounted.current && token === generation.current) setNotice(job.signal.aborted ? 'Loading cancelled. The previous recording is unchanged.' : readableError(error));
    } finally { if (mounted.current && token === generation.current) setBusy(null); }
  }
  async function render() {
    if (!buffer || !file || busy) return;
    const token = ++generation.current;
    const job = new AbortController(); controller.current = job;
    const snapshot = signature;
    const captured = sanitizeVoiceSettings(settings);
    original.current?.pause(); processed.current?.pause(); setBusy('render'); setProgress(0); setNotice('Analyzing voice pitch and rendering a new WAV copy…');
    try {
      const result = await renderVoiceTuning(buffer, captured, job.signal, value => { if (mounted.current && token === generation.current) setProgress(value); });
      if (!mounted.current || token !== generation.current) return;
      setOutput({ ...result, url: URL.createObjectURL(result.blob), signature: snapshot }); setSaved(false);
      setNotice(snapshot !== signatureRef.current ? 'Controls changed while rendering. Render again before downloading the current sound.' : result.stats.voicedFrames === 0 && captured.tune && captured.shiftSemitones === 0 ? 'No confident monophonic pitch was found. Unvoiced audio was preserved; try a clean, isolated vocal recording.' : 'Tuned WAV ready. Compare it with the original, then download a separate copy.');
    } catch (error) {
      if (mounted.current && token === generation.current) setNotice(job.signal.aborted ? 'Voice render cancelled. Your original and previous render are unchanged.' : readableError(error));
    } finally { if (mounted.current && token === generation.current) setBusy(null); }
  }
  function audition(which: HTMLAudioElement) {
    if (onStartAudio) onStartAudio();
    else if (library.playing) library.actions.togglePlay();
    // Other creative workspaces may remain mounted to retain their drafts.
    for (const media of document.querySelectorAll<HTMLMediaElement>('audio, video')) if (media !== which) media.pause();
  }
  const clear = () => {
    if (busy || !confirmReplace()) return;
    original.current?.pause(); processed.current?.pause(); generation.current++;
    setFile(null); setBuffer(null); setSourceUrl(null); setOutput(null); setSaved(false); setNotice('Voice workbench cleared. Original files are preserved.');
  };
  const coverage = output && output.stats.totalFrames ? Math.round(output.stats.voicedFrames / output.stats.totalFrames * 100) : 0;

  return <section className="labPanel voiceTuning" aria-label="Voice tuning">
    <div className="labHeader"><div><span className="voiceKicker">VOICE STUDIO</span><h2>Pitch & tuning</h2></div><span className="badge">Local · WAV 48 kHz</span></div>
    <p className="hint">Tune an isolated voice to a key and scale or transpose it without changing its duration. Granular processing can add texture; this is intended for monophonic vocals, not a full mix. Formants are not preserved.</p>
    <div className="buttonRow">
      <button className="btn" disabled={!!busy} onClick={() => input.current?.click()}><FolderOpen size={17} /> Choose voice file</button>
      {sourceFile && <button className="btn" disabled={!!busy} onClick={() => void loadFile(sourceFile, sourceBuffer)}>Use workbench audio</button>}
      <button className="btn" disabled={!!busy || !file} onClick={clear}><Trash2 size={16} /> Clear voice</button>
    </div>
    <input ref={input} type="file" hidden accept="audio/*,.wav,.mp3,.m4a,.aac,.ogg,.flac,.webm" aria-label="Voice tuning audio file" onChange={event => { const selected = event.target.files?.[0]; event.target.value = ''; if (selected) void loadFile(selected); }} />
    <p className="hint">Up to 60 seconds, mono/stereo and 32 MiB. Audio is processed in a private worker. Voice files and renders stay in this session; download results before closing.</p>
    <div className="voiceControls">
      <div className="voiceToggle"><span>Correct pitch to scale</span><Toggle label="Correct voice pitch" checked={settings.tune} onChange={tune => update({ tune })} /></div>
      <label className="voiceSelect">Key<select className="select" aria-label="Voice key" value={settings.key} onChange={event => update({ key: Number(event.target.value) })}>{KEYS.map((key, index) => <option key={key} value={index}>{key}</option>)}</select></label>
      <label className="voiceSelect">Scale<select className="select" aria-label="Voice scale" value={settings.scale} onChange={event => update({ scale: Number(event.target.value) })}>{SCALES.map((scale, index) => <option key={scale} value={index}>{scale}</option>)}</select></label>
      <VoiceSlider label="Correction amount" value={settings.amount} min={0} max={1} step={0.01} display={`${Math.round(settings.amount * 100)}%`} onChange={amount => update({ amount })} />
      <VoiceSlider label="Retune speed" value={settings.retuneMs} min={5} max={500} step={5} display={`${settings.retuneMs} ms`} onChange={retuneMs => update({ retuneMs })} />
      <VoiceSlider label="Transpose voice" value={settings.shiftSemitones} min={-12} max={12} step={0.5} display={`${settings.shiftSemitones > 0 ? '+' : ''}${settings.shiftSemitones} semitones`} onChange={shiftSemitones => update({ shiftSemitones })} />
    </div>
    <p className="hint">Correction follows confident voiced sections. Transposition shifts the whole recording. Slower retuning follows expression more gradually; chromatic mode allows all twelve notes.</p>
    <div className="buttonRow">
      <button className="btn primary" disabled={!buffer || !!busy} onClick={() => void render()}>Render tuned WAV</button>
      <button className="btn" onClick={() => update(DEFAULT_VOICE_SETTINGS)}><RotateCcw size={16} /> Reset voice controls</button>
      {busy && <button className="btn" disabled={controller.current?.signal.aborted} onClick={() => { controller.current?.abort(); setNotice('Cancelling voice operation…'); }}><Square size={15} /> Cancel voice operation</button>}
    </div>
    {busy && <div className="voiceProgress" role="status"><span>{busy === 'decode' ? 'Decoding voice…' : `Rendering voice · ${Math.round(progress * 100)}%`}</span>{busy === 'render' && <progress max={1} value={progress} aria-label="Voice render progress" />}</div>}
    {sourceUrl && <div className="voiceAudition"><label>Original recording · {file?.name}</label><audio ref={original} src={sourceUrl} controls aria-label="Original voice preview" onPlay={event => audition(event.currentTarget)} /></div>}
    {output && <div className="voiceResult">
      <div className="voiceResultHeader"><h3>{stale ? 'Previous render' : 'Tuned voice copy'}</h3><span>{coverage}% confident voiced windows</span></div>
      <p className="hint">{hz(output.stats.meanInputHz)} → {hz(output.stats.meanTargetHz)} · Original duration retained with compensated grain timing.</p>
      {stale && <p className="voiceNotice" role="status">Voice controls changed. Render again before downloading the current sound.</p>}
      <audio ref={processed} controls src={output.url} aria-label="Tuned voice preview" onPlay={event => audition(event.currentTarget)} />
      <button className="btn" disabled={stale || !!busy} onClick={() => { downloadVoice(output.blob, `${file?.name.replace(/\.[^.]+$/, '') || 'MUGEN-voice'}-tuned.wav`); setSaved(true); }}><Download size={16} /> Download tuned WAV</button>
    </div>}
    {notice && <p className="voiceNotice" role="status">{notice}</p>}
  </section>;
}
function VoiceSlider({ label, value, min, max, step, display, onChange }: { label: string; value: number; min: number; max: number; step: number; display: string; onChange: (value: number) => void }) {
  return <label className="voiceSlider"><span>{label}<output>{display}</output></span><input type="range" className="range" aria-label={label} value={value} min={min} max={max} step={step} onChange={event => onChange(Number(event.target.value))} /></label>;
}
