import { useEffect, useRef, useState, useSyncExternalStore, type ChangeEvent } from 'react';
import { Download, FolderOpen, Mic, Pause, RotateCcw, Save, Square, Trash2, Upload } from 'lucide-react';
import { usePlayer } from '../context';
import { ScreenHeader, Toggle } from '../components';
import { Equalizer } from './Equalizer';
import { decodeAudioLabFile, getAudioLabBypass, getAudioLabSettings, initEngine, attachElement, readAudioLabMeters, renderAudioLabWav, resumeEngine, setAudioLabBypass, setAudioLabSettings, subscribeAudioLab } from '../audioEngine';
import { DEFAULT_AUDIO_LAB, DEFAULT_AUDIO_LAB_EQ, audioLabHeadroomDb, importAudioLabPresetFile, mergeAudioLabPresets, parseAudioLabPresets, sanitizeAudioLab, serializeAudioLabPresets, type AudioLabPreset, type AudioLabSettings } from '../audioLabModel';
import '../audioLab.css';

const STORAGE_KEY = 'mortimer:audioLab:presets:v1';
const scenes: AudioLabPreset[] = [
  { name: 'Clean master', settings: { ...DEFAULT_AUDIO_LAB, enabled: true }, eq: { ...DEFAULT_AUDIO_LAB_EQ, enabled: true } },
  { name: 'Voice cleanup', settings: { ...DEFAULT_AUDIO_LAB, enabled: true, highPass: true, compressor: true, threshold: -22, ratio: 3, reverbMix: 0.08 }, eq: { ...DEFAULT_AUDIO_LAB_EQ, enabled: true, bands: [-3,-3,-2,0,2,3,3,1,0,-1] } },
  { name: 'Night listening', settings: { ...DEFAULT_AUDIO_LAB, enabled: true, compressor: true, threshold: -30, ratio: 5, makeup: 5 }, eq: { ...DEFAULT_AUDIO_LAB_EQ, enabled: true, bands: [0,0,0,1,2,2,1,0,-1,-2] } },
  { name: 'Wide room', settings: { ...DEFAULT_AUDIO_LAB, enabled: true, reverbMix: 0.22, room: 0.7 }, eq: { ...DEFAULT_AUDIO_LAB_EQ, enabled: true } },
  { name: 'Dub echo', settings: { ...DEFAULT_AUDIO_LAB, enabled: true, delayMix: 0.35, delayMs: 375, delayFeedback: 0.6 }, eq: { ...DEFAULT_AUDIO_LAB_EQ, enabled: true } },
];
const signed = (v: number) => `${v > 0 ? '+' : ''}${v.toFixed(1)}`;
const db = (v: number) => v <= -90 ? '−∞' : signed(v);
function loadPresets() { try { return parseAudioLabPresets(localStorage.getItem(STORAGE_KEY) || '{"format":"mugen-audio-lab","version":1,"presets":{}}'); } catch { return []; } }
function saveBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob); const a = document.createElement('a'); a.href = url; a.download = name; a.click(); window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

