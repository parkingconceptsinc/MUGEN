package com.parkingconcepts.mortimer

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

@UnstableApi
@RunWith(AndroidJUnit4::class)
class AudioLabDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun tone(hz:Double=452.0,amplitude:Double=.2,seconds:Int=4):File=File.createTempFile("audio-qa-",".wav",context.cacheDir).apply{
        RandomAccessFile(this,"rw").use{out->out.write(ByteArray(44));val bytes=ByteArray(48000*seconds*2);val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);repeat(48000*seconds){b.putShort((sin(2*PI*hz*it/48000)*amplitude*32767).toInt().toShort())};out.write(bytes);waveHeader(out,48000,1,48000L*seconds)}
    }
    @Test fun media3PlaybackUsesTheRealSoftwareEqWithoutTakingAudioFocus(){
        val file=tone(1000.0,.02,2);val processor=ProAudioProcessor();processor.settings=AudioEffects.Settings(enabled=true,limiter=false,autoHeadroom=false,bands=List(10){if(it==5)12f else 0f})
        var player:androidx.media3.exoplayer.ExoPlayer?=null
        try{
            instrumentation.runOnMainSync{player=audioLabPreviewPlayer(context,processor,audioFocus=false).apply{volume=0f;setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)));prepare();play()}}
            val deadline=System.currentTimeMillis()+10_000;var peak=0f;var ended=false
            while(System.currentTimeMillis()<deadline&&!ended){Thread.sleep(30);peak=max(peak,processor.meters.peak);instrumentation.runOnMainSync{assertNull(player!!.playerError);ended=player!!.playbackState==Player.STATE_ENDED}}
            assertTrue("PCM processor never received playback",processor.configured);assertTrue("EQ did not change decoded audio: $peak",peak in .07f.. .09f);assertTrue(ended)
        }finally{instrumentation.runOnMainSync{player?.release()};file.delete()}
    }
    @Test fun voiceAndSynthRenderValidAudioAndDspRunsFasterThanRealtime()=runBlocking<Unit>{
        val input=tone(452.0,.3,2);val voice=File(context.cacheDir,"voice-qa-processed.wav");val synth=File(context.cacheDir,"synth-qa-sequence.wav")
        try{
            val settings=AudioEffects.Settings(enabled=true,autoHeadroom=false,tune=true,retuneMs=5f,limiter=true)
            renderVoiceWave(input,voice,settings,false);val info=inspectWave(voice);assertEquals(96000L,info.frames)
            val detector=VoicePitchDetector(48000);RandomAccessFile(voice,"r").use{r->r.seek(info.offset+48000*2);val bytes=ByteArray(48000*2);r.readFully(bytes);val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);while(b.hasRemaining())detector.push(b.short/32768.0)};assertEquals(440.0,detector.hz,3.0)
            renderSynthWave(synth,SynthSettings(sequence=true,wave=1),AudioEffects.SCENES.getValue("Wide room"),2);assertEquals(192000L,inspectWave(synth).frames)
            val dsp=AudioDspEngine(48000,2);val data=FloatArray(960){(.2*sin(2*PI*220*(it/2)/48000)).toFloat()};val busy=settings.copy(reverbMix=.25f,chorusMix=.2f,delayMix=.3f,compressor=true)
            repeat(30){dsp.process(data,data.size,busy)}
            val times=ArrayList<Long>();val start=System.nanoTime()
            repeat(200){for(i in data.indices)data[i]=(.2*sin(2*PI*220*(it*480+i/2)/48000)).toFloat();val t=System.nanoTime();dsp.process(data,data.size,busy);times.add(System.nanoTime()-t);assertTrue(data.all{it.isFinite()&&abs(it)<=1f})}
            val elapsed=(System.nanoTime()-start)/1e9;val p95=times.sorted()[189]/1e6
            File(context.cacheDir,"audio-lab-performance.json").writeText(JSONObject().put("audio_seconds",2).put("processing_seconds",elapsed).put("buffer_ms",10).put("dsp_p95_ms",p95).toString(2))
            assertTrue("DSP cannot keep up: $elapsed seconds for 2 seconds of audio",elapsed<2)
        }finally{input.delete();voice.delete();synth.delete()}
    }
    @Test fun deviceScannerUsesReadableProviderUrisAndLeavesTheLibraryUntouched()=runBlocking<Unit>{
        val before=LibraryStore.load(context);val root=File(context.getExternalFilesDir(null),"scan-qa-${System.nanoTime()}").apply{mkdirs()}
        val book=File(root,"Book with spaces.epub").apply{writeText("synthetic book")};File(root,"clip.mp4").writeBytes(byteArrayOf(1,2,3));File(root,"comic.cbz").writeBytes(byteArrayOf(4,5,6));val camera=File(root,"camera.3gp");instrumentation.context.assets.open("media-studio.mp4").use{input->camera.outputStream().use{input.copyTo(it)}}
        try{
            val first=scanDeviceFiles(context,emptyList(),listOf(root));assertEquals(4,first.items.size);assertEquals("videos",first.items.first{it.title==camera.name}.kind)
            val item=first.items.first{it.title==book.name};val bytes=context.contentResolver.openInputStream(item.androidUri)!!.use{it.readBytes()};assertEquals("synthetic book",String(bytes))
            val second=scanDeviceFiles(context,first.items,listOf(root));assertTrue(second.items.isEmpty());assertEquals(4,second.progress.duplicates);assertEquals(before,LibraryStore.load(context))
            assertThrows(IllegalArgumentException::class.java){androidx.core.content.FileProvider.getUriForFile(context,context.packageName+".fileprovider",File(context.filesDir,"private-secret.json"))}
        }finally{root.listFiles()?.forEach{it.delete()};root.delete()}
    }
    @androidx.test.filters.SdkSuppress(minSdkVersion=34)
    @Test fun audioLabControlsWorkOnTabletAndPhoneWithIsolatedSettingsAndMutedAudio(){
        val file=tone();val thread=HandlerThread("audio-lab-frames").apply{start()};val bitmap=AtomicReference<Bitmap?>()
        fun reader(w:Int,h:Int)=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,3).apply{setOnImageAvailableListener({r->runCatching{r.acquireLatestImage()}.getOrNull()?.use{image->
            val plane=image.planes[0];val padded=Bitmap.createBitmap(plane.rowStride/plane.pixelStride,h,Bitmap.Config.ARGB_8888);padded.copyPixelsFromBuffer(plane.buffer);val frame=Bitmap.createBitmap(padded,0,0,w,h);if(frame!==padded)padded.recycle();bitmap.getAndSet(frame)?.recycle()
        }},Handler(thread.looper))}
        var images=reader(1280,800);val manager=context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display=manager.createVirtualDisplay("MUGEN Audio Lab QA",1280,800,160,images.surface,DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION)
        val automation=instrumentation.uiAutomation;val oldInfo=automation.serviceInfo
        automation.serviceInfo=automation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        val prefsBefore=context.getSharedPreferences("mortimer_prefs",0).all.toMap();val libraryBefore=LibraryStore.load(context)
        fun nodes():List<AccessibilityNodeInfo>{automation.clearCache();val roots=automation.windowsOnAllDisplays.get(display.display.displayId).orEmpty().mapNotNull{it.root};val all=ArrayList<AccessibilityNodeInfo>()
            fun visit(n:AccessibilityNodeInfo,depth:Int){if(depth>40||all.size>5000)return;all.add(n);for(i in 0 until n.childCount)n.getChild(i)?.let{visit(it,depth+1)}};roots.forEach{visit(it,0)};return all
        }
        fun find(label:String):AccessibilityNodeInfo {val deadline=System.currentTimeMillis()+15_000;while(System.currentTimeMillis()<deadline){nodes().firstOrNull{it.text?.toString()==label||it.contentDescription?.toString()==label}?.let{return it};Thread.sleep(100)};error("Missing Audio Lab control: $label")}
        fun click(label:String){val n=find(label);val clickable=generateSequence(n){it.parent}.take(8).firstOrNull{it.isClickable}?:n;assertTrue("Could not click $label",clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(250)}
        fun screenshot(name:String){Thread.sleep(500);val done=CountDownLatch(1);Handler(thread.looper).post{bitmap.get()?.let{b->File(context.cacheDir,name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}};done.countDown()};assertTrue(done.await(5,TimeUnit.SECONDS))}
        fun launch(scan:Boolean=false){automation.adoptShellPermissionIdentity("android.permission.ACTIVITY_EMBEDDING");try{context.startActivity(Intent(context,AudioLabQaActivity::class.java).putExtra("qa_display",display.display.displayId).putExtra("fixture",file.path).putExtra("scan_mode",scan).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(display.display.displayId).toBundle())}finally{automation.dropShellPermissionIdentity()}}
        fun close(){instrumentation.runOnMainSync{AudioLabQaActivity.current.get()?.finish()};assertTrue(AudioLabQaActivity.destroyed.await(10,TimeUnit.SECONDS))}
        try{
            launch();find("AUDIO LAB");find("10-band equalizer")
            val fader=find("EQ 1000 Hz");assertTrue(fader.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,Bundle().apply{putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,6f)}));find("6.0");screenshot("audio-mix-tablet.png")
            click("Effects");find("Filters");click("High-pass filter");find("High-pass cutoff");screenshot("audio-effects-tablet.png")
            click("Voice");find("Voice studio");click("Play effects")
            val pitchDeadline=System.currentTimeMillis()+10_000;while(nodes().none{it.text?.toString()?.startsWith("Detected A4")==true}&&System.currentTimeMillis()<pitchDeadline)Thread.sleep(100)
            if(nodes().none{it.text?.toString()?.startsWith("Detected A4")==true}){screenshot("audio-voice-failed.png");error("Synthetic voice pitch was not detected. Controls: "+nodes().mapNotNull{it.text?.toString()}.joinToString())};click("Pause");screenshot("audio-voice-tablet.png")
            click("Synth");find("Polyphonic synth");click("Saw");screenshot("audio-synth-tablet.png")
            click("Presets");click("Save playback");val edit=nodes().first{it.isEditable};assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"QA scene")}));click("Save");find("QA scene");screenshot("audio-presets-tablet.png")
            click("Mix");val phone=reader(420,900);display.resize(420,900,160);display.surface=phone.surface;images.close();images=phone;Thread.sleep(900);find("AUDIO LAB");screenshot("audio-mix-phone.png")
            close();launch(scan=true);find("Scan device");screenshot("scan-device-phone.png");close()
            assertEquals(prefsBefore,context.getSharedPreferences("mortimer_prefs",0).all);assertEquals(libraryBefore,LibraryStore.load(context))
        }finally{close();automation.serviceInfo=oldInfo;display.release();images.close();thread.quitSafely();bitmap.getAndSet(null)?.recycle();file.delete()}
    }
}
