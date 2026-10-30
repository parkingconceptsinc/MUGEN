package com.parkingconcepts.mortimer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import kotlin.math.*
import org.json.JSONObject

internal fun audioNumber(value:Float,unit:String="",digits:Int=1)=java.lang.String.format(java.util.Locale.ROOT,"%.${digits}f%s",value,unit)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun EqualizerSection(modifier:Modifier,player:Player?=null,persistSettings:Boolean=true,qaTake:java.io.File?=null){
    val context=LocalContext.current;val c=LocalMortimerColors.current;val scope=rememberCoroutineScope()
    var s by remember{mutableStateOf(if(persistSettings)AudioEffects.load(context) else AudioEffects.Settings())};var voice by remember{mutableStateOf(if(persistSettings)AudioEffects.loadVoice(context) else AudioEffects.SCENES.getValue("Warm vocal").copy(tune=true))}
    // Keep completed renders and picker callbacks alive while switching Audio Lab tabs.
    val voiceExporter=rememberWaveExporter()
    val synthExporter=rememberWaveExporter()
    var tab by remember{mutableStateOf("Mix")};var meters by remember{mutableStateOf(AudioMeters())}
    var custom by remember{mutableStateOf(if(persistSettings)AudioEffects.loadUserPresets(context) else emptyMap())};var naming by remember{mutableStateOf<String?>(null)}
    var presetBusy by remember { mutableStateOf(false) }
    var presetError by remember { mutableStateOf<String?>(null) }
    var presetNotice by remember { mutableStateOf<String?>(null) }
    var exportJson by rememberSaveable { mutableStateOf<String?>(null) }
    fun storePresets(next:Map<String,AudioEffects.Settings>,saved:()->Unit={}) {
        if(presetBusy)return
        presetBusy=true;presetError=null;presetNotice=null
        scope.launch {
            try {
                if(persistSettings)withContext(Dispatchers.IO){AudioEffects.saveUserPresets(context,next)}
                custom=next;saved()
            } catch(cancelled:CancellationException){throw cancelled}
            catch(failure:Exception){presetError=failure.message?:"Could not save presets."}
            finally{presetBusy=false}
        }
    }
    val importPresets=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri==null)presetBusy=false else scope.launch {
            try {
                val incoming=withContext(Dispatchers.IO){
                    val job=currentCoroutineContext()
                    val text=context.contentResolver.openInputStream(uri)?.use{it.readBytesLimited(256*1024){job.ensureActive()}.toString(Charsets.UTF_8)}?:error("Could not read this preset file.")
                    AudioEffects.importPresetBundle(text)
                }
                val merged=AudioEffects.mergePresetBundle(custom,incoming)
                if(persistSettings)withContext(Dispatchers.IO){AudioEffects.saveUserPresets(context,merged)}
                custom=merged;presetError=null;presetNotice="Imported ${incoming.size} presets. Existing names were preserved."
            }catch(cancelled:CancellationException){throw cancelled}
            catch(failure:Exception){presetError=failure.message?:"Could not import presets."}
            finally{presetBusy=false}
        }
    }
    val exportPresets=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        val snapshot=exportJson
        if(uri==null||snapshot==null){presetBusy=false;exportJson=null} else scope.launch {
            try {
                withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri,"wt")?.use{it.write(snapshot.toByteArray(Charsets.UTF_8))}?:error("Could not open the destination.")}
                presetError=null;presetNotice="Preset backup saved."
            }catch(cancelled:CancellationException){throw cancelled}
            catch(failure:Exception){presetError=failure.message?:"Could not export presets."}
            finally{presetBusy=false;exportJson=null}
        }
    }
    var slotA by remember{mutableStateOf(s)};var slotB by remember{mutableStateOf(s)};var undo by remember{mutableStateOf<AudioEffects.Settings?>(null)}
    val latest by rememberUpdatedState(s);val latestVoice by rememberUpdatedState(voice)
    LaunchedEffect(s){if(persistSettings){AudioEffects.apply(s);delay(180);AudioEffects.save(context,s)}}
    LaunchedEffect(voice){if(persistSettings){delay(180);AudioEffects.saveVoice(context,voice)}}
    DisposableEffect(Unit){onDispose{if(persistSettings){AudioEffects.save(context,latest);AudioEffects.saveVoice(context,latestVoice)}}}
    LaunchedEffect(Unit){while(true){meters=if(persistSettings)AudioEffects.processor.meters else AudioMeters();delay(100)}}
    fun change(next:AudioEffects.Settings){s=next.sanitized().copy(enabled=true,preset="Custom")}
    Column(modifier){
        Row(Modifier.fillMaxWidth().padding(bottom=8.dp),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){Text("AUDIO LAB",fontSize=11.sp,letterSpacing=2.sp,color=c.accent);Text("Shape your sound",fontSize=21.sp,fontWeight=FontWeight.Bold,color=c.text)}
            if(tab !in listOf("Voice","Synth"))Column(horizontalAlignment=Alignment.End){Text(if(s.enabled)"PROCESSING" else "BYPASS",fontSize=10.sp,color=c.muted);Switch(s.enabled,onCheckedChange={s=s.copy(enabled=it)})}
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            listOf("Mix","Effects","Voice","Synth","Presets").forEach{label->Chip(label,tab==label){tab=label}}
        }
        if(tab!="Voice"&&voiceExporter.pending?.isFile==true)TextButton(onClick={tab="Voice"}){Text(if(voiceExporter.busy)"Voice WAV is saving…" else "Voice WAV ready · review or save again")}
        if(tab!="Synth"&&synthExporter.pending?.isFile==true)TextButton(onClick={tab="Synth"}){Text(if(synthExporter.busy)"Synth WAV is saving…" else "Synth WAV ready · review or save again")}
        Spacer(Modifier.height(10.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            when(tab){
                "Mix"->{
                    AudioLabCard("Master output"){
                        AudioLevelMeter(meters)
                        Text(if(meters.sampleRate>0)"${meters.sampleRate/1000f} kHz · ${meters.channels} channels · ${s.preset}" else "Play music or a video to monitor the output.",color=c.muted,fontSize=12.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState())){
                            TextButton(onClick={s=slotA}){Text("A")};TextButton(onClick={slotA=s}){Text("Store A")}
                            TextButton(onClick={s=slotB}){Text("B")};TextButton(onClick={slotB=s}){Text("Store B")}
                            TextButton(onClick={undo=s;s=AudioEffects.Settings(enabled=s.enabled)}){Text("Reset mix")}
                            if(undo!=null)TextButton(onClick={s=undo!!;undo=null}){Text("Undo reset")}
                        }
                    }
                    AudioLabCard("10-band equalizer"){
                        EqResponse(s)
                        BoxWithConstraints(Modifier.fillMaxWidth()){
                            val bandWidth=maxOf(54.dp,maxWidth/10)
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())){AudioEffects.BANDS.forEachIndexed{i,hz->
                                EqFader(hz,s.bands[i],bandWidth){value->change(s.copy(bands=s.bands.mapIndexed{j,v->if(i==j)value else v}))}
                            }}
                        }
                        Text("Adjustments enable processing. Tap a band's value to reset it.",color=c.muted,fontSize=12.sp)
                    }
                    AudioLabCard("Gain & stereo"){
                        LabSlider("Preamp",s.preamp,-24f..12f,"dB"){change(s.copy(preamp=it))}
                        LabToggle("Auto headroom",s.autoHeadroom){change(s.copy(autoHeadroom=it))}
                        Text("EQ headroom compensation: ${audioNumber(meters.headroomDb," dB")}",color=c.muted,fontSize=12.sp)
                        LabSlider("Output boost",(s.boost-1)*100,0f..100f,"%"){change(s.copy(boost=1+it/100))}
                        LabSlider("Balance",s.balance*100,-100f..100f,"%",labelValue=when{abs(s.balance)<.01->"Center";s.balance<0->"Left ${(-s.balance*100).roundToInt()}%";else->"Right ${(s.balance*100).roundToInt()}%"}){change(s.copy(balance=it/100))}
                        LabSlider("Stereo width",s.width*100,0f..200f,"%"){change(s.copy(width=it/100))}
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())){AudioEffects.PRESETS.forEach{(name,bands)->TextButton(onClick={s=s.copy(enabled=true,preset=name,bands=bands)}){Text(name)}}}
                }
                "Effects"->AudioEffectPanels(s,::change)
                "Voice"->VoiceStudioPanel(voice,{voice=it.sanitized()},s,onStartAudio={player?.pause()},initialTake=qaTake,quiet=!persistSettings,waveExporter=voiceExporter)
                "Synth"->SynthStudioPanel(s,onStartAudio={player?.pause()},persistSettings=persistSettings,quiet=!persistSettings,waveExporter=synthExporter)
                "Presets"->{
                    AudioLabCard("Playback scenes"){
                        Row(Modifier.horizontalScroll(rememberScrollState())){listOf("Clean master","Lo-fi tape","Wide room","Dub echo","Night listening","Robot").forEach{name->TextButton(onClick={s=AudioEffects.SCENES.getValue(name)}){Text(name)}}}
                    }
                    AudioLabCard("Voice scenes"){
                        Row(Modifier.horizontalScroll(rememberScrollState())){listOf("Warm vocal","Hard tune","Dream voice","Robot").forEach{name->TextButton(onClick={voice=AudioEffects.SCENES.getValue(name);tab="Voice"}){Text(name)}}}
                    }
                    AudioLabCard("Your complete presets"){
                        Text("Presets store EQ, filters, dynamics, stereo, pitch and effects together.",fontSize=12.sp,color=c.muted)
                        Row{TextButton(enabled=!presetBusy,onClick={naming="Playback"}){Text("Save playback")};TextButton(enabled=!presetBusy,onClick={naming="Voice"}){Text("Save voice")}}
                        Row {
                            TextButton(enabled=!presetBusy,onClick={
                                presetBusy=true;presetError=null;presetNotice=null
                                runCatching{importPresets.launch(arrayOf("application/json","text/plain"))}.onFailure{presetBusy=false;presetError="No document picker is available."}
                            }){Text("Import presets")}
                            TextButton(enabled=!presetBusy&&custom.isNotEmpty(),onClick={
                                runCatching{AudioEffects.exportPresetBundle(custom)}.onSuccess{json->
                                    exportJson=json;presetBusy=true;presetError=null;presetNotice=null
                                    runCatching{exportPresets.launch("MUGEN-audio-presets.json")}.onFailure{presetBusy=false;exportJson=null;presetError="No document picker is available."}
                                }.onFailure{presetError=it.message}
                            }){Text("Export backup")}
                        }
                        if(presetBusy)LinearProgressIndicator(Modifier.fillMaxWidth())
                        presetError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                        presetNotice?.let{Text(it,color=c.muted)}
                        custom.forEach{(name,value)->
                            Text(name,color=c.text,fontWeight=FontWeight.SemiBold)
                            Row{TextButton(onClick={s=value.copy(preset=name)}){Text("Playback")};TextButton(onClick={voice=value.copy(preset=name);tab="Voice"}){Text("Voice")};TextButton(enabled=!presetBusy,onClick={storePresets(custom-name)}){Text("Delete")}}
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
    naming?.let{destination->var name by remember{mutableStateOf("")};var error by remember{mutableStateOf<String?>(null)}
        AlertDialog(onDismissRequest={naming=null},title={Text("Save $destination preset")},text={Column{OutlinedTextField(name,{name=it.take(48)},label={Text("Preset name")},singleLine=true);(error?:presetError)?.let{Text(it,color=MaterialTheme.colorScheme.error)}}},confirmButton={TextButton(enabled=!presetBusy,onClick={
            val n=name.trim();if(n.isBlank())error="Enter a name." else if(n in custom)error="That name already exists. Choose another name." else if(custom.size>=32)error="Delete a preset before adding another (32 maximum)." else {storePresets(custom+(n to (if(destination=="Voice")voice else s).copy(preset=n))){naming=null}}
        }){Text("Save")}},dismissButton={TextButton(onClick={naming=null}){Text("Cancel")}})
    }
}
@Composable internal fun AudioLabCard(title:String,content:@Composable ColumnScope.()->Unit){val c=LocalMortimerColors.current
    Column(Modifier.fillMaxWidth().background(c.panel,RoundedCornerShape(16.dp)).padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
        Text(title,color=c.text,fontWeight=FontWeight.SemiBold,fontSize=15.sp);content()
    }
}
@Composable internal fun LabToggle(label:String,value:Boolean,onChange:(Boolean)->Unit){val c=LocalMortimerColors.current
    Row(Modifier.fillMaxWidth().toggleable(value=value,role=Role.Switch,onValueChange=onChange),verticalAlignment=Alignment.CenterVertically){Text(label,color=c.text,modifier=Modifier.weight(1f));Switch(value,onCheckedChange=null)}
}
@Composable internal fun LabSlider(label:String,value:Float,range:ClosedFloatingPointRange<Float>,unit:String="",labelValue:String?=null,onChange:(Float)->Unit){val c=LocalMortimerColors.current
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(label,color=c.text,fontSize=13.sp,modifier=Modifier.weight(1f));Text(labelValue?:audioNumber(value,if(unit.isEmpty())"" else " $unit"),color=c.accent,fontSize=12.sp)}
    Slider(value.coerceIn(range),onValueChange=onChange,valueRange=range,modifier=Modifier.height(36.dp).semantics{contentDescription=label})
}
@Composable private fun EqFader(hz:Int,value:Float,width:androidx.compose.ui.unit.Dp=54.dp,onChange:(Float)->Unit){val c=LocalMortimerColors.current;val latest by rememberUpdatedState(onChange)
    Column(Modifier.width(width),horizontalAlignment=Alignment.CenterHorizontally){
        Text(if(hz>=1000)"${hz/1000}k" else "$hz",color=c.muted,fontSize=11.sp)
        Canvas(Modifier.width(48.dp).height(190.dp).semantics{contentDescription="EQ $hz Hz";progressBarRangeInfo=ProgressBarRangeInfo(value,-12f..12f);setProgress{latest(it.coerceIn(-12f,12f));true}}
            .pointerInput(Unit){fun set(y:Float){latest(((1-(y-12)/(size.height-24))*24-12).coerceIn(-12f,12f).let{(it*2).roundToInt()/2f})};detectDragGestures(onDragStart={set(it.y)},onDrag={change,_->change.consume();set(change.position.y)})}){
            val center=size.width/2;val top=12f;val bottom=size.height-12;val y=top+(12-value)/24*(bottom-top)
            drawLine(c.line,Offset(center,top),Offset(center,bottom),strokeWidth=5f)
            drawLine(c.accent.copy(alpha=.65f),Offset(center,size.height/2),Offset(center,y),strokeWidth=5f)
            drawLine(c.muted,Offset(center-8,size.height/2),Offset(center+8,size.height/2),strokeWidth=1f)
            drawCircle(c.accent,9f,Offset(center,y))
        }
        Text(audioNumber(value,digits=1),color=c.text,fontSize=11.sp,modifier=Modifier.clickable{onChange(0f)}.padding(5.dp))
    }
}
@Composable private fun EqResponse(s:AudioEffects.Settings){val c=LocalMortimerColors.current
    Canvas(Modifier.fillMaxWidth().height(120.dp)){
        for(db in listOf(-12,0,12)){val y=size.height/2-db/36f*size.height;drawLine(c.line,Offset(0f,y),Offset(size.width,y),strokeWidth=1f)}
        val path=Path();for(i in 0..140){val hz=20*1000.0.pow(i/140.0);val db=audioEqResponse(s,hz).toFloat().coerceIn(-18f,18f);val x=i/140f*size.width;val y=size.height/2-db/36*size.height;if(i==0)path.moveTo(x,y)else path.lineTo(x,y)}
        drawPath(path,if(s.enabled)c.accent else c.muted,style=Stroke(3f))
    }
}
@Composable internal fun AudioLevelMeter(m:AudioMeters){val c=LocalMortimerColors.current
    Canvas(Modifier.fillMaxWidth().height(65.dp)){
        val gap=5f;val w=(size.width-gap*9)/10
        m.spectrum.forEachIndexed{i,db->val h=((db+72)/72).coerceIn(0f,1f)*size.height;drawRect(c.panel2,Offset(i*(w+gap),0f),androidx.compose.ui.geometry.Size(w,size.height));drawRect(c.accent.copy(alpha=.75f),Offset(i*(w+gap),size.height-h),androidx.compose.ui.geometry.Size(w,h))}
    }
    Text("Peak ${audioNumber(gainDb(m.peak.toDouble()).toFloat()," dBFS")} · RMS ${audioNumber(gainDb(m.rms.toDouble()).toFloat()," dBFS")} · GR ${audioNumber(m.reductionDb," dB")}",color=c.muted,fontSize=11.sp)
}
@Composable internal fun AudioEffectPanels(s:AudioEffects.Settings,onChange:(AudioEffects.Settings)->Unit){
    AudioLabCard("Filters"){
        LabToggle("High-pass filter",s.highPass){onChange(s.copy(enabled=true,highPass=it))};if(s.highPass)LabSlider("High-pass cutoff",s.highPassHz,20f..1200f,"Hz"){onChange(s.copy(highPassHz=it))}
        LabToggle("Low-pass filter",s.lowPass){onChange(s.copy(enabled=true,lowPass=it))};if(s.lowPass)LabSlider("Low-pass cutoff",s.lowPassHz,1500f..22000f,"Hz"){onChange(s.copy(lowPassHz=it))}
        if(s.highPass||s.lowPass)LabSlider("Filter resonance",s.resonance,.5f..2f,"Q"){onChange(s.copy(resonance=it))}
    }
    AudioLabCard("Dynamics"){
        LabToggle("Compressor",s.compressor){onChange(s.copy(enabled=true,compressor=it))}
        if(s.compressor){LabSlider("Threshold",s.threshold,-60f..0f,"dB"){onChange(s.copy(threshold=it))};LabSlider("Ratio",s.ratio,1f..20f,":1"){onChange(s.copy(ratio=it))};LabSlider("Attack",s.attackMs,.5f..100f,"ms"){onChange(s.copy(attackMs=it))};LabSlider("Release",s.releaseMs,20f..1000f,"ms"){onChange(s.copy(releaseMs=it))};LabSlider("Makeup gain",s.makeup,0f..12f,"dB"){onChange(s.copy(makeup=it))}}
        LabToggle("Noise gate",s.gate){onChange(s.copy(enabled=true,gate=it))};if(s.gate)LabSlider("Gate threshold",s.gateDb,-90f..-10f,"dB"){onChange(s.copy(gateDb=it))}
        LabToggle("Peak limiter",s.limiter){onChange(s.copy(limiter=it))};if(s.limiter)LabSlider("Limiter ceiling",s.ceiling,-12f..-.1f,"dBFS"){onChange(s.copy(ceiling=it))}
    }
    AudioLabCard("Space & motion"){
        LabSlider("Delay mix",s.delayMix*100,0f..70f,"%"){onChange(s.copy(enabled=true,delayMix=it/100))}
        if(s.delayMix>0){
            LabToggle("Sync delay to tempo",s.delaySync){onChange(s.copy(delaySync=it))}
            if(s.delaySync){
                LabSlider("Tempo",s.bpm,40f..240f,"BPM"){onChange(s.copy(bpm=it))}
                Row(Modifier.horizontalScroll(rememberScrollState())){
                    listOf(.25f,.5f,.75f,1f,1.5f).forEach{v->TextButton(onClick={onChange(s.copy(delayBeats=v))}){Text("$v beat")}}
                }
            } else LabSlider("Delay time",s.delayMs,10f..1500f,"ms"){onChange(s.copy(delayMs=it))}
            LabSlider("Feedback",s.delayFeedback*100,0f..85f,"%"){onChange(s.copy(delayFeedback=it/100))}
        }
        LabSlider("Room reverb",s.reverbMix*100,0f..60f,"%"){onChange(s.copy(enabled=true,reverbMix=it/100))};if(s.reverbMix>0){LabSlider("Room size",s.room*100,0f..100f,"%"){onChange(s.copy(room=it/100))};LabSlider("Damping",s.damping*100,0f..95f,"%"){onChange(s.copy(damping=it/100))}}
        LabSlider("Chorus mix",s.chorusMix*100,0f..70f,"%"){onChange(s.copy(enabled=true,chorusMix=it/100))};if(s.chorusMix>0){LabSlider("Chorus rate",s.chorusRate,.1f..3f,"Hz"){onChange(s.copy(chorusRate=it))};LabSlider("Chorus depth",s.chorusDepthMs,.5f..8f,"ms"){onChange(s.copy(chorusDepthMs=it))}}
    }
    AudioLabCard("Character"){
        LabSlider("Saturation mix",s.driveMix*100,0f..100f,"%"){onChange(s.copy(enabled=true,driveMix=it/100))};if(s.driveMix>0)LabSlider("Drive",s.driveDb,0f..24f,"dB"){onChange(s.copy(driveDb=it))}
        LabSlider("Lo-fi mix",s.crusherMix*100,0f..100f,"%"){onChange(s.copy(enabled=true,crusherMix=it/100))};if(s.crusherMix>0){LabSlider("Bit depth",s.crusherBits.toFloat(),4f..16f,"bits"){onChange(s.copy(crusherBits=it.roundToInt()))};LabSlider("Sample hold",s.crusherHold.toFloat(),1f..16f,"samples"){onChange(s.copy(crusherHold=it.roundToInt()))}}
        LabSlider("Tremolo depth",s.tremoloMix*100,0f..100f,"%"){onChange(s.copy(enabled=true,tremoloMix=it/100))};if(s.tremoloMix>0)LabSlider("Tremolo rate",s.tremoloHz,.2f..20f,"Hz"){onChange(s.copy(tremoloHz=it))}
        LabSlider("Robot mix",s.robotMix*100,0f..100f,"%"){onChange(s.copy(enabled=true,robotMix=it/100))};if(s.robotMix>0)LabSlider("Robot carrier",s.robotHz,20f..400f,"Hz"){onChange(s.copy(robotHz=it))}
    }
}
