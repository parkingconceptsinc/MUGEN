package com.parkingconcepts.mortimer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

@Composable internal fun SynthStudioPanel(effects:AudioEffects.Settings,onStartAudio:()->Unit,persistSettings:Boolean=true,quiet:Boolean=false,waveExporter:WaveExportState?=null){
    val context=LocalContext.current;val c=LocalMortimerColors.current;val scope=rememberCoroutineScope();val lifecycle=LocalLifecycleOwner.current.lifecycle
    val synth=remember{SynthPreview(context,muted=quiet)};var s by remember{mutableStateOf(if(persistSettings)loadSynth(context) else SynthSettings())};var active by remember{mutableStateOf(false)};var step by remember{mutableIntStateOf(0)}
    var selectedNote by remember{mutableIntStateOf(60)};var useFx by remember{mutableStateOf(true)};var error by remember{mutableStateOf<String?>(null)}
    var job by remember{mutableStateOf<Job?>(null)};var progress by remember{mutableFloatStateOf(0f)};var bars by remember{mutableIntStateOf(2)};val exporter=waveExporter?:rememberWaveExporter()
    LaunchedEffect(s,effects,useFx){synth.settings=s;synth.effects=if(useFx)effects else AudioEffects.Settings();if(persistSettings)saveSynth(context,s.copy(sequence=false))}
    LaunchedEffect(Unit){while(true){active=synth.active;step=synth.currentStep;synth.error?.let{error=it};delay(80)}}
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP){synth.stop();s=s.copy(sequence=false)}};lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer);synth.close();job?.cancel()}}
    fun start(){if(!synth.active){onStartAudio();synth.settings=s;synth.effects=if(useFx)effects else AudioEffects.Settings();synth.start()}}
    AudioLabCard("Polyphonic synth"){
        Text("8 voices · two oscillators · envelope · resonant filter",fontSize=12.sp,color=c.muted)
        Row(Modifier.horizontalScroll(rememberScrollState())){listOf("Sine","Saw","Square","Triangle").forEachIndexed{i,wave->Chip(wave,s.wave==i){s=s.copy(wave=i)}}}
        Row(Modifier.horizontalScroll(rememberScrollState())){TextButton(onClick={s=s.copy(octave=(s.octave-1).coerceAtLeast(2))}){Text("Octave −")};Text("${s.octave}",Modifier.padding(12.dp),color=c.text);TextButton(onClick={s=s.copy(octave=(s.octave+1).coerceAtMost(6))}){Text("Octave +")};TextButton(onClick={s=s.copy(sequence=false);synth.stop()}){Text("Stop all")}}
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(3.dp)){
            for(offset in 0..12){val note=(s.octave+1)*12+offset;val dark=offset%12 in setOf(1,3,6,8,10)
                Box(Modifier.width(44.dp).height(if(dark)90.dp else 130.dp).background(if(dark)Color(0xFF202631)else Color(0xFFEBE8DF),RoundedCornerShape(5.dp))
                    .semantics{contentDescription="Play ${PitchScale.name(note)}";onClick{start();selectedNote=note;synth.noteOn(note);scope.launch{delay(250);synth.noteOff(note)};true}}
                    .pointerInput(note){detectTapGestures(onPress={start();selectedNote=note;synth.noteOn(note);try{tryAwaitRelease()}finally{synth.noteOff(note)}})},contentAlignment=Alignment.BottomCenter){Text(PitchScale.name(note),color=if(dark)Color.White else Color(0xFF161B22),fontSize=10.sp,modifier=Modifier.padding(bottom=8.dp))}
            }
        }
        LabSlider("Synth output",s.volume*100,0f..80f,"%"){s=s.copy(volume=it/100)}
        LabToggle("Use playback effects",useFx){useFx=it}
        Text(if(useFx&&effects.enabled)"Playback effects active" else "Dry synth preview",fontSize=12.sp,color=c.muted)
    }
    AudioLabCard("Envelope & tone"){
        LabSlider("Attack",s.attack*1000,2f..1000f,"ms"){s=s.copy(attack=it/1000)}
        LabSlider("Decay",s.decay*1000,10f..2000f,"ms"){s=s.copy(decay=it/1000)}
        LabSlider("Sustain",s.sustain*100,0f..100f,"%"){s=s.copy(sustain=it/100)}
        LabSlider("Release",s.release*1000,10f..2000f,"ms"){s=s.copy(release=it/1000)}
        LabSlider("Filter cutoff",s.cutoff,80f..20000f,"Hz"){s=s.copy(cutoff=it)}
        LabSlider("Synth resonance",s.resonance,.5f..4f,"Q"){s=s.copy(resonance=it)}
        LabSlider("Oscillator detune",s.detuneCents,0f..25f,"cents"){s=s.copy(detuneCents=it)}
        LabSlider("Second oscillator",s.blend*100,0f..100f,"%"){s=s.copy(blend=it/100)}
        LabSlider("Vibrato rate",s.vibratoHz,.1f..10f,"Hz"){s=s.copy(vibratoHz=it)}
        LabSlider("Vibrato depth",s.vibratoDepth,0f..1f,"semitones"){s=s.copy(vibratoDepth=it)}
    }
    AudioLabCard("16-step sequencer"){
        Text("Choose a note on the keyboard, then tap a step to add or remove it. Selected: ${PitchScale.name(selectedNote)}",fontSize=12.sp,color=c.muted)
        for(row in 0..1)Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)){
            for(i in row*8 until row*8+8)Chip("${i+1} · ${if(s.steps[i]<0)"—" else PitchScale.name(s.steps[i])}",active&&s.sequence&&step==i){s=s.copy(steps=s.steps.mapIndexed{index,v->if(index==i){if(v==selectedNote)-1 else selectedNote}else v})}
        }
        LabSlider("Sequencer tempo",s.bpm,40f..240f,"BPM"){s=s.copy(bpm=it)}
        Row{Button(onClick={if(active&&s.sequence){s=s.copy(sequence=false);synth.stop()}else{s=s.copy(sequence=true);synth.settings=s;start()}}){Text(if(active&&s.sequence)"Stop sequence" else "Play sequence")};TextButton(onClick={s=s.copy(steps=List(16){-1})}){Text("Clear steps")}}
        Row{listOf(1,2,4,8).forEach{n->TextButton(onClick={bars=n}){Text("$n bars${if(n==bars)" ✓" else ""}")}}}
        if(job?.isActive==true){LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth());TextButton(onClick={job?.cancel()}){Text("Cancel render")}}
        else Button(enabled=!exporter.busy,onClick={synth.stop();s=s.copy(sequence=false);progress=0f;val settings=s;val renderBars=bars;val renderEffects=if(useFx)effects else AudioEffects.Settings();job=scope.launch{
            var file:File?=null
            try{file=File.createTempFile("synth-render-",".wav",context.cacheDir);renderSynthWave(file!!,settings,renderEffects,renderBars){p->scope.launch{progress=p}};exporter.save(file!!,"MUGEN-synth-$renderBars-bars.wav");error=null}
            catch(cancelled:CancellationException){file?.delete();error="Render cancelled.";throw cancelled}
            catch(failure:Exception){file?.delete();error=failure.message?:"Synth render failed."}finally{job=null}
        }}){Text("Render sequence WAV")}
        WaveExportStatus(exporter)
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }
}
private fun loadSynth(context:android.content.Context):SynthSettings=runCatching{
    val o=JSONObject(context.getSharedPreferences("mortimer_prefs",0).getString("synth_settings","{}")?:"{}")
    SynthSettings(wave=o.optInt("wave",0),octave=o.optInt("octave",4),attack=o.optDouble("attack",.015).toFloat(),decay=o.optDouble("decay",.18).toFloat(),sustain=o.optDouble("sustain",.65).toFloat(),release=o.optDouble("release",.25).toFloat(),cutoff=o.optDouble("cutoff",6500.0).toFloat(),resonance=o.optDouble("resonance",.707).toFloat(),detuneCents=o.optDouble("detune",5.0).toFloat(),blend=o.optDouble("blend",.3).toFloat(),vibratoHz=o.optDouble("vibratoHz",5.0).toFloat(),vibratoDepth=o.optDouble("vibratoDepth",0.0).toFloat(),volume=o.optDouble("volume",.35).toFloat(),bpm=o.optDouble("bpm",120.0).toFloat(),steps=o.optJSONArray("steps")?.let{a->List(16){a.optInt(it,-1)}}?:SynthSettings().steps).clean()
}.getOrDefault(SynthSettings())
private fun saveSynth(context:android.content.Context,s:SynthSettings){
    val o=JSONObject().put("wave",s.wave).put("octave",s.octave).put("steps",JSONArray(s.steps));mapOf("attack" to s.attack,"decay" to s.decay,"sustain" to s.sustain,"release" to s.release,"cutoff" to s.cutoff,"resonance" to s.resonance,"detune" to s.detuneCents,"blend" to s.blend,"vibratoHz" to s.vibratoHz,"vibratoDepth" to s.vibratoDepth,"volume" to s.volume,"bpm" to s.bpm).forEach{(k,v)->o.put(k,v.toDouble())};context.getSharedPreferences("mortimer_prefs",0).edit().putString("synth_settings",o.toString()).apply()
}
