package com.parkingconcepts.mortimer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Only starts after the user taps Record and grants microphone access. No background service. */
internal class VoiceCapture(context:Context):AutoCloseable {
    private val app=context.applicationContext
    @Volatile var active=false;private set
    @Volatile private var running=false
    @Volatile var seconds=0f;private set
    @Volatile var peak=0f;private set
    @Volatile var error:String?=null;private set
    @Volatile var completed:File?=null;private set
    @android.annotation.SuppressLint("MissingPermission")
    fun start(){
        check(!active){"A recording is already finishing."}
        check(ContextCompat.checkSelfPermission(app,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){"Allow microphone access to record your voice."}
        val dir=File(app.filesDir,"voice_takes").apply { mkdirs() }
        require((dir.listFiles()?.count { it.extension=="wav" }?:0) < 10){"Ten takes are saved. Export or delete a take before recording another."}
        active=true;running=true;seconds=0f;peak=0f;error=null;completed=null
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val id=UUID.randomUUID().toString();val temp=File(dir,"$id.tmp");val destination=File(dir,"$id.wav");var recorder:AudioRecord?=null
            try{
                val rate=listOf(48000,44100).firstOrNull { AudioRecord.getMinBufferSize(it,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)>0 }?:error("No microphone format is available.")
                val minimum=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
                recorder=AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION).setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(maxOf(minimum*2,rate/5)).build()
                check(recorder.state==AudioRecord.STATE_INITIALIZED){"Microphone could not initialize."}
                val samples=ShortArray(rate/50);val bytes=ByteArray(samples.size*2);val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);var frames=0L
                RandomAccessFile(temp,"rw").use { output->
                    output.write(ByteArray(44));recorder.startRecording();check(recorder.recordingState==AudioRecord.RECORDSTATE_RECORDING){"Microphone is not recording."}
                    while(running&&frames<rate*300L){val count=recorder.read(samples,0,samples.size,AudioRecord.READ_BLOCKING);if(count<0){if(running)error="Recording interrupted ($count). Captured audio was saved.";break};if(count==0)continue
                        buffer.clear();var maximum=0;for(i in 0 until count){buffer.putShort(samples[i]);maximum=maxOf(maximum,kotlin.math.abs(samples[i].toInt()))};output.write(bytes,0,count*2);frames+=count;seconds=frames.toFloat()/rate;peak=maximum/32768f
                    }
                    check(frames>0){"No audio was captured."};waveHeader(output,rate,1,frames);output.fd.sync()
                }
                check(temp.renameTo(destination)){"Could not save this take."};completed=destination
            }catch(failure:Throwable){temp.delete();error=failure.message?:"Could not record this take."}
            finally{runCatching { recorder?.stop() };recorder?.release();running=false;active=false;peak=0f}
        },"MUGEN-voice-capture").apply { isDaemon=true }.start()
    }
    fun stop(){running=false}
    override fun close(){stop()}
    fun takes():List<File> = File(app.filesDir,"voice_takes").listFiles()?.filter { it.extension=="wav" }?.sortedByDescending { it.lastModified() }?:emptyList()
}
