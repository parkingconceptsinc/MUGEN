package com.parkingconcepts.mortimer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class ProAudioProcessor : BaseAudioProcessor() {
    @Volatile var settings=AudioEffects.Settings()
    @Volatile var configured=false;private set
    internal val meters get()=engine?.meters?:AudioMeters()
    @Volatile private var engine:AudioDspEngine?=null
    private var samples=FloatArray(0)
    private var raw=ByteArray(0)
    private var rawView=ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder())
    private val partial=ByteArray(16)
    private var partialCount=0
    override fun onConfigure(input:AudioProcessor.AudioFormat):AudioProcessor.AudioFormat {
        if(input.encoding!=C.ENCODING_PCM_16BIT||input.channelCount !in 1..8||input.sampleRate !in 8000..192000)throw AudioProcessor.UnhandledAudioFormatException(input)
        return input
    }
    override fun onFlush(){
        partialCount=0
        if(inputAudioFormat.sampleRate !in 8000..192000||inputAudioFormat.channelCount !in 1..8){engine=null;configured=false;return}
        engine=AudioDspEngine(inputAudioFormat.sampleRate,inputAudioFormat.channelCount);configured=true
    }
    override fun onReset(){engine=null;samples=FloatArray(0);raw=ByteArray(0);rawView=ByteBuffer.wrap(raw);partialCount=0;configured=false}
    override fun queueInput(inputBuffer:ByteBuffer){
        if(!inputBuffer.hasRemaining())return
        val frameBytes=inputAudioFormat.channelCount*2
        val total=partialCount+inputBuffer.remaining()
        if(raw.size<total){raw=ByteArray((total+4095)/4096*4096);rawView=ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder())}
        partial.copyInto(raw,0,0,partialCount);inputBuffer.get(raw,partialCount,inputBuffer.remaining())
        val complete=total/frameBytes*frameBytes;partialCount=total-complete
        raw.copyInto(partial,0,complete,total)
        if(complete==0)return
        val count=complete/2;if(samples.size<count)samples=FloatArray(count)
        rawView.clear();rawView.limit(complete);for(i in 0 until count)samples[i]=rawView.short/32768f
        val out=replaceOutputBuffer(complete).order(ByteOrder.nativeOrder());val s=settings
        engine?.process(samples,count,s)
        if(!s.enabled)out.put(raw,0,complete) else for(i in 0 until count)out.putShort((samples[i]*32768f).toInt().coerceIn(-32768,32767).toShort())
        out.flip()
    }
}