export function AudioLab({ active = true }: { active?: boolean }) {
  const p = usePlayer(); const { actions } = p;
  const settings = useSyncExternalStore(subscribeAudioLab, getAudioLabSettings);
  const bypass = useSyncExternalStore(subscribeAudioLab, getAudioLabBypass);
  const [presets, setPresets] = useState<AudioLabPreset[]>(loadPresets);
  const [presetName, setPresetName] = useState('');
  const [notice, setNotice] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [buffer, setBuffer] = useState<AudioBuffer | null>(null);
  const [liveUrl, setLiveUrl] = useState<string | null>(null);
  const [output, setOutput] = useState<{ url: string; blob: Blob; signature: string } | null>(null);
  const [busy, setBusy] = useState<'decode' | 'render' | 'permission' | null>(null);
  const [recording, setRecording] = useState(false);
  const [unsavedTake, setUnsavedTake] = useState(false);
  const [recordSeconds, setRecordSeconds] = useState(0);
  const [meters, setMeters] = useState<ReturnType<typeof readAudioLabMeters> | null>(null);
  const [peakHold, setPeakHold] = useState(-90);
  const sourceInput = useRef<HTMLInputElement>(null); const presetInput = useRef<HTMLInputElement>(null);
  const originalAudio = useRef<HTMLAudioElement>(null); const renderedAudio = useRef<HTMLAudioElement>(null);
  const job = useRef<AbortController | null>(null); const generation = useRef(0); const activeRef = useRef(active); activeRef.current = active;
  const recorder = useRef<MediaRecorder | null>(null); const stream = useRef<MediaStream | null>(null); const mounted = useRef(true);
  const latestPlayState = useRef(p.playing); latestPlayState.current = p.playing;
  const eq = { enabled: p.eq.enabled, bands: p.eq.bands, preamp: p.eq.preamp, balance: p.balance, boost: p.boost };
  const signature = JSON.stringify({ eq, settings, source: file ? [file.name, file.size, file.lastModified] : null });
  const latestSignature = useRef(signature); latestSignature.current = signature;
  const stale = !!output && output.signature !== signature;
  const headroom = audioLabHeadroomDb(settings, eq);

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; generation.current++; job.current?.abort(); if (recorder.current?.state === 'recording') recorder.current.stop(); stream.current?.getTracks().forEach(track => track.stop()); };
  }, []);
  useEffect(() => {
    if (!unsavedTake && !recording && !busy) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; };
    window.addEventListener('beforeunload', warn); return () => window.removeEventListener('beforeunload', warn);
  }, [unsavedTake, recording, busy]);
  useEffect(() => () => { if (liveUrl) URL.revokeObjectURL(liveUrl); }, [liveUrl]);
  useEffect(() => () => { if (output) URL.revokeObjectURL(output.url); }, [output]);
  useEffect(() => {
    if (!active) { originalAudio.current?.pause(); renderedAudio.current?.pause(); if (recorder.current?.state === 'recording') recorder.current.stop(); }
    if (!active) return;
    let frame = 0; let last = 0;
    const tick = (time: number) => {
      if (time - last >= 100) { const values = readAudioLabMeters(); setMeters(values); setPeakHold(hold => Math.max(hold, values.peakDb)); last = time; }
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick); return () => cancelAnimationFrame(frame);
  }, [active]);
  useEffect(() => {
    if (!recording) return;
    const start = Date.now(); const timer = window.setInterval(() => { const seconds = Math.floor((Date.now() - start) / 1000); setRecordSeconds(seconds); if (seconds >= 300 && recorder.current?.state === 'recording') recorder.current.stop(); }, 250);
    return () => window.clearInterval(timer);
  }, [recording]);

  const update = (change: Partial<AudioLabSettings>) => {
    if (!setAudioLabSettings(sanitizeAudioLab({ ...settings, ...change }))) setNotice('Changes apply now, but browser storage could not save them. Export a preset to keep a copy.');
  };
  const applyPreset = (preset: AudioLabPreset) => {
    setAudioLabBypass(false); update(preset.settings); actions.setEq(() => ({ enabled: preset.eq.enabled, preset: preset.name, bands: [...preset.eq.bands], preamp: preset.eq.preamp })); actions.setBalance(preset.eq.balance); actions.setBoost(preset.eq.boost); setNotice(`Applied ${preset.name}.`);
  };
  function storePresets(next: AudioLabPreset[]) {
    try { localStorage.setItem(STORAGE_KEY, serializeAudioLabPresets(next)); setPresets(next); return true; }
    catch { setNotice('Could not save presets. Export a copy before closing the app.'); return false; }
  }
  async function loadAudio(next: File, recorded = false) {
    const token = ++generation.current; job.current?.abort(); const controller = new AbortController(); job.current = controller;
    originalAudio.current?.pause(); renderedAudio.current?.pause(); setBusy('decode'); setNotice(''); setOutput(null); setBuffer(null); setFile(next); setLiveUrl(URL.createObjectURL(next)); setUnsavedTake(recorded);
    try {
      const decoded = await decodeAudioLabFile(next, controller.signal);
      if (token !== generation.current || !mounted.current) return;
      setBuffer(decoded); setNotice(`${next.name} · ${decoded.duration.toFixed(1)} s · ready to audition and render.`);
    } catch (error) { if (token === generation.current && mounted.current && !controller.signal.aborted) setNotice((error as Error).message || 'This audio format could not be decoded. Try WAV, MP3, AAC or FLAC.'); }
    finally { if (token === generation.current && mounted.current) setBusy(null); }
  }
  async function render() {
    if (!buffer || !file) return;
    const token = ++generation.current; const controller = new AbortController(); job.current?.abort(); job.current = controller;
    const snapshot = signature; const capturedSettings = sanitizeAudioLab(settings); const capturedEq = { ...eq, bands: [...eq.bands] };
    originalAudio.current?.pause(); renderedAudio.current?.pause(); setBusy('render'); setNotice('Rendering a new 48 kHz stereo WAV with the current chain and effect tails…');
    try {
      const blob = await renderAudioLabWav(buffer, capturedEq, capturedSettings, controller.signal);
      if (token !== generation.current || !mounted.current) return;
      setOutput({ blob, url: URL.createObjectURL(blob), signature: snapshot }); setNotice(snapshot === latestSignature.current ? 'Render complete. Listen before downloading; your original file is unchanged.' : 'Settings changed during rendering. Render again to export the current sound.');
    } catch (error) { if (token === generation.current && mounted.current) setNotice(controller.signal.aborted ? 'Render discarded.' : (error as Error).message || 'Rendering failed. Try a shorter file.'); }
    finally { if (token === generation.current && mounted.current) setBusy(null); }
  }
  async function startRecording() {
    if (unsavedTake && !window.confirm('Replace the unsaved microphone take? Save the original take first to keep a copy.')) return;
    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') { setNotice('Microphone recording is unavailable in this browser. Import a local recording instead.'); return; }
    const token = ++generation.current; setBusy('permission'); setNotice('Allow microphone access to record a local take.');
    originalAudio.current?.pause(); renderedAudio.current?.pause(); if (latestPlayState.current) actions.togglePlay();
    try {
      const media = await navigator.mediaDevices.getUserMedia({ audio: true });
      if (!mounted.current || !activeRef.current || token !== generation.current) { media.getTracks().forEach(track => track.stop()); if (mounted.current) setBusy(null); return; }
      stream.current = media;
      const mime = ['audio/webm;codecs=opus', 'audio/mp4', 'audio/ogg;codecs=opus'].find(type => MediaRecorder.isTypeSupported(type));
      const take = new MediaRecorder(media, mime ? { mimeType: mime } : undefined); recorder.current = take;
      const chunks: Blob[] = []; let bytes = 0; let failed = false;
      take.ondataavailable = event => { if (event.data.size) { chunks.push(event.data); bytes += event.data.size; } if (bytes > 64 * 1024 * 1024 && take.state === 'recording') take.stop(); };
      take.onerror = () => { failed = true; if (mounted.current) setNotice('Recording stopped after a microphone error. Import another take or try again.'); if (take.state === 'recording') take.stop(); media.getTracks().forEach(track => track.stop()); };
      take.onstop = () => {
        media.getTracks().forEach(track => track.stop()); stream.current = null; recorder.current = null;
        if (!mounted.current) return;
        setRecording(false); setBusy(null);
        if (!failed && bytes) { const type = take.mimeType || mime || 'audio/webm'; const extension = type.includes('mp4') ? 'm4a' : type.includes('ogg') ? 'ogg' : 'webm'; void loadAudio(new File(chunks, `MUGEN-take-${new Date().toISOString().replace(/[:.]/g, '-')}.${extension}`, { type }), true); }
      };
      take.start(1000); setRecording(true); setRecordSeconds(0); setBusy(null); setNotice('Recording on this device. Stop to review the take; recording stops when you leave Audio Lab.');
    } catch (error) { if (mounted.current) { setBusy(null); setNotice((error as Error).name === 'NotAllowedError' ? 'Microphone access was denied. Allow it in browser settings or import a recording.' : 'Could not start the microphone. Check that another app is not using it.'); } stream.current?.getTracks().forEach(track => track.stop()); }
  }
  async function importPresets(event: ChangeEvent<HTMLInputElement>) {
    const picked = event.target.files?.[0]; event.target.value = ''; if (!picked) return;
    try {
      if (picked.size > 256 * 1024) throw new Error('Preset file exceeds 256 KiB.');
      const imported = importAudioLabPresetFile(await picked.text()); const next = mergeAudioLabPresets(presets, imported.presets);
      if (storePresets(next)) setNotice(imported.unsupported.length ? `Imported supported controls. These Android effects are unavailable here: ${imported.unsupported.join(', ')}. Keep the original preset file for full native processing.` : `Imported ${imported.presets.length} presets. Existing names were preserved.`);
    } catch (error) { setNotice((error as Error).message); }
  }
  const pauseOthers = (which: 'live' | 'rendered') => {
    if (latestPlayState.current) actions.togglePlay();
    (which === 'live' ? renderedAudio : originalAudio).current?.pause();
    if (which === 'live') { initEngine(); attachElement(originalAudio.current); resumeEngine(); }
  };

  return <section className="screen audioLabScreen" aria-label="Audio Lab">
    <ScreenHeader title="Audio Lab" subtitle="Shape, compare and render local audio">
      <button className={'btn small' + (bypass ? ' primary' : '')} aria-pressed={bypass} onClick={() => setAudioLabBypass(!bypass)}>{bypass ? 'A · Original' : 'B · Processed'}</button>
    </ScreenHeader>
    <div className="audioLabGrid">
      <div className="labPanel labMeterPanel">
        <div className="labHeader"><h2>Monitor</h2><button className="link" onClick={() => setPeakHold(-90)}>Reset peak</button></div>
        <div className="audioLabSpectrum" role="img" aria-label="Live audio spectrum">{Array.from(meters?.bins ?? new Float32Array(64)).map((value, i) => <i key={i} style={{ height: `${Math.max(2, value * 100)}%` }} />)}</div>
        <div className="audioLabMeter"><span>Peak <b>{db(meters?.peakDb ?? -90)} dBFS</b></span><span>RMS <b>{db(meters?.rmsDb ?? -90)} dBFS</b></span><span className={peakHold > -0.1 ? 'labClip' : ''}>Hold <b>{db(peakHold)} dBFS</b></span><span>Compression <b>{signed(meters?.reductionDb ?? 0)} dB</b></span></div>
        <p className="hint">{bypass ? 'Comparing the original signal. Rendering always uses the processed chain.' : meters?.active ? 'Live monitoring · music, video and local audition' : 'Play library audio or audition a local file to monitor the signal.'}</p>
      </div>
      <div className="labPanel">
        <div className="labHeader"><h2>Processing chain</h2><Toggle label="Audio Lab processing" checked={settings.enabled} onChange={enabled => update({ enabled })} /></div>
        <p className="hint">Advanced processing {settings.enabled ? 'on' : 'off'} · EQ controls below remain independent.</p>
        <LabToggle label="Automatic headroom" checked={settings.autoHeadroom} onChange={autoHeadroom => update({ autoHeadroom })} hint={`${headroom.toFixed(1)} dB reserved for positive EQ, boost and makeup gain`} />
        <LabToggle label="High-pass filter" checked={settings.highPass} onChange={highPass => update({ highPass })} />
        <LabSlider label="High-pass frequency" value={settings.highPassHz} min={20} max={1200} step={5} unit="Hz" onChange={highPassHz => update({ highPassHz })} />
        <LabToggle label="Low-pass filter" checked={settings.lowPass} onChange={lowPass => update({ lowPass })} />
        <LabSlider label="Low-pass frequency" value={settings.lowPassHz} min={1500} max={22000} step={100} unit="Hz" onChange={lowPassHz => update({ lowPassHz })} />
      </div>
      <div className="labPanel">
        <div className="labHeader"><h2>Dynamics</h2><Toggle label="Compressor" checked={settings.compressor} onChange={compressor => update({ compressor })} /></div>
        <LabSlider label="Compressor threshold" value={settings.threshold} min={-60} max={0} step={1} unit="dB" onChange={threshold => update({ threshold })} />
        <LabSlider label="Compression ratio" value={settings.ratio} min={1} max={20} step={0.5} unit=":1" onChange={ratio => update({ ratio })} />
        <LabSlider label="Attack" value={settings.attackMs} min={0.5} max={100} step={0.5} unit="ms" onChange={attackMs => update({ attackMs })} />
        <LabSlider label="Release" value={settings.releaseMs} min={20} max={1000} step={10} unit="ms" onChange={releaseMs => update({ releaseMs })} />
        <LabSlider label="Makeup gain" value={settings.makeup} min={0} max={12} step={0.5} unit="dB" onChange={makeup => update({ makeup })} />
        <LabToggle label="Output limiter" checked={settings.limiter} onChange={limiter => update({ limiter })} />
        <LabSlider label="Limiter threshold" value={settings.ceiling} min={-12} max={-0.1} step={0.1} unit="dB" onChange={ceiling => update({ ceiling })} />
        <p className="hint">The limiter is a fast dynamics compressor. Its threshold is not a true-peak delivery guarantee.</p>
      </div>
      <div className="labPanel">
        <div className="labHeader"><h2>Space & delay</h2><span className="badge">Stereo</span></div>
        <LabSlider label="Delay mix" value={settings.delayMix} min={0} max={0.7} step={0.01} unit="" onChange={delayMix => update({ delayMix })} />
        <LabSlider label="Delay time" value={settings.delayMs} min={10} max={1500} step={5} unit="ms" onChange={delayMs => update({ delayMs })} />
        <LabSlider label="Delay feedback" value={settings.delayFeedback} min={0} max={0.85} step={0.01} unit="" onChange={delayFeedback => update({ delayFeedback })} />
        <LabSlider label="Reverb mix" value={settings.reverbMix} min={0} max={0.6} step={0.01} unit="" onChange={reverbMix => update({ reverbMix })} />
        <LabSlider label="Room size" value={settings.room} min={0} max={1} step={0.05} unit="" onChange={room => update({ room })} />
        <p className="hint">Exports include up to 12 seconds of effect decay. Delay and reverb are additive; compare levels before raising the mix.</p>
      </div>
    </div>
    <div className="labPanel labPresets">
      <div className="labHeader"><h2>Complete presets</h2><span className="badge">EQ + processing</span></div>
      <div className="chips">{scenes.map(scene => <button className="chip" key={scene.name} onClick={() => applyPreset(scene)}>{scene.name}</button>)}</div>
      <div className="labPresetSave"><label>Preset name<input aria-label="Audio Lab preset name" maxLength={48} value={presetName} onChange={e => setPresetName(e.target.value)} /></label><button className="btn" disabled={!presetName.trim() || presets.length >= 32} onClick={() => {
        const name = presetName.trim(); if (presets.some(preset => preset.name === name)) return setNotice('That name already exists. Choose a new name to preserve the saved preset.');
        if (storePresets([...presets, { name, settings, eq }])) { setPresetName(''); setNotice(`Saved ${name}.`); }
      }}><Save size={16} /> Save complete preset</button></div>
      {presets.length > 0 && <ul className="labPresetList">{presets.map(preset => <li key={preset.name}><button className="btn small" onClick={() => applyPreset(preset)}>{preset.name}</button><button className="iconBtn" aria-label={`Delete Audio Lab preset ${preset.name}`} onClick={() => { if (window.confirm(`Delete preset “${preset.name}”?`)) storePresets(presets.filter(item => item.name !== preset.name)); }}><Trash2 size={16} /></button></li>)}</ul>}
      <div className="buttonRow"><button className="btn" onClick={() => presetInput.current?.click()}><Upload size={16} /> Import presets</button><button className="btn" disabled={!presets.length} onClick={() => saveBlob(new Blob([serializeAudioLabPresets(presets)], { type: 'application/json' }), 'MUGEN-Audio-Lab-presets.json')}><Download size={16} /> Export presets</button><button className="btn" onClick={() => { update(DEFAULT_AUDIO_LAB); setAudioLabBypass(false); }}><RotateCcw size={16} /> Reset advanced processing</button></div>
      <p className="hint">Portable presets share supported controls with Android. Additional native effects are identified on import.</p>
      <input ref={presetInput} type="file" hidden accept=".json,application/json" aria-label="Audio Lab preset file" onChange={importPresets} />
    </div>
    <div className="labPanel audioLabWorkstation">
      <div className="labHeader"><h2>Local audio workbench</h2><span className="badge">WAV · 48 kHz · 16-bit</span></div>
      <p className="hint">Import a file or record a take, audition the live chain, then render a separate processed copy. Up to five minutes / 64 MiB. No uploads. Workbench files and renders last for this session; save microphone takes before closing the app.</p>
      <div className="buttonRow"><button className="btn" disabled={recording || !!busy} onClick={() => { if (!unsavedTake || window.confirm('Replace the unsaved microphone take? Save the original take first to keep a copy.')) sourceInput.current?.click(); }}><FolderOpen size={17} /> Choose audio file</button>
        {recording ? <button className="btn danger" onClick={() => recorder.current?.stop()}><Square size={17} /> Stop recording · {recordSeconds}s</button> : <button className="btn" disabled={!!busy} onClick={() => void startRecording()}><Mic size={17} /> Record microphone</button>}
        {busy && <button className="btn" disabled={busy === 'permission' || job.current?.signal.aborted} onClick={() => { job.current?.abort(); setNotice('Discarding operation. Waiting for audio processing to release its resources…'); }}>Discard operation</button>}</div>
      <input ref={sourceInput} type="file" hidden accept="audio/*,.wav,.mp3,.flac,.m4a,.ogg,.webm" aria-label="Audio Lab audio file" onChange={event => { const next = event.target.files?.[0]; event.target.value = ''; if (next) void loadAudio(next); }} />
      {unsavedTake && <p className="labNotice" role="status">This microphone take has not been saved. Choose Save original take to keep it after closing MUGEN.</p>}
      {liveUrl && <div className="labAudition"><label>Live audition · {file?.name}</label><audio ref={originalAudio} controls src={liveUrl} aria-label="Live audio audition" onPlay={() => pauseOthers('live')} /><button className="btn small" onClick={() => originalAudio.current?.pause()}><Pause size={15} /> Pause audition</button><button className="btn small" disabled={!file} onClick={() => { if (file) { saveBlob(file, file.name); setUnsavedTake(false); } }}>Save original take</button></div>}
      <div className="buttonRow"><button className="btn primary" disabled={!buffer || !!busy || recording} onClick={() => void render()}>Render processed WAV</button>{output && <button className="btn" disabled={stale || !!busy} onClick={() => saveBlob(output.blob, (file?.name.replace(/\.[^.]+$/, '') || 'MUGEN') + '-processed.wav')}><Download size={16} /> Download processed WAV</button>}</div>
      {output && <div className="labAudition"><label>{stale ? 'Previous render · settings changed, render again before downloading' : 'Rendered copy · processing is already applied'}</label><audio ref={renderedAudio} controls src={output.url} aria-label="Rendered audio preview" onPlay={() => pauseOthers('rendered')} /></div>}
    </div>
    {notice && <p className="labNotice" role="status">{notice}</p>}
    <div className="audioLabEqualizer"><Equalizer /></div>
  </section>;
}
function LabToggle({ label, checked, onChange, hint }: { label: string; checked: boolean; onChange: (value: boolean) => void; hint?: string }) {
  return <div className="labToggle"><span>{label}{hint && <small>{hint}</small>}</span><Toggle label={label} checked={checked} onChange={onChange} /></div>;
}
function LabSlider({ label, value, min, max, step, unit, onChange }: { label: string; value: number; min: number; max: number; step: number; unit: string; onChange: (value: number) => void }) {
  return <label className="labSlider"><span>{label}<output>{Number(value.toFixed(2))}{unit && ` ${unit}`}</output></span><input className="range" aria-label={label} type="range" min={min} max={max} step={step} value={value} onChange={event => onChange(Number(event.target.value))} /></label>;
}
