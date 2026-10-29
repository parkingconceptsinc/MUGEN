package com.parkingconcepts.mortimer

import android.media.*
import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.*
import kotlinx.coroutines.*

internal data class SynthSettings(val wave:Int=0,val octave:Int=4,val attack:Float=.015f,val decay:Float=.18f,val sustain:Float=.65f,val release:Float=.25f,
    val cutoff:Float=6500f,val resonance:Float=.707f,val detuneCents:Float=5f,val blend:Float=.3f,val vibratoHz:Float=5f,val vibratoDepth:Float=0f,
    val volume:Float=.35f,val bpm:Float=120f,val sequence:Boolean=false,val steps:List<Int> = listOf(60,-1,64,-1,67,-1,64,-1,57,-1,60,-1,64,-1,67,-1)) {
    fun clean()=copy(wave=wave.coerceIn(0,3),octave=octave.coerceIn(2,6),
        attack=attack.finiteRange(.002f,1f,.015f),decay=decay.finiteRange(.01f,2f,.18f),sustain=sustain.finiteRange(0f,1f,.65f),release=release.finiteRange(.01f,2f,.25f),
        cutoff=cutoff.finiteRange(80f,20000f,6500f),resonance=resonance.finiteRange(.5f,4f,.707f),detuneCents=detuneCents.finiteRange(0f,25f,5f),blend=blend.finiteRange(0f,1f,.3f),
        vibratoHz=vibratoHz.finiteRange(.1f,10f,5f),vibratoDepth=vibratoDepth.finiteRange(0f,1f,0f),volume=volume.finiteRange(0f,.8f,.35f),bpm=bpm.finiteRange(40f,240f,120f),
        steps=List(16){steps.getOrElse(it){-1}.coerceIn(-1,108)})
    private fun Float.finiteRange(min:Float,max:Float,fallback:Float)=if(isFinite())coerceIn(min,max) else fallback
}
internal class SynthEngine(private val rate:Int=48000){
    private data class Voice(val note:Int,var phase:Double=0.0,var second:Double=0.0,var level:Double=0.0,var stage:Int=0,var releasing:Boolean=false,var releaseLevel:Double=0.0)
    private val commands=ConcurrentLinkedQueue<Pair<Int,Boolean>>();private val voices=ArrayList<Voice>(8);private val filter=Biquad()
    private var last:SynthSettings?=null;private var lfo=0.0;private var seqFrames=0;private var sequenceWasOn=false;private var seqNote=-1
    var step=0;private set
    fun on(note:Int){commands.add(note.coerceIn(24,108) to true)}
    fun off(note:Int){commands.add(note to false)}
    private fun command(note:Int,on:Boolean){if(on){voices.filter{it.note==note}.forEach{it.releasing=true};if(voices.size>=8)voices.removeAt(voices.indices.minByOrNull { voices[it].level }?:0);voices.add(Voice(note))}else voices.filter{it.note==note}.forEach{it.releasing=true}}
    private fun blep(t:Double,dt:Double):Double=when{t<dt->{val x=t/dt;2*x-x*x-1};t>1-dt->{val x=(t-1)/dt;x*x+2*x+1};else->0.0}
    private fun osc(t:Double,dt:Double,wave:Int)=when(wave){1->2*t-1-blep(t,dt);2->(if(t<.5)1.0 else -1.0)+blep(t,dt)-blep((t+.5)%1,dt);3->1-4*abs(t-.5);else->sin(2*PI*t)}
    fun render(out:FloatArray,frames:Int,value:SynthSettings){
        require(frames<=out.size);val s=value.clean()
        if(last!=s){filter.set(Biquad.coefficients(2,s.cutoff.toDouble(),0.0,s.resonance.toDouble(),rate),last==null);last=s}
        while(true){val c=commands.poll()?:break;command(c.first,c.second)}
        if(!s.sequence&&sequenceWasOn){if(seqNote>=0)command(seqNote,false);seqFrames=0;step=0;seqNote=-1}
        if(s.sequence&&!sequenceWasOn){step=0;seqFrames=0;seqNote=s.steps[0];if(seqNote>=0)command(seqNote,true)};sequenceWasOn=s.sequence
        val stepLength=(rate*60.0/s.bpm/4).roundToInt().coerceAtLeast(1)
        for(i in 0 until frames){
            if(s.sequence){if(seqFrames==(stepLength*.65).toInt()&&seqNote>=0)command(seqNote,false);if(seqFrames>=stepLength){if(seqNote>=0)command(seqNote,false);step=(step+1)%16;seqFrames=0;seqNote=s.steps[step];if(seqNote>=0)command(seqNote,true)};seqFrames++}
            var sum=0.0;val vibrato=s.vibratoDepth*sin(lfo)
            val iterator=voices.iterator()
            while(iterator.hasNext()){
                val v=iterator.next()
                if(v.releasing&&v.stage!=3){v.releaseLevel=v.level;v.stage=3}
                v.level=when{v.releasing->max(0.0,v.level-v.releaseLevel/(rate*s.release));v.stage==0->{val n=min(1.0,v.level+1.0/(rate*s.attack));if(n>=1)v.stage=1;n};else->max(s.sustain.toDouble(),v.level-(1-s.sustain)/(rate*s.decay))}
                if(v.releasing&&v.level<=0){iterator.remove();continue}
                val hz=PitchScale.hz(v.note+vibrato);val dt=(hz/rate).coerceAtMost(.45);val dt2=(dt*2.0.pow(s.detuneCents/1200.0)).coerceAtMost(.45)
                sum+=(osc(v.phase,dt,s.wave)*(1-s.blend)+osc(v.second,dt2,s.wave)*s.blend)*v.level*.18
                v.phase=(v.phase+dt)%1;v.second=(v.second+dt2)%1
            }
            out[i]=(filter.sample(sum)*s.volume).coerceIn(-.95,.95).toFloat();lfo=(lfo+2*PI*s.vibratoHz/rate)%(2*PI)
        }
    }
}
internal class SynthPreview(context:Context,private val muted:Boolean=false):AutoCloseable{
    private val app=context.applicationContext
    @Volatile var settings=SynthSettings()
    @Volatile var effects=AudioEffects.Settings()
    @Volatile var active=false;private set
    @Volatile var error:String?=null;private set
    @Volatile var currentStep=0;private set
    private var engine=SynthEngine()
    @Volatile private var generation=0L
    fun noteOn(note:Int){if(!active)start();engine.on(note)}
    fun noteOff(note:Int){engine.off(note)}
    @Synchronized fun start(){if(active)return;active=true;error=null;engine=SynthEngine();val token=++generation;val generator=engine
        Thread({var track:AudioTrack?=null
            try{
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                val minimum=AudioTrack.getMinBufferSize(48000,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT)
                track=AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(maxOf(minimum*2,4096)).setTransferMode(AudioTrack.MODE_STREAM).build()
                check(track.state==AudioTrack.STATE_INITIALIZED){"Synth output could not initialize."};if(muted)track.setVolume(0f);track.play()
                val mono=FloatArray(256);val stereo=FloatArray(512);val pcm=ShortArray(512);val dsp=AudioDspEngine(48000,2,false)
                while(active&&generation==token){generator.render(mono,256,settings);currentStep=generator.step;for(i in mono.indices){stereo[i*2]=mono[i];stereo[i*2+1]=mono[i]};dsp.process(stereo,stereo.size,effects);for(i in pcm.indices)pcm[i]=(stereo[i]*32768).toInt().coerceIn(-32768,32767).toShort()
                    var at=0;while(at<pcm.size&&active&&generation==token){val n=track.write(pcm,at,pcm.size-at,AudioTrack.WRITE_BLOCKING);check(n>0){"Synth audio output failed ($n)."};at+=n}
                }
            }catch(failure:Throwable){if(generation==token)error=failure.message?:"Synth preview failed."}finally{runCatching{track?.stop()};track?.release();if(generation==token)active=false}
        },"MUGEN-synth").apply{isDaemon=true}.start()
    }
    @Synchronized fun stop(){active=false;generation++}
    override fun close(){stop()}
}
internal suspend fun renderSynthWave(target:File,settings:SynthSettings,effects:AudioEffects.Settings,bars:Int,onProgress:(Float)->Unit={}):File=withContext(Dispatchers.Default){
    require(bars in 1..8);val s=settings.clean().copy(sequence=true);val rate=48000;val frames=(rate*60.0/s.bpm*4*bars).roundToInt();val synth=SynthEngine(rate);val dsp=AudioDspEngine(rate,2,false)
    val mono=FloatArray(1024);val stereo=FloatArray(2048);val bytes=ByteArray(4096);val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);val job=currentCoroutineContext()
    try{RandomAccessFile(target,"rw").use { output->output.setLength(0);output.write(ByteArray(44));var at=0;var lastProgress=0L
        while(at<frames){job.ensureActive();val n=minOf(1024,frames-at);synth.render(mono,n,s);for(i in 0 until n){stereo[i*2]=mono[i];stereo[i*2+1]=mono[i]};dsp.process(stereo,n*2,effects);buffer.clear();for(i in 0 until n*2)buffer.putShort((stereo[i]*32768).toInt().coerceIn(-32768,32767).toShort());output.write(bytes,0,n*4);at+=n;val now=System.nanoTime();if(now-lastProgress>100_000_000L||at==frames){onProgress(at.toFloat()/frames);lastProgress=now}}
        waveHeader(output,rate,2,frames.toLong());output.fd.sync()
    };target}catch(failure:Throwable){target.delete();throw failure}
}
