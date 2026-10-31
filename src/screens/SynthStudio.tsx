import { useEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react';
import { Download, Play, Square, Upload } from 'lucide-react';
import { LiveSynth, renderSynthWav } from '../synthEngine';
import { SYNTH_DEFAULTS, exportSynthPatch, importSynthPatch, sanitizeSynth, synthFingerprint, synthNoteName, synthSequencePlan, type SynthSettings } from '../synthModel';
import '../synth.css';

const STORAGE = 'mortimer:synth:v1';
const COMPUTER_KEYS = ['a','w','s','e','d','f','t','g','y','h','u','j','k'];
function initialPatch() {
  try { const text = localStorage.getItem(STORAGE); return { patch: text ? importSynthPatch(text) : sanitizeSynth(SYNTH_DEFAULTS), warning: '' }; }
  catch { return { patch: sanitizeSynth(SYNTH_DEFAULTS), warning: 'The saved synth patch could not be recovered. Defaults are loaded; import a patch backup to restore your sound.' }; }
}
function download(blob: Blob, name: string) { const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = name; link.click(); window.setTimeout(() => URL.revokeObjectURL(url),60_000); }

export function SynthStudio({ active = true, onStartAudio }: { active?: boolean; onStartAudio?: () => void }) {
  const [initial] = useState(initialPatch); const [settings,setSettings] = useState(initial.patch);
  const [notice,setNotice] = useState(initial.warning); const [storageFailed,setStorageFailed] = useState(false);
  const [bars,setBars] = useState(2); const [selectedNote,setSelectedNote] = useState(60);
  const [heldNotes,setHeldNotes] = useState<number[]>([]); const held = useRef(new Map<string,number>());
  const [sequencing,setSequencing] = useState(false); const [step,setStep] = useState(-1);
  const [rendering,setRendering] = useState(false); const [progress,setProgress] = useState(0); const [discarding,setDiscarding] = useState(false);
  const [output,setOutput] = useState<{url:string;blob:Blob;fingerprint:string}|null>(null);
  const synth = useRef<LiveSynth|null>(null); const preview = useRef<HTMLAudioElement>(null); const input = useRef<HTMLInputElement>(null); const keyboard = useRef<HTMLDivElement>(null);
  const controller = useRef<AbortController|null>(null); const mounted = useRef(true); const activeRef = useRef(active); activeRef.current = active;
  const settingsRef = useRef(settings); settingsRef.current = settings;
  const onStartRef = useRef(onStartAudio); onStartRef.current = onStartAudio;
  const fingerprint = synthFingerprint(settings,bars); const currentFingerprint = useRef(fingerprint); currentFingerprint.current = fingerprint;
  const stale = !!output && output.fingerprint !== fingerprint;
  const duration = synthSequencePlan(settings,bars).duration;
  const update = (change: Partial<SynthSettings>) => setSettings(current => sanitizeSynth({...current,...change}));
  const heldChanged = () => { if (mounted.current) setHeldNotes([...held.current.values()]); };
  const stopAll = () => { synth.current?.stopAll(); held.current.clear(); heldChanged(); if (mounted.current) { setSequencing(false); setStep(-1); } };

  useEffect(() => {
    mounted.current = true;
    const releaseKey = (event: KeyboardEvent) => { const id = `key:${event.key.toLowerCase()}`; synth.current?.noteOff(id); held.current.delete(id); heldChanged(); };
    const stopOnHide = () => { if (document.visibilityState !== 'visible') { stopAll(); preview.current?.pause(); } };
    window.addEventListener('keyup',releaseKey); window.addEventListener('blur',stopAll); document.addEventListener('visibilitychange',stopOnHide);
    return () => { mounted.current=false; controller.current?.abort(); held.current.clear(); void synth.current?.close(); synth.current=null; window.removeEventListener('keyup',releaseKey); window.removeEventListener('blur',stopAll); document.removeEventListener('visibilitychange',stopOnHide); };
  }, []);
  useEffect(() => {
    try { localStorage.setItem(STORAGE,exportSynthPatch(settings)); setStorageFailed(false); }
    catch { setStorageFailed(true); setNotice('This browser could not save the patch. Export a patch file before closing the app.'); }
    synth.current?.setPatch(settings);
  }, [settings]);
  useEffect(() => {
    if (!active) { stopAll(); preview.current?.pause(); void synth.current?.close(); synth.current=null; }
  }, [active]);
  useEffect(() => {
    if (!sequencing || !active) return;
    const timer = window.setInterval(() => setStep(synth.current?.step ?? -1),50); return () => window.clearInterval(timer);
  }, [sequencing,active]);
  useEffect(() => () => { if (output) URL.revokeObjectURL(output.url); }, [output]);
  useEffect(() => {
    if (!storageFailed) return;
    const warn = (event:BeforeUnloadEvent) => { event.preventDefault(); event.returnValue=''; };
    window.addEventListener('beforeunload',warn); return () => window.removeEventListener('beforeunload',warn);
  }, [storageFailed]);

  async function ready() {
    if (!activeRef.current) throw new Error('Open Audio Lab to play the synth.');
    onStartRef.current?.(); preview.current?.pause();
    if (!synth.current) synth.current = new LiveSynth(settingsRef.current);
    const instance = synth.current; await instance.resume();
    if (!activeRef.current || instance !== synth.current) throw new Error('Synth playback stopped.');
    return instance;
  }
  async function noteOn(id:string,note:number) {
    if (held.current.has(id)) return;
    held.current.set(id,note); heldChanged(); setSelectedNote(note);
    try { const engine=await ready(); if (held.current.get(id)===note) engine.noteOn(id,note); }
    catch (error) { held.current.delete(id); heldChanged(); if (mounted.current && activeRef.current) setNotice((error as Error).message || 'Audio output is unavailable. Try another browser.'); }
  }
  function noteOff(id:string) { held.current.delete(id); synth.current?.noteOff(id); heldChanged(); }
  function onKeyboard(event:ReactKeyboardEvent<HTMLDivElement>) {
    const offset = COMPUTER_KEYS.indexOf(event.key.toLowerCase()); if (offset<0 || event.ctrlKey || event.altKey || event.metaKey) return;
    event.preventDefault(); event.stopPropagation(); if (!event.repeat) void noteOn(`key:${event.key.toLowerCase()}`,(settings.octave+1)*12+offset);
  }
  async function playSequence() {
    if (sequencing) return stopAll();
    try { const engine=await ready(); engine.startSequence(); setSequencing(true); setNotice('Sequencer playing. Moving a control restarts the pattern with the new patch.'); }
    catch (error) { if (mounted.current) setNotice((error as Error).message || 'Could not start the synth.'); }
  }
  async function render() {
    if (rendering) return; stopAll(); preview.current?.pause();
    const token=new AbortController(); controller.current=token; setRendering(true); setDiscarding(false); setProgress(0); setNotice('Rendering dry synth audio…');
    const snapshot=fingerprint; const patch=sanitizeSynth(settings); const renderBars=bars;
    try {
      const blob=await renderSynthWav(patch,renderBars,{signal:token.signal,onProgress:value => { if(mounted.current) setProgress(value); }});
      if(!mounted.current) return;
      setOutput({url:URL.createObjectURL(blob),blob,fingerprint:snapshot});
      setNotice(snapshot===currentFingerprint.current ? 'Synth render complete. Listen to the copy before downloading.' : 'Patch changed while rendering. Render again before downloading the current sound.');
    } catch (error) { if(mounted.current) setNotice(token.signal.aborted ? 'Synth render discarded.' : (error as Error).message || 'Could not render the sequence.'); }
    finally { if(mounted.current) { setRendering(false); setDiscarding(false); } controller.current=null; }
  }
  async function importFile(event:React.ChangeEvent<HTMLInputElement>) {
    const file=event.target.files?.[0]; event.target.value=''; if(!file) return;
    try { if(file.size>16*1024) throw new Error('Synth patch exceeds 16 KiB.'); const patch=importSynthPatch(await file.text()); if(!mounted.current)return; stopAll(); setSettings(patch); setNotice('Synth patch imported. Playback stays stopped; press a key or play the sequence to audition it.'); }
    catch(error) { if(mounted.current)setNotice((error as Error).message || 'Could not import the synth patch.'); }
  }

  return <section className="synthStudio" aria-label="Synth Studio">
    <div className="labPanel">
      <div className="labHeader"><h2>Synth Studio</h2><span className="badge">8 voices · dual oscillators · dry synth</span></div>
      <p className="hint">Play the keys, shape the envelope and arrange a 16-step pattern. Synth preview and WAV rendering use the same synthesis chain. Audio Lab playback effects are not applied.</p>
      <div className="synthToolbar"><div className="chips" role="group" aria-label="Oscillator waveform">{['Sine','Saw','Square','Triangle'].map((name,wave) => <button key={name} className={'chip'+(settings.wave===wave?' on':'')} aria-pressed={settings.wave===wave} onClick={() => update({wave})}>{name}</button>)}</div><button className="btn" onClick={stopAll}><Square size={16}/> Stop all</button></div>
      <div className="synthToolbar"><label>Keyboard octave<select aria-label="Synth keyboard octave" value={settings.octave} onChange={event => { stopAll(); update({octave:Number(event.target.value)}); }}>{[2,3,4,5,6].map(octave => <option key={octave} value={octave}>{octave}</option>)}</select></label><span className="hint">Focus the keyboard: A W S E D F T G Y H U J K. Release a key to release its note.</span></div>
      <div ref={keyboard} className="synthKeyboard" role="group" aria-label="Synth keyboard" tabIndex={0} onKeyDown={onKeyboard} onBlur={event => { if(!event.currentTarget.contains(event.relatedTarget as Node|null)) stopAll(); }}>
        {Array.from({length:13},(_,offset) => { const note=(settings.octave+1)*12+offset; const black=[1,3,6,8,10].includes(offset%12); return <button key={note} className={'synthKey'+(black?' black':'')+(heldNotes.includes(note)?' pressed':'')} aria-label={`Play ${synthNoteName(note)}`} aria-pressed={heldNotes.includes(note)}
          onPointerDown={event => { event.preventDefault(); keyboard.current?.focus(); try{event.currentTarget.setPointerCapture(event.pointerId);}catch{} void noteOn(`pointer:${event.pointerId}`,note); }}
          onPointerUp={event => noteOff(`pointer:${event.pointerId}`)} onPointerCancel={event => noteOff(`pointer:${event.pointerId}`)} onLostPointerCapture={event => noteOff(`pointer:${event.pointerId}`)}
          onClick={event => { if(event.detail===0) { const id=`accessible:${note}`; void noteOn(id,note); window.setTimeout(() => noteOff(id),250); } }}><b>{synthNoteName(note)}</b><small>{COMPUTER_KEYS[offset].toUpperCase()}</small></button>; })}
      </div>
      <SynthSlider label="Synth output" value={settings.volume} min={0} max={0.8} step={0.01} unit="" onChange={volume => update({volume})}/>
    </div>
    <div className="synthGrid">
      <div className="labPanel"><div className="labHeader"><h3>Envelope</h3><span className="badge">ADSR</span></div>
        <SynthSlider label="Synth attack" value={settings.attack} min={0.002} max={1} step={0.001} unit="s" onChange={attack => update({attack})}/>
        <SynthSlider label="Synth decay" value={settings.decay} min={0.01} max={2} step={0.01} unit="s" onChange={decay => update({decay})}/>
        <SynthSlider label="Synth sustain" value={settings.sustain} min={0} max={1} step={0.01} unit="" onChange={sustain => update({sustain})}/>
        <SynthSlider label="Synth release" value={settings.release} min={0.01} max={2} step={0.01} unit="s" onChange={release => update({release})}/>
        <p className="hint">Envelope changes apply to the next note. Release tails are included in exported audio.</p>
      </div>
      <div className="labPanel"><div className="labHeader"><h3>Tone & motion</h3><span className="badge">Two oscillators</span></div>
        <SynthSlider label="Synth filter cutoff" value={settings.cutoff} min={80} max={20000} step={10} unit="Hz" onChange={cutoff => update({cutoff})}/>
        <SynthSlider label="Synth resonance" value={settings.resonance} min={0.5} max={4} step={0.01} unit="Q" onChange={resonance => update({resonance})}/>
        <SynthSlider label="Oscillator detune" value={settings.detuneCents} min={0} max={25} step={0.1} unit="cents" onChange={detuneCents => update({detuneCents})}/>
        <SynthSlider label="Second oscillator blend" value={settings.blend} min={0} max={1} step={0.01} unit="" onChange={blend => update({blend})}/>
        <SynthSlider label="Vibrato rate" value={settings.vibratoHz} min={0.1} max={10} step={0.1} unit="Hz" onChange={vibratoHz => update({vibratoHz})}/>
        <SynthSlider label="Vibrato depth" value={settings.vibratoDepth} min={0} max={1} step={0.01} unit="semitones" onChange={vibratoDepth => update({vibratoDepth})}/>
      </div>
    </div>
    <div className="labPanel">
      <div className="labHeader"><h3>16-step sequencer</h3><span className="badge">Selected: {synthNoteName(selectedNote)}</span></div>
      <p className="hint">Tap a step to place the selected keyboard note or remove it. Use its note selector for exact pitches and rests.</p>
      <div className="synthSteps">{settings.steps.map((note,index) => <div className={'synthStep'+(step===index?' current':'')} key={index}><button className={'btn small'+(note>=0?' primary':'')} aria-label={`Step ${index+1}: ${synthNoteName(note)}`} aria-pressed={note>=0} onClick={() => update({steps:settings.steps.map((value,i)=>i===index?(value===selectedNote?-1:selectedNote):value)})}><small>{index+1}</small><b>{synthNoteName(note)}</b></button><select aria-label={`Step ${index+1} note`} value={note} onChange={event => update({steps:settings.steps.map((value,i)=>i===index?Number(event.target.value):value)})}><option value={-1}>Rest</option>{Array.from({length:85},(_,index)=>index+24).map(value=><option key={value} value={value}>{synthNoteName(value)}</option>)}</select></div>)}</div>
      <SynthSlider label="Sequencer tempo" value={settings.bpm} min={40} max={240} step={1} unit="BPM" onChange={bpm=>update({bpm})}/>
      <div className="synthToolbar"><button className="btn" disabled={!active} onClick={()=>void playSequence()}><Play size={16}/>{sequencing?'Stop sequence':'Play sequence'}</button><button className="btn" onClick={()=>update({steps:Array(16).fill(-1)})}>Clear synth steps</button><label>Export length<select aria-label="Render bars" value={bars} onChange={event=>setBars(Number(event.target.value))}>{Array.from({length:8},(_,i)=><option key={i+1} value={i+1}>{i+1} {i===0?'bar':'bars'}</option>)}</select></label></div>
      <p className="hint">{bars} {bars===1?'bar':'bars'} · {duration.toFixed(2)} s including release · stereo WAV · 48 kHz / 16-bit. A silent pattern exports silence.</p>
      <div className="synthToolbar"><button className="btn primary" disabled={rendering} onClick={()=>void render()}>Render synth WAV</button>{rendering&&<button className="btn" disabled={discarding} onClick={()=>{controller.current?.abort();setDiscarding(true);setNotice('Discarding synth render. Processing will release its resources before another render can start.');}}>{discarding?'Discarding…':'Discard synth render'}</button>}{output&&<button className="btn" disabled={stale||rendering} onClick={()=>download(output.blob,`MUGEN-synth-${bars}-bars.wav`)}><Download size={16}/>Download synth WAV</button>}</div>
      {rendering&&<progress className="synthProgress" aria-label="Synth render progress" max={1} value={progress}/>}
      {output&&<div className="synthRender"><p className="hint">{stale?'Previous render: patch or length changed. Render again to download the current sound.':'Rendered synth preview. Processing is already applied.'}</p><audio ref={preview} controls src={output.url} aria-label="Rendered synth preview" onPlay={()=>{stopAll();onStartRef.current?.();}}/></div>}
    </div>
    <div className="labPanel"><div className="labHeader"><h3>Patch library</h3><span className="badge">Portable with Android</span></div><p className="hint">The current patch and pattern are saved on this device. Export a patch file for backup or transfer. Importing never starts playback.</p><div className="synthToolbar"><button className="btn" onClick={()=>input.current?.click()}><Upload size={16}/>Import synth patch</button><button className="btn" onClick={()=>download(new Blob([exportSynthPatch(settings)],{type:'application/json'}),'MUGEN-synth-patch.json')}><Download size={16}/>Export synth patch</button><button className="btn" onClick={()=>{stopAll();setSettings(sanitizeSynth(SYNTH_DEFAULTS));setNotice('Synth reset to defaults.');}}>Reset synth</button></div><input ref={input} type="file" hidden accept=".json,application/json" aria-label="Synth patch file" onChange={event=>void importFile(event)}/></div>
    {notice&&<p className="labNotice" role="status">{notice}</p>}
  </section>;
}
function SynthSlider({label,value,min,max,step,unit,onChange}:{label:string;value:number;min:number;max:number;step:number;unit:string;onChange:(value:number)=>void}) { return <label className="synthSlider"><span>{label}<output>{Number(value.toFixed(3))}{unit&&` ${unit}`}</output></span><input type="range" className="range" aria-label={label} min={min} max={max} step={step} value={value} onChange={event=>onChange(Number(event.target.value))}/></label>; }
