package com.parkingconcepts.mortimer

import kotlin.math.*

/** Independent implementation of YIN's normalized difference and local-minimum interpolation. */
internal class VoicePitchDetector(sampleRate:Int){
    private val decimation=max(1,(sampleRate+23999)/24000);private val sr=sampleRate.toDouble()/decimation
    private val ring=DoubleArray(1024);private val x=DoubleArray(1024);private val difference=DoubleArray(512)
    private val lowpass=1-exp(-2*PI*2200/sampleRate)
    private var low1=0.0;private var low2=0.0
    private var at=0;private var samples=0;private var dec=0
    var hz=0.0;private set
    var confidence=0.0;private set
    fun push(value:Double):Boolean {
        low1+=(value-low1)*lowpass;low2+=(low1-low2)*lowpass
        if(++dec<decimation)return false;dec=0
        ring[at]=low2;at=(at+1)%ring.size;samples++
        if(samples<ring.size||samples%512!=0)return false
        for(i in x.indices)x[i]=ring[(at+i)%ring.size]
        var power=0.0;for(v in x)power+=v*v
        if(power/x.size<.000016){hz=0.0;confidence=0.0;return true}
        val maxTau=min(510,(sr/60).toInt());val minTau=max(2,(sr/1000).toInt());var sum=0.0;difference[0]=1.0
        for(tau in 1..maxTau){var value=0.0;for(j in 0 until 512){val delta=x[j]-x[j+tau];value+=delta*delta};sum+=value;difference[tau]=if(sum>1e-12)value*tau/sum else 1.0}
        var best=-1
        for(tau in minTau until maxTau)if(difference[tau]<.16 && difference[tau]<=difference[tau+1]){best=tau;break}
        if(best<0){hz=0.0;confidence=0.0;return true}
        val left=difference[best-1];val mid=difference[best];val right=difference[best+1];val denominator=left-2*mid+right
        val refined=best+if(abs(denominator)>.000001)(.5*(left-right)/denominator).coerceIn(-.5,.5) else 0.0
        hz=sr/refined;confidence=(1-mid).coerceIn(0.0,1.0);return true
    }
}
internal object PitchScale {
    fun midi(hz:Double)=69+12*ln(hz/440.0)/ln(2.0)
    fun hz(note:Double)=440.0*2.0.pow((note-69)/12)
    fun nearest(note:Double,key:Int,scale:Int):Int {
        val intervals=when(scale){1->setOf(0,2,4,5,7,9,11);2->setOf(0,2,3,5,7,8,10);3->setOf(0,2,4,7,9);else->(0..11).toSet()}
        return (floor(note).toInt()-12..ceil(note).toInt()+12).filter { Math.floorMod(it-key,12) in intervals }.minByOrNull { abs(it-note) }?:note.roundToInt()
    }
    fun name(note:Int)=AudioEffects.KEYS[Math.floorMod(note,12)]+(note/12-1)
}
/** Two overlapping, interpolated delay grains change pitch without changing stream duration. */
internal class VoicePitchShift(private val sr:Int,private val channels:Int){
    private val detector=VoicePitchDetector(sr)
    private val delays=Array(channels){FractionalDelay((sr*.09).toInt()+8)}
    private var phase=.25;private var ratio=1.0;private var targetRatio=1.0;private var mix=0.0
    private var window=sr*.04;private var targetWindow=window
    var detectedHz=0.0;private set
    var targetHz=0.0;private set
    val confidence get()=detector.confidence
    fun frame(frame:DoubleArray,s:AudioEffects.Settings){
        var mono=0.0;for(v in frame)mono+=v;mono/=channels
        val wanted=s.tune||abs(s.shiftSemitones)>.001
        if(wanted&&detector.push(mono)){
            detectedHz=detector.hz
            val note=if(detectedHz>0)PitchScale.nearest(PitchScale.midi(detectedHz),s.tuneKey,s.tuneScale) else 0
            targetHz=if(detectedHz>0)if(s.tune)PitchScale.hz(note.toDouble()) else detectedHz*2.0.pow(s.shiftSemitones/12.0) else 0.0
            targetRatio=2.0.pow(s.shiftSemitones/12.0)*(if(s.tune&&detectedHz>0)(targetHz/detectedHz).pow(s.tuneAmount.toDouble()) else 1.0)
            // A whole number of periods separates the two grains. This avoids
            // comb cancellation and detuning at octave shifts on voiced input.
            targetWindow=if(detectedHz>0){val period=sr/detectedHz;period*(2*(sr*.04/(2*period)).roundToInt().coerceAtLeast(1))}else sr*.04
        }
        if(!s.tune)targetRatio=2.0.pow(s.shiftSemitones/12.0)
        if(!wanted){detectedHz=0.0;targetHz=0.0}
        val smooth=1-exp(-1.0/(sr*s.retuneMs*.001));ratio+=(targetRatio.coerceIn(.5,2.0)-ratio)*smooth
        mix+=((if(wanted)1.0 else 0.0)-mix)*.004
        window+=(targetWindow-window)*.0008
        val p2=(phase+.5)%1;val w1=sin(PI*phase).pow(2);val w2=1-w1
        for(ch in 0 until channels){val dry=frame[ch];delays[ch].write(dry);val shifted=delays[ch].read(sr*.008+phase*window)*w1+delays[ch].read(sr*.008+p2*window)*w2;frame[ch]=dry+(shifted-dry)*mix}
        phase+=(1-ratio)/window;phase-=floor(phase)
    }
}
