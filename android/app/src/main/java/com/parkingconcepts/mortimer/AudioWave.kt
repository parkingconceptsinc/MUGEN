package com.parkingconcepts.mortimer

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*

internal const val MAX_WAVE_BYTES = 256L * 1024 * 1024

internal data class WaveInfo(val rate:Int,val channels:Int,val frames:Long,val offset:Long)
internal fun waveHeader(file:RandomAccessFile,rate:Int,channels:Int,frames:Long){
    require(rate in 8000..192000&&channels in 1..2&&frames>=0&&frames<=MAX_WAVE_BYTES/(channels*2))
    val size=(frames*channels*2).toInt();val h=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    h.put("RIFF".toByteArray()).putInt(size+36).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate*channels*2).putShort((channels*2).toShort()).putShort(16).put("data".toByteArray()).putInt(size)
    file.seek(0);file.write(h.array())
}
internal fun inspectWave(file:File):WaveInfo=RandomAccessFile(file,"r").use { input->
    require(input.length() in 44..(256L*1024*1024+65536)){"WAV is empty or exceeds 256 MiB."}
    fun tag():String {val b=ByteArray(4);input.readFully(b);return String(b,Charsets.US_ASCII)}
    fun int()=Integer.reverseBytes(input.readInt())
    require(tag()=="RIFF"){"Choose a PCM WAV file."};int();require(tag()=="WAVE")
    var rate=0;var channels=0;var offset=-1L;var bytes=0L
    var chunks=0
    while(input.filePointer+8<=input.length()&&chunks++<1024){
        val id=tag();val size=int().toLong() and 0xffffffffL;val start=input.filePointer
        require(size<=input.length()-start){"Truncated WAV chunk."}
        when(id){
            "fmt "->{require(size>=16);require(java.lang.Short.reverseBytes(input.readShort()).toInt()==1){"Only uncompressed PCM WAV is supported."};channels=java.lang.Short.reverseBytes(input.readShort()).toInt();rate=int();int();val align=java.lang.Short.reverseBytes(input.readShort()).toInt();val bits=java.lang.Short.reverseBytes(input.readShort()).toInt();require(channels in 1..2&&rate in 8000..192000&&bits==16&&align==channels*2){"Use 16-bit mono or stereo PCM WAV."}}
            "data"->{offset=start;bytes=size}
        }
        input.seek(start+size+(size and 1))
    }
    require(offset>=0&&rate>0&&bytes>0&&bytes%(channels*2)==0L){"WAV has no valid audio frames."}
    WaveInfo(rate,channels,bytes/(channels*2),offset)
}
internal suspend fun renderVoiceWave(source:File,target:File,settings:AudioEffects.Settings,tail:Boolean,onProgress:(Float)->Unit={}):File=withContext(Dispatchers.Default){
    require(source.canonicalFile != target.canonicalFile){"Choose a different output file; the original recording must be preserved."}
    val info=inspectWave(source);val job=currentCoroutineContext();val s=settings.sanitized();val dsp=AudioDspEngine(info.rate,info.channels,false)
    val latency=if(s.enabled&&(s.tune||kotlin.math.abs(s.shiftSemitones)>.001)) (info.rate*.028).toLong() else 0L
    val extra=if(tail&&s.enabled&&(s.delayMix>0||s.reverbMix>0))info.rate*2L else 0L
    val outputFrames=info.frames+extra;require(outputFrames<=MAX_WAVE_BYTES/(info.channels*2)){"The rendered WAV would exceed 256 MiB. Disable the effects tail or choose a shorter take."};val processingFrames=outputFrames+latency
    val data=FloatArray(1024*info.channels);val bytes=ByteArray(data.size*2);val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    try{
        RandomAccessFile(source,"r").use { input->RandomAccessFile(target,"rw").use { output->
            output.setLength(0);output.write(ByteArray(44));input.seek(info.offset);var processed=0L;var written=0L;var lastProgress=0L
            while(processed<processingFrames){job.ensureActive();val frames=minOf(1024L,processingFrames-processed).toInt();val sourceFrames=minOf(frames.toLong(),(info.frames-processed).coerceAtLeast(0)).toInt()
                if(sourceFrames>0)input.readFully(bytes,0,sourceFrames*info.channels*2)
                buffer.position(0);for(i in 0 until frames*info.channels)data[i]=if(i<sourceFrames*info.channels)buffer.short/32768f else 0f
                dsp.process(data,frames*info.channels,s);buffer.clear()
                for(f in 0 until frames)if(processed+f>=latency&&written<outputFrames){for(ch in 0 until info.channels)buffer.putShort((data[f*info.channels+ch]*32768).toInt().coerceIn(-32768,32767).toShort());written++}
                output.write(bytes,0,buffer.position());processed+=frames;val now=System.nanoTime();if(now-lastProgress>100_000_000L||processed==processingFrames){onProgress(processed.toFloat()/processingFrames);lastProgress=now}
            }
            waveHeader(output,info.rate,info.channels,written);output.fd.sync()
        }}
        target
    }catch(failure:Throwable){target.delete();throw failure}
}
