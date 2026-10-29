package com.parkingconcepts.mortimer

import kotlin.math.*

internal class RoomReverb(sr:Int,channels:Int){
    private class Comb(size:Int){val data=DoubleArray(size);var at=0;var low=0.0
        fun tick(x:Double,feedback:Double,damping:Double):Double {val out=data[at];low=out*(1-damping)+low*damping;data[at]=x+low*feedback;at=(at+1)%data.size;return out}}
    private val combs=Array(channels){ch->doubleArrayOf(.0297,.0371,.0411,.0437).map { Comb((sr*(it+ch*.00071)).toInt().coerceAtLeast(2)) }}
    fun sample(ch:Int,input:Double,room:Float,damping:Float):Double {var sum=0.0;for(c in combs[ch])sum+=c.tick(input,.45+room*.38,damping.toDouble());return sum*.25}
}
/** Audio-thread-owned state. UI snapshots are applied at buffer boundaries; state never crosses threads. */
internal class AudioDspEngine(val sampleRate:Int,val channels:Int,private val analyze:Boolean=true){
    init{require(sampleRate in 8000..192000&&channels in 1..8)}
    private val filters=Array(channels){Array(12){Biquad()}}
    private val delays=Array(channels){FractionalDelay((sampleRate*1.55).toInt()+8)}
    private val chorus=Array(channels){FractionalDelay((sampleRate*.04).toInt()+8)}
    private val reverb=RoomReverb(sampleRate,channels)
    private val pitch=if(channels<=2)VoicePitchShift(sampleRate,channels) else null
    private val analyzer=AudioAnalyzer(sampleRate)
    private val frame=DoubleArray(channels);private val dry=DoubleArray(channels);private val echo=DoubleArray(channels);private val crushed=DoubleArray(channels)
    private var state=AudioEffects.Settings();private var ready=false;private var gain=1.0;private var targetGain=1.0;private var headroom=0.0
    private var envelope=0.0;private var compGain=1.0;private var gateGain=1.0;private var limiterGain=1.0;private var bypassMix=0.0
    private var lfo=0.0;private var trem=0.0;private var robot=0.0;private var hold=0
    @Volatile var meters=AudioMeters(sampleRate=sampleRate,channels=channels);private set
    fun configure(value:AudioEffects.Settings){
        if(ready&&value==state)return
        state=value.sanitized();val s=state
        for(ch in 0 until channels){
            for(i in 0..9)filters[ch][i].set(Biquad.coefficients(0,AudioEffects.BANDS[i].toDouble(),s.bands[i].toDouble(),1.0,sampleRate),!ready)
            filters[ch][10].set(if(s.highPass)Biquad.coefficients(1,s.highPassHz.toDouble(),0.0,s.resonance.toDouble(),sampleRate) else doubleArrayOf(1.0,0.0,0.0,0.0,0.0),!ready)
            filters[ch][11].set(if(s.lowPass)Biquad.coefficients(2,s.lowPassHz.toDouble(),0.0,s.resonance.toDouble(),sampleRate) else doubleArrayOf(1.0,0.0,0.0,0.0,0.0),!ready)
        }
        var maxResponse=0.0
        if(s.autoHeadroom)for(i in 0..127){val hz=20*1000.0.pow(i/127.0);if(hz<sampleRate*.475)maxResponse=max(maxResponse,audioEqResponse(s,hz,sampleRate))}
        headroom=if(s.autoHeadroom)-maxResponse else 0.0
        targetGain=dbGain(s.preamp+20*log10(s.boost.toDouble())+headroom)
        if(!ready){gain=targetGain;bypassMix=if(s.enabled)1.0 else 0.0};ready=true
    }
    fun process(data:FloatArray,count:Int=data.size,settings:AudioEffects.Settings=state){
        require(count in 0..data.size&&count%channels==0);configure(settings)
        val s=state;var peak=0.0;var squares=0.0
        val attack=exp(-1.0/(sampleRate*s.attackMs*.001));val release=exp(-1.0/(sampleRate*s.releaseMs*.001));val limitRelease=exp(-1.0/(sampleRate*.08))
        val ceiling=dbGain(s.ceiling.toDouble());val gateLevel=dbGain(s.gateDb.toDouble());val drive=dbGain(s.driveDb.toDouble());val driveNorm=max(.001,tanh(drive))
        val delayFrames=(if(s.delaySync)60000.0/s.bpm*s.delayBeats else s.delayMs.toDouble()).coerceIn(10.0,1500.0)*sampleRate/1000
        var p=0
        while(p<count){
            var inputPeak=0.0
            for(ch in 0 until channels){val v=data[p+ch].toDouble().let {if(it.isFinite())it.coerceIn(-8.0,8.0)else 0.0};frame[ch]=v;dry[ch]=v;inputPeak=max(inputPeak,abs(v))}
            bypassMix+=((if(s.enabled)1.0 else 0.0)-bypassMix)*.004
            if(s.enabled||bypassMix>.00001){
                gain+=(targetGain-gain)*.002
                pitch?.frame(frame,s)
                inputPeak=0.0
                for(ch in 0 until channels){var v=frame[ch];for(filter in filters[ch])v=filter.sample(v);frame[ch]=v*gain;inputPeak=max(inputPeak,abs(frame[ch]))}
                envelope=if(inputPeak>envelope)attack*envelope+(1-attack)*inputPeak else release*envelope+(1-release)*inputPeak
                val over=max(0.0,gainDb(envelope)-s.threshold)
                val wantedComp=if(s.compressor)dbGain(-over*(1-1/s.ratio)+s.makeup)else 1.0
                val compRate=if(wantedComp<compGain)attack else release;compGain=compRate*compGain+(1-compRate)*wantedComp
                gateGain+=((if(!s.gate||envelope>gateLevel)1.0 else 0.0)-gateGain)*(if(envelope>gateLevel).01 else .0005)
                for(ch in 0 until channels){var v=frame[ch]*compGain*gateGain
                    if(s.driveMix>0)v+=(tanh(v*drive)/driveNorm-v)*s.driveMix
                    if(hold==0){val steps=2.0.pow(s.crusherBits-1);crushed[ch]=round(v*steps)/steps}
                    v+=(crushed[ch]-v)*s.crusherMix
                    chorus[ch].write(v)
                    if(s.chorusMix>0){val d=sampleRate*(.012+s.chorusDepthMs*.001*sin(lfo+ch*PI*.5));val wet=chorus[ch].read(d);v+=(wet-v)*s.chorusMix}
                    echo[ch]=delays[ch].read(delayFrames)
                    frame[ch]=v
                }
                for(ch in 0 until channels){val other=if(channels==2)1-ch else ch;delays[ch].write((frame[ch]+echo[other]*s.delayFeedback).coerceIn(-4.0,4.0));frame[ch]+=echo[ch]*s.delayMix
                    val room=reverb.sample(ch,frame[ch],s.room,s.damping);frame[ch]+=room*s.reverbMix
                    frame[ch]*=1-s.tremoloMix*(.5+.5*sin(trem))
                    frame[ch]=frame[ch]*(1-s.robotMix)+frame[ch]*sin(robot)*s.robotMix
                }
                if(channels==2){val mid=(frame[0]+frame[1])*.5;val side=(frame[0]-frame[1])*.5*s.width;frame[0]=(mid+side)*(1-max(0f,s.balance));frame[1]=(mid-side)*(1+min(0f,s.balance))}
                var outputPeak=0.0;for(v in frame)outputPeak=max(outputPeak,abs(v))
                val wantedLimit=if(s.limiter&&outputPeak>ceiling)ceiling/outputPeak else 1.0
                limiterGain=if(wantedLimit<limiterGain)wantedLimit else min(wantedLimit,limitRelease*limiterGain+(1-limitRelease))
                for(ch in 0 until channels){val v=frame[ch]*(if(s.limiter)limiterGain else 1.0);frame[ch]=dry[ch]+(v-dry[ch])*bypassMix}
            }
            var mono=0.0
            for(ch in 0 until channels){val v=frame[ch].let {if(it.isFinite())it.coerceIn(-1.0,.999969)else 0.0};data[p+ch]=v.toFloat();peak=max(peak,abs(v));squares+=v*v;mono+=v}
            if(analyze)analyzer.push(mono/channels)
            hold=(hold+1)%s.crusherHold;lfo=(lfo+2*PI*s.chorusRate/sampleRate)%(2*PI);trem=(trem+2*PI*s.tremoloHz/sampleRate)%(2*PI);robot=(robot+2*PI*s.robotHz/sampleRate)%(2*PI);p+=channels
        }
        meters=AudioMeters(sampleRate,channels,peak.toFloat(),sqrt(squares/max(1,count)).toFloat(),(-gainDb(compGain*limiterGain)).coerceAtLeast(0.0).toFloat(),headroom.toFloat(),pitch?.detectedHz?.toFloat()?:0f,pitch?.targetHz?.toFloat()?:0f,pitch?.confidence?.toFloat()?:0f,analyzer.levels)
    }
}
