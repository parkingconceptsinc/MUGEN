package com.parkingconcepts.mortimer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

@androidx.media3.common.util.UnstableApi
class AudioDspTest {
    private fun tone(hz:Double,amplitude:Double=.1,seconds:Double=1.0,sr:Int=48000,channels:Int=1)=FloatArray((sr*seconds).toInt()*channels){i->(amplitude*sin(2*PI*hz*(i/channels)/sr)).toFloat()}
    private fun rms(a:FloatArray,start:Int=a.size/2)=sqrt(a.drop(start).sumOf{it.toDouble()*it}/(a.size-start))
    private fun process(a:FloatArray,s:AudioEffects.Settings,channels:Int=1,sr:Int=48000):AudioDspEngine{val e=AudioDspEngine(sr,channels);var at=0;while(at<a.size){val n=minOf(960*channels,a.size-at);val block=a.copyOfRange(at,at+n);e.process(block,n,s);block.copyInto(a,at);at+=n};return e}
    @Test fun disabledProcessorIsByteIdenticalIncludingBoostAndBalance(){
        val pcm=shortArrayOf(-32768,32767,0,1,-1,1234,-2222,100)
        val input=ByteBuffer.allocateDirect(pcm.size*2).order(ByteOrder.nativeOrder());pcm.forEach{input.putShort(it)};input.flip()
        val p=ProAudioProcessor();p.settings=AudioEffects.Settings(enabled=false,preamp=12f,boost=2f,balance=1f,delayMix=.7f)
        p.configure(AudioProcessor.AudioFormat(48000,2,C.ENCODING_PCM_16BIT));p.flush();p.queueInput(input)
        val output=p.output.order(ByteOrder.nativeOrder());assertArrayEquals(pcm,ShortArray(pcm.size){output.short});p.reset()
    }
    @Test fun fragmentedPcmFramesAreReassembledAndResetBeforeConfigurationIsSafe(){
        val p=ProAudioProcessor();p.reset();p.configure(AudioProcessor.AudioFormat(48000,2,C.ENCODING_PCM_16BIT));p.flush()
        val original=ByteArray(24){(it*3).toByte()};val output=java.io.ByteArrayOutputStream()
        var at=0
        for(n in listOf(1,2,5,3,13)){p.queueInput(ByteBuffer.wrap(original,at,n));at+=n;val b=p.output;val bytes=ByteArray(b.remaining());b.get(bytes);output.write(bytes)}
        assertArrayEquals(original,output.toByteArray());p.reset()
    }
    @Test fun anInTuneVoiceIsNotCancelledByThePitchGrains(){
        val a=tone(440.0,.3,1.0);process(a,AudioEffects.Settings(enabled=true,autoHeadroom=false,limiter=false,tune=true));assertEquals(.3/sqrt(2.0),rms(a),.01)
    }
    @Test fun actualSoftwareBandBoostMatchesTheMeasuredFrequencyResponse(){
        val input=tone(1000.0,.02);val reference=rms(input);val s=AudioEffects.Settings(enabled=true,autoHeadroom=false,limiter=false,bands=List(10){if(it==5)12f else 0f})
        process(input,s);assertEquals(12.0,gainDb(rms(input)/reference),.2)
        assertEquals(12.0,audioEqResponse(s,1000.0),.05)
    }
    @Test fun filtersRejectTheirStopBandsAndPreserveThePassBand(){
        val hp=AudioEffects.Settings(enabled=true,highPass=true,highPassHz=100f,autoHeadroom=false,limiter=false)
        val low=tone(20.0);val high=tone(1000.0);process(low,hp);process(high,hp)
        assertTrue(rms(low)<.005);assertEquals(.1/sqrt(2.0),rms(high),.002)
        val cut=tone(12000.0);process(cut,hp.copy(highPass=false,lowPass=true,lowPassHz=3000f));assertTrue(rms(cut)<.005)
    }
    @Test fun compressorAndGateChangeActualSignalLevels(){
        val loud=tone(440.0,.8);process(loud,AudioEffects.Settings(enabled=true,compressor=true,threshold=-24f,ratio=4f,autoHeadroom=false,limiter=false));assertTrue(rms(loud) in .05.. .13)
        val quiet=tone(440.0,.00005);process(quiet,AudioEffects.Settings(enabled=true,gate=true,gateDb=-50f,autoHeadroom=false,limiter=false));assertTrue(rms(quiet)<.000001)
    }
    @Test fun limiterBoundsHotMultichannelSignalsAndInvalidSamples(){
        val a=FloatArray(48000*2){if(it%7==0)Float.NaN else if(it%2==0)2f else -2f};val s=AudioEffects.Settings(enabled=true,preamp=12f,boost=2f,autoHeadroom=false,ceiling=-1f)
        process(a,s,2);assertTrue(a.all{it.isFinite()&&abs(it)<=dbGain(-1.0)+.00001})
    }
    @Test fun headroomCompensationProtectsAnOverlappingTenBandCurve(){
        val a=tone(1000.0,.1);val e=process(a,AudioEffects.Settings(enabled=true,bands=List(10){6f},limiter=false))
        assertTrue(e.meters.headroomDb < -6f);assertTrue(rms(a)<.075)
    }
    @Test fun echoHasTheSelectedDelayAndItsFeedbackCrossesStereoChannels(){
        val a=FloatArray(48000*2);a[0]=.5f;process(a,AudioEffects.Settings(enabled=true,limiter=false,autoHeadroom=false,delayMix=.5f,delayMs=100f,delayFeedback=.5f),2)
        assertTrue((4799..4803).maxOf{abs(a[it*2])}>.24f)
        assertTrue((9599..9605).maxOf{abs(a[it*2+1])}>.12f)
        assertEquals(0f,a[2000],.00001f)
    }
    @Test fun reverbProducesADecayingTailInsteadOfChangingOnlyTheUi(){
        val a=FloatArray(96000);a[0]=.5f;process(a,AudioEffects.Settings(enabled=true,limiter=false,autoHeadroom=false,reverbMix=.5f,room=.6f))
        assertTrue(a.slice(1200..24000).any{abs(it)>.001f});assertTrue(rms(a,72000)<.0001)
    }
    @Test fun stereoWidthAndBalanceApplyCoherently(){
        val mono=FloatArray(2000){if(it%2==0).2f else -.1f};process(mono,AudioEffects.Settings(enabled=true,width=0f,autoHeadroom=false,limiter=false),2)
        for(i in 0 until mono.size step 2)assertEquals(mono[i],mono[i+1],.00001f)
        val pan=FloatArray(2000){.2f};process(pan,AudioEffects.Settings(enabled=true,balance=-1f,autoHeadroom=false,limiter=false),2)
        assertTrue(pan.filterIndexed{i,_->i%2==1}.all{it==0f});assertEquals(.2f,pan[1000],.00001f)
    }
    @Test fun yinFindsVocalFundamentalsAndRejectsSilenceAndNoise(){
        for(hz in listOf(65.41,110.0,220.0,440.0,466.16,880.0,987.77)){val d=VoicePitchDetector(48000);tone(hz,.3,.5).forEach{d.push(it.toDouble())};assertEquals(hz,d.hz,hz*.003);assertTrue(d.confidence>.9)}
        val silence=VoicePitchDetector(48000);repeat(48000){silence.push(0.0)};assertEquals(0.0,silence.hz,0.0)
        val noise=VoicePitchDetector(48000);val random=java.util.Random(123);repeat(48000){noise.push(random.nextDouble()*.2-.1)};assertEquals(0.0,noise.hz,0.0)
    }
    @Test fun quantizationRespectsTheSelectedKeyAndScale(){
        assertEquals(69,PitchScale.nearest(69.2,0,0));assertTrue(Math.floorMod(PitchScale.nearest(70.1,0,1),12) in setOf(0,2,4,5,7,9,11))
        assertTrue(Math.floorMod(PitchScale.nearest(66.9,2,3)-2,12) in setOf(0,2,4,7,9))
    }
    @Test fun pitchCorrectionMovesAnOutOfTuneVoiceToTheChosenNote(){
        val a=tone(452.0,.3,2.0);val s=AudioEffects.Settings(enabled=true,autoHeadroom=false,limiter=false,tune=true,retuneMs=5f)
        val e=process(a,s);assertEquals(440.0,e.meters.targetHz.toDouble(),.2)
        val detector=VoicePitchDetector(48000);a.takeLast(24000).forEach{detector.push(it.toDouble())}
        assertEquals(440.0,detector.hz,3.0)
    }
    @Test fun manualPitchShiftPreservesFrameCountAndChangesTheFundamental(){
        val a=tone(220.0,.3,2.0);val size=a.size;process(a,AudioEffects.Settings(enabled=true,autoHeadroom=false,limiter=false,shiftSemitones=12f,retuneMs=5f))
        val detector=VoicePitchDetector(48000);a.takeLast(24000).forEach{detector.push(it.toDouble())};assertEquals(size,a.size);assertEquals(440.0,detector.hz,5.0)
    }
    @Test fun everySceneStaysFiniteAtDifferentRatesAndChannelCounts(){
        for(sr in listOf(8000,44100,96000))for(ch in listOf(1,2,6))for(s in AudioEffects.SCENES.values){val a=tone(300.0,.4,.08,sr,ch);process(a,s,ch,sr);assertTrue(a.all{it.isFinite()&&abs(it)<=1})}
    }
    @Test fun analyzerReportsTheDominantFrequencyBand(){
        val e=process(tone(1000.0,.3),AudioEffects.Settings(enabled=true,limiter=false));assertEquals(5,e.meters.spectrum.indices.maxByOrNull{e.meters.spectrum[it]})
    }
}
