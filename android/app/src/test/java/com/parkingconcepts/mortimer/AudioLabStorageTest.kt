package com.parkingconcepts.mortimer

import android.net.Uri
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class AudioLabStorageTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Test fun fullPresetsRoundTripAndLegacyBandPresetsMigrate(){
        val original=AudioEffects.SCENES.getValue("Dream voice").copy(bands=List(10){it.toFloat()-5},crusherMix=.3f,gate=true)
        assertEquals(original,AudioEffects.Settings.from(original.json()))
        context.getSharedPreferences("mortimer_prefs",0).edit().remove("audio_lab_presets").putString("eq_custom_presets","{\"Old preset\":[1,2,3,4,5,6,7,8,9,10]}").commit()
        val migrated=AudioEffects.loadUserPresets(context);assertEquals(3f,migrated.getValue("Old preset").bands[2],0f)
        AudioEffects.saveUserPresets(context,mapOf("Complete" to original));assertEquals(original,AudioEffects.loadUserPresets(context).getValue("Complete"))
    }
    @Test fun hostileSettingsCannotIntroduceNonFiniteOrUnboundedDspParameters(){
        val s=AudioEffects.Settings(bands=listOf(Float.NaN,1000f),preamp=Float.POSITIVE_INFINITY,bpm=0f,delayFeedback=20f,crusherBits=-5,shiftSemitones=100f).sanitized()
        assertTrue(s.bands.all{it.isFinite()&&it in -12f..12f});assertEquals(0f,s.preamp,0f);assertEquals(.85f,s.delayFeedback,0f);assertEquals(4,s.crusherBits);assertEquals(12f,s.shiftSemitones,0f)
        assertNotNull(AudioEffects.Settings.from(JSONObject("{}")))
        val synth=SynthSettings(attack=Float.NaN,cutoff=Float.POSITIVE_INFINITY,bpm=Float.NaN,volume=Float.NEGATIVE_INFINITY).clean()
        assertEquals(.015f,synth.attack,0f);assertEquals(6500f,synth.cutoff,0f);assertEquals(120f,synth.bpm,0f);assertEquals(.35f,synth.volume,0f)
        val audio=FloatArray(4800);SynthEngine().render(audio,audio.size,synth.copy(sequence=true));assertTrue(audio.all{it.isFinite()})
    }
    private fun source():File=File.createTempFile("wave-source-",".wav",context.cacheDir).apply{RandomAccessFile(this,"rw").use{out->out.write(ByteArray(44));val pcm=ByteBuffer.allocate(4800*2).order(ByteOrder.LITTLE_ENDIAN);repeat(4800){pcm.putShort((kotlin.math.sin(2*Math.PI*440*it/48000)*5000).toInt().toShort())};out.write(pcm.array());waveHeader(out,48000,1,4800)}}
    @Test fun wavRenderingPreservesFramesAndOriginalSourceAndCanAddAnEffectsTail()=runBlocking<Unit>{
        val input=source();val before=input.readBytes();val target=File(context.cacheDir,"wave-output.wav")
        try{renderVoiceWave(input,target,AudioEffects.Settings(),false);assertArrayEquals(before,input.readBytes());assertArrayEquals(before,target.readBytes())
            renderVoiceWave(input,target,AudioEffects.SCENES.getValue("Wide room"),true);assertEquals(100800L,inspectWave(target).frames)
        }finally{input.delete();target.delete()}
    }
    @Test fun cancelledRenderDeletesOnlyItsPartialOutput()=runBlocking<Unit>{
        val input=source();val target=File(context.cacheDir,"cancelled.wav")
        try{assertThrows(CancellationException::class.java){runBlocking{renderVoiceWave(input,target,AudioEffects.SCENES.getValue("Hard tune"),false){throw CancellationException()}}};assertFalse(target.exists());assertTrue(input.exists())}
        finally{input.delete();target.delete()}
    }
    @Test fun synthProducesAnAudibleBoundedSequenceAndCorrectWavLength()=runBlocking<Unit>{
        val synth=SynthEngine();val a=FloatArray(48000);synth.render(a,a.size,SynthSettings(sequence=true));assertTrue(a.any{kotlin.math.abs(it)>.01});assertTrue(a.all{it.isFinite()&&kotlin.math.abs(it)<=.95})
        val target=File(context.cacheDir,"synth.wav");try{renderSynthWave(target,SynthSettings(bpm=120f),AudioEffects.Settings(),1);val info=inspectWave(target);assertEquals(96000L,info.frames);assertEquals(2,info.channels)}finally{target.delete()}
    }
    @Test fun malformedWavCannotReadBeyondItsChunks(){
        val input=source();try{RandomAccessFile(input,"rw").use{it.seek(40);it.writeInt(-1)};assertThrows(IllegalArgumentException::class.java){inspectWave(input)}}finally{input.delete()}
    }
    @Test fun scannerFindsAllKindsAndDeduplicatesWithoutChangingExistingProgress()=runBlocking<Unit>{
        val root=File(context.cacheDir,"scan-fixture").apply{mkdirs()};val known=File(root,"known.epub").apply{writeText("known")}
        File(root,"track.mp3").writeBytes(byteArrayOf(1,2));File(root,"video.mp4").writeBytes(byteArrayOf(1,2));File(root,"book.pdf").writeBytes(byteArrayOf(1,2));File(root,"ignore.exe").writeBytes(byteArrayOf(1,2));File(root,"comic.cbz").writeBytes(byteArrayOf(1,2))
        val item=LibraryItem(Uri.fromFile(known).toString(),"Known","books","epub",position="5:0.5",progress=.5f)
        try{val result=scanDeviceFiles(context,listOf(item),listOf(root),uriForFile={Uri.fromFile(it)});assertEquals(setOf("audio","videos","books","comics"),result.items.map{it.kind}.toSet());assertEquals(4,result.items.size);assertEquals(1,result.progress.duplicates);assertEquals("5:0.5",item.position)
            val partial=scanDeviceFiles(context,emptyList(),listOf(root),maxEntries=2,uriForFile={Uri.fromFile(it)});assertTrue(partial.partial)
        }finally{root.deleteRecursively()}
    }
    @Test fun scannerMatchesExistingSafDocumentsToTheirPhysicalFiles()=runBlocking<Unit>{
        val root=File(context.cacheDir,"scan-saf").apply{mkdirs()};val folder=File(root,"Books").apply{mkdirs()};File(folder,"Saved.epub").writeText("synthetic")
        val existing=LibraryItem("content://com.android.externalstorage.documents/tree/primary%3ABooks/document/primary%3ABooks%2FSaved.epub","Saved","books","epub",position="4:0.25",progress=.4f)
        try{val result=scanDeviceFiles(context,listOf(existing),listOf(root),uriForFile={Uri.fromFile(it)});assertTrue(result.items.isEmpty());assertEquals(1,result.progress.duplicates);assertEquals("4:0.25",existing.position)}
        finally{root.deleteRecursively()}
    }
    @Test fun cancelledDeviceScanNeverCommitsAPartialLibrary()=runBlocking<Unit>{
        val root=File(context.cacheDir,"scan-cancel").apply{mkdirs()};File(root,"Book.epub").writeText("synthetic");val before=LibraryStore.load(context)
        try{assertThrows(CancellationException::class.java){runBlocking{scanDeviceFiles(context,emptyList(),listOf(root),uriForFile={Uri.fromFile(it)},onProgress={throw CancellationException()})}};assertEquals(before,LibraryStore.load(context))}
        finally{root.deleteRecursively()}
    }
    @Test fun synthReleasesKeysInsteadOfLeavingAStuckVoice(){
        val e=SynthEngine();val s=SynthSettings(release=.1f);e.on(60);val held=FloatArray(24000);e.render(held,held.size,s);assertTrue(held.any{kotlin.math.abs(it)>.01})
        e.off(60);val tail=FloatArray(24000);e.render(tail,tail.size,s);assertTrue(tail.takeLast(4800).all{kotlin.math.abs(it)<.00001})
    }
    @Test fun scannerDoesNotMistakeAnOrdinaryZipForAComic(){
        val file=File(context.cacheDir,"archive.zip")
        try{ZipOutputStream(file.outputStream()).use{z->z.putNextEntry(ZipEntry("application.exe"));z.write(byteArrayOf(1));z.closeEntry()};assertNull(scanKind(file))}
        finally{file.delete()}
    }
}
