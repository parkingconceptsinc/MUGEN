package com.parkingconcepts.mortimer

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.*

@UnstableApi
internal fun audioLabPreviewPlayer(context:Context,processor:ProAudioProcessor,audioFocus:Boolean=true):ExoPlayer {
    val factory=object:DefaultRenderersFactory(context){override fun buildAudioSink(context:Context,enableFloatOutput:Boolean,enableAudioTrackPlaybackParams:Boolean):AudioSink=
        DefaultAudioSink.Builder(context).setEnableFloatOutput(false).setEnableAudioTrackPlaybackParams(false).setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES).setAudioProcessors(arrayOf(processor)).build()}
    return ExoPlayer.Builder(context,factory).build().apply{setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT,audioFocus);setHandleAudioBecomingNoisy(true)}
}
@androidx.annotation.OptIn(UnstableApi::class)
@Composable internal fun VoiceStudioPanel(s:AudioEffects.Settings,onChange:(AudioEffects.Settings)->Unit,mix:AudioEffects.Settings,onStartAudio:()->Unit,initialTake:File?=null,quiet:Boolean=false){
    val context=LocalContext.current;val c=LocalMortimerColors.current;val scope=rememberCoroutineScope();val lifecycle=LocalLifecycleOwner.current.lifecycle
    val capture=remember{VoiceCapture(context)};val processor=remember{ProAudioProcessor()};val player=remember{audioLabPreviewPlayer(context,processor,!quiet).apply{if(quiet)volume=0f}}
    var takes by remember{mutableStateOf(if(quiet)listOfNotNull(initialTake) else capture.takes())};var selected by remember{mutableStateOf(takes.firstOrNull())}
    var recording by remember{mutableStateOf(false)};var seconds by remember{mutableFloatStateOf(0f)};var peak by remember{mutableFloatStateOf(0f)}
    var error by remember{mutableStateOf<String?>(null)};var effectPreview by remember{mutableStateOf(true)};var playing by remember{mutableStateOf(false)}
    var position by remember{mutableLongStateOf(0)};var duration by remember{mutableLongStateOf(0)};var meters by remember{mutableStateOf(AudioMeters())}
    var renderJob by remember{mutableStateOf<Job?>(null)};var progress by remember{mutableFloatStateOf(0f)};var tail by remember{mutableStateOf(true)};var delete by remember{mutableStateOf(false)}
    var showEffects by remember{mutableStateOf(false)};val exporter=rememberWaveExporter()
    fun record(){try{player.pause();onStartAudio();capture.start();error=null}catch(failure:Exception){error=failure.message}}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){if(it)record() else error="Microphone access is needed to record. You can allow it in Android app settings."}
    fun preview(processed:Boolean){
        val source=selected?:return;onStartAudio();effectPreview=processed;processor.settings=if(processed)s else AudioEffects.Settings()
        if(player.currentMediaItem?.localConfiguration?.uri!=Uri.fromFile(source)){player.setMediaItem(MediaItem.fromUri(Uri.fromFile(source)));player.prepare()}
        else if(player.playbackState==androidx.media3.common.Player.STATE_ENDED)player.seekTo(0)
        player.play()
    }
    LaunchedEffect(selected){player.stop();player.clearMediaItems();position=0;duration=0}
    LaunchedEffect(s,effectPreview){processor.settings=if(effectPreview)s else AudioEffects.Settings()}
    LaunchedEffect(Unit){var seen:File?=null;while(true){recording=capture.active;seconds=capture.seconds;peak=capture.peak
        capture.completed?.takeIf{it!=seen}?.let{seen=it;takes=capture.takes();selected=it}
        capture.error?.let{error=it};playing=player.isPlaying;position=player.currentPosition;duration=player.duration.coerceAtLeast(0);meters=processor.meters
        player.playerError?.let{error="Voice preview failed: ${it.errorCodeName}"};delay(100)
    }}
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP){capture.stop();player.pause()}};lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer);capture.close();player.release();renderJob?.cancel()}}
    AudioLabCard("Voice studio"){
        Text("Record a take, choose a key and scale, then compare the tuned version with your original.",fontSize=12.sp,color=c.muted)
        Row(Modifier.horizontalScroll(rememberScrollState())){
            if(!recording)Button(onClick={if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)record() else permission.launch(Manifest.permission.RECORD_AUDIO)},enabled=renderJob?.isActive!=true&&!exporter.busy&&!quiet){Text("● Record voice")}
            else Button(onClick={capture.stop()}){Text("■ Stop recording")}
            Text("${audioNumber(seconds," s")} / 300 s",Modifier.padding(12.dp),color=c.text)
        }
        if(recording){LinearProgressIndicator(progress={peak.coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth());Text("Original voice is being saved locally.",fontSize=12.sp,color=c.muted)}
        takes.forEachIndexed{i,file->TextButton(onClick={player.pause();selected=file}){Text((if(selected==file)"● " else "")+"Take ${takes.size-i} · "+java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(file.lastModified())))}}
        if(selected!=null){
            Row(Modifier.horizontalScroll(rememberScrollState())){
                TextButton(onClick={preview(false)},enabled=!recording){Text("Play original")}
                TextButton(onClick={preview(true)},enabled=s.enabled&&!recording){Text("Play effects")}
                TextButton(onClick={player.pause()}){Text("Pause")}
            }
            if(duration>0){Slider(position.coerceIn(0,duration).toFloat(),onValueChange={player.seekTo(it.toLong())},valueRange=0f..duration.toFloat());Text("${playerTime(position)} / ${playerTime(duration)} · ${if(effectPreview)"Effects" else "Original"}",fontSize=12.sp,color=c.muted)}
            AudioLevelMeter(meters)
            Text(if(effectPreview&&s.enabled&&meters.detectedHz>0)"Detected ${PitchScale.name(PitchScale.midi(meters.detectedHz.toDouble()).roundToInt())} · ${audioNumber(meters.detectedHz," Hz")} → ${audioNumber(meters.targetHz," Hz")}" else if(playing&&effectPreview)"No stable vocal note" else "Play effects to monitor detected pitch.",fontSize=12.sp,color=c.muted)
            Row(Modifier.horizontalScroll(rememberScrollState())){
                TextButton(onClick={exporter.save(selected!!,"MUGEN-voice-original.wav")},enabled=!recording&&!exporter.busy){Text("Export original")}
                TextButton(onClick={delete=true},enabled=!recording&&!exporter.busy){Text("Delete take")}
            }
        }
    }
    AudioLabCard("Pitch correction"){
        LabToggle("Process voice",s.enabled){onChange(s.copy(enabled=it))}
        LabToggle("Automatic pitch correction",s.tune){onChange(s.copy(enabled=true,tune=it))}
        Text("For a single voice or solo instrument. This does not separate vocals from a full song.",fontSize=12.sp,color=c.muted)
        Row(Modifier.horizontalScroll(rememberScrollState())){AudioEffects.KEYS.forEachIndexed{i,key->Chip(key,s.tuneKey==i){onChange(s.copy(tuneKey=i))}}}
        Row(Modifier.horizontalScroll(rememberScrollState())){AudioEffects.SCALES.forEachIndexed{i,scale->Chip(scale,s.tuneScale==i){onChange(s.copy(tuneScale=i))}}}
        LabSlider("Correction strength",s.tuneAmount*100,0f..100f,"%"){onChange(s.copy(tuneAmount=it/100))}
        LabSlider("Retune time",s.retuneMs,5f..500f,"ms"){onChange(s.copy(retuneMs=it))}
        LabSlider("Pitch shift",s.shiftSemitones,-12f..12f,"semitones"){onChange(s.copy(shiftSemitones=it))}
        Row(Modifier.horizontalScroll(rememberScrollState())){listOf("Warm vocal","Hard tune","Dream voice","Robot").forEach{name->TextButton(onClick={onChange(AudioEffects.SCENES.getValue(name))}){Text(name)}}}
        TextButton(onClick={onChange(mix.copy(enabled=true,tune=s.tune,tuneKey=s.tuneKey,tuneScale=s.tuneScale,tuneAmount=s.tuneAmount,retuneMs=s.retuneMs,shiftSemitones=s.shiftSemitones,preset="Voice mix"))}){Text("Copy playback effects to voice")}
        TextButton(onClick={showEffects=!showEffects}){Text(if(showEffects)"Hide voice effects" else "Edit voice filters & effects")}
    }
    if(showEffects)AudioEffectPanels(s,onChange)
    AudioLabCard("Render & save"){
        LabToggle("Include a two-second effects tail",tail){tail=it}
        if(renderJob?.isActive==true){LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth());TextButton(onClick={renderJob?.cancel()}){Text("Cancel render")}}
        else Button(enabled=selected!=null&&!recording&&s.enabled&&!exporter.busy,onClick={
            val source=selected!!;val settings=s;player.pause();progress=0f;renderJob=scope.launch{
                try{val target=File.createTempFile("voice-render-",".wav",context.cacheDir);renderVoiceWave(source,target,settings,tail){value->scope.launch{progress=value}};exporter.save(target,"MUGEN-voice-processed.wav");error=null}
                catch(cancelled:CancellationException){error="Render cancelled.";throw cancelled}
                catch(failure:Exception){error=failure.message?:"Voice rendering failed."}
                finally{renderJob=null}
            }
        }){Text("Render processed WAV")}
        if(exporter.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        (error?:exporter.error)?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }
    if(delete)AlertDialog(onDismissRequest={delete=false},title={Text("Delete this voice take?")},text={Text("Export it first if you want to keep a copy.")},confirmButton={TextButton(onClick={player.stop();selected?.delete();takes=capture.takes();selected=takes.firstOrNull();delete=false}){Text("Delete")}},dismissButton={TextButton(onClick={delete=false}){Text("Cancel")}})
}
