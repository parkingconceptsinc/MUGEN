import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {SYNTH_DEFAULTS,sanitizeSynth,importSynthPatch,exportSynthPatch,synthSequencePlan,synthEnvelopeAt,synthNoteHz,synthFingerprint} from '../src/synthModel.ts';
test('native/PWA shared patch loads exactly, roundtrips and never starts playback',()=>{
 const text=readFileSync(new URL('../shared/fixtures/synth-patch.json',import.meta.url),'utf8');
 assert.deepEqual(importSynthPatch(text),SYNTH_DEFAULTS);
 assert.deepEqual(importSynthPatch(exportSynthPatch({...SYNTH_DEFAULTS,sequence:true})),SYNTH_DEFAULTS);
});
test('patch import rejects future, unbounded and invalid musical data before applying',()=>{
 const valid=JSON.parse(exportSynthPatch(SYNTH_DEFAULTS));
 for(const [key,value] of [['bpm',0],['wave',1.5],['volume',2],['attack','slow'],['steps',[0,...Array(15).fill(-1)]],['steps',[69]]]){
  assert.throws(()=>importSynthPatch(JSON.stringify({...valid,settings:{...valid.settings,[key]:value}})));
 }
 assert.throws(()=>importSynthPatch(JSON.stringify({...valid,version:2})));
 assert.throws(()=>importSynthPatch(' '.repeat(16385)));
 assert.equal(sanitizeSynth({cutoff:NaN,steps:[0,24,108,109]}).cutoff,6500);
 assert.deepEqual(sanitizeSynth({steps:[0,24,108,109]}).steps.slice(0,4),[-1,24,108,-1]);
});
test('sequencer timing includes tempo, rests, fixed gate and bounded release tail',()=>{
 const s={...SYNTH_DEFAULTS,bpm:120,release:.01,steps:[69,...Array(15).fill(-1)]};
 const p=synthSequencePlan(s,1);assert.equal(p.notes.length,1);assert.equal(p.notes[0].duration,.08125);assert.equal(p.duration,2.03);assert.equal(p.frames,97440);
 assert.equal(synthSequencePlan(s,8).notes.length,8);
 for(const bars of [0,9,NaN,1.5])assert.throws(()=>synthSequencePlan(s,bars));
 assert.equal(synthSequencePlan({...s,steps:Array(16).fill(-1)},1).notes.length,0);
});
test('musical pitch and envelope values obey their physical targets',()=>{
 assert.equal(synthNoteHz(69),440);assert.equal(synthNoteHz(57),220);assert.equal(synthNoteHz(81),880);
 const s={...SYNTH_DEFAULTS,attack:.1,decay:.2,sustain:.5};assert.equal(synthEnvelopeAt(.05,s),.5);assert.ok(Math.abs(synthEnvelopeAt(.2,s)-.75)<1e-9);assert.equal(synthEnvelopeAt(1,s),.5);
 assert.notEqual(synthFingerprint(s,1),synthFingerprint({...s,cutoff:400},1));
 assert.equal(synthFingerprint({...s,sequence:true},1),synthFingerprint(s,1));
});
