package com.parkingconcepts.mortimer

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.SpeedChangeEffect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StudioProjectTest {
    private val source = StudioSource("content://studio/source", "Source", 20_000, 1920, 1080, 25.0, false)
    private fun project() = StudioProject(name = "Test", clips = listOf(
        StudioClip(source = source, edit = VideoEditSpec(2000, 6000, speed = 2f)),
        StudioClip(source = source, edit = VideoEditSpec(8000, 12_000, speed = 0.5f))))
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Test fun projectClockIsIndependentFromSourceTrimAndSpeed() {
        val p = project()
        assertEquals(10_000L, p.duration)
        assertEquals(4000L, p.locate(1000).sourceMs)
        assertEquals(1, p.locate(2000).index)
        assertEquals(8000L, p.locate(2000).sourceMs)
        assertEquals(9000L, p.locate(4000).sourceMs)
        assertEquals(12_000L, p.locate(Long.MAX_VALUE).sourceMs)
        assertEquals(4000L, p.projectTime(p.clips[1].id, 9000))
        assertEquals(0L, p.locate(-1).offsetMs)
    }
    @Test fun mappingKeepsTheSameSourceFrameWhenTrimmingOrChangingSpeed() {
        val p = project(); val c = p.clips.first(); val head = p.locate(1000).sourceMs
        val trimmed = p.replace(c.id, c.edit.copy(startMs = 3000, speed = 0.5f))
        assertEquals(2000L, trimmed.projectTime(c.id, head))
        assertEquals(head, trimmed.locate(2000).sourceMs)
        assertEquals(0L, trimmed.projectTime(c.id, 1000))
    }
    @Test fun longSourcesKeepMillisecondPrecisionInsteadOfRoundingThroughFloat() {
        val clip = StudioClip(source = source.copy(duration = 600_000_000), edit = VideoEditSpec(500_000_123, 500_004_123, speed = 1.5f))
        val p = StudioProject(name = "Long source", clips = listOf(clip)).validate()
        assertEquals(500_001_623L, p.locate(1000).sourceMs)
        assertEquals(1000L, p.projectTime(clip.id, 500_001_623))
    }
    @Test fun splitKeepsMediaContiguousAndUndoRestoresTheWholeOperation() {
        val p = project(); val split = p.split(1000)
        assertEquals(3, split.clips.size)
        assertEquals(4000L, split.clips[0].edit.endMs)
        assertEquals(4000L, split.clips[1].edit.startMs)
        assertEquals(p.duration, split.duration)
        assertNotEquals(split.clips[0].id, split.clips[1].id)
        val history = StudioHistory(p); history.commit(split)
        assertEquals(p, history.undo()); assertEquals(split, history.redo())
        assertEquals(split, history.commit(split))
        history.undo(); history.commit(p.copy(name = "Changed")); assertFalse(history.canRedo)
    }
    @Test fun splitRejectsTinyPiecesAndMoveUsesStableClipIds() {
        val p = project()
        assertThrows(IllegalArgumentException::class.java) { p.split(1) }
        val moved = p.move(p.clips[0].id, 1)
        assertEquals(p.clips.reversed(), moved.clips)
        assertEquals(p.duration, moved.duration)
        assertEquals(moved, moved.move(moved.clips.first().id, -1))
    }
    @Test fun timecodeInputHasExplicitUnitsAndRejectsAmbiguousInvalidNumbers() {
        assertEquals("01:02:03.004", studioTime(3_723_004))
        assertEquals(3_723_004L, parseStudioTime("01:02:03.004"))
        assertEquals(62_500L, parseStudioTime("01:02,5"))
        assertEquals(1250L, parseStudioTime("1.25"))
        listOf("NaN", "Infinity", "-1", "1:90", "00:60:00", "bad", "").forEach { assertNull(parseStudioTime(it)) }
        assertEquals(2000L, source.snap(2011))
    }
    @Test fun serializationKeepsAllControlsAndIndependentClipInstances() {
        val p = project().let { p -> p.replace(p.clips.first().id, p.clips.first().edit.copy(rotation = 270, flip = true, brightness = .2f, contrast = -.4f, saturation = 22f, mute = true, volume = .35f)) }
        assertEquals(p, StudioProject.from(p.json()))
        assertThrows(IllegalArgumentException::class.java) { p.copy(clips = listOf(p.clips[0], p.clips[0])).validate() }
        assertThrows(IllegalArgumentException::class.java) { p.copy(clips = emptyList()).validate() }
        assertThrows(IllegalArgumentException::class.java) { p.clips[0].edit.copy(speed = Float.NaN).validate(source.duration) }
        assertThrows(IllegalArgumentException::class.java) { p.clips[0].edit.copy(volume = 2f).validate(source.duration) }
    }
    @Test fun previewAndExportUseSameVisualEffectsWithoutDoublingSpeed() {
        val p = project(); val clip = p.clips.first()
        val output = studioVideoEffects(p, clip)
        val preview = studioVideoEffects(p, clip, preview = true)
        assertEquals(1, output.count { it is SpeedChangeEffect })
        assertFalse(preview.any { it is SpeedChangeEffect })
        assertEquals(output.filterNot { it is SpeedChangeEffect }.map { it.javaClass }, preview.map { it.javaClass })
        assertEquals(1, editAudioProcessors(clip.edit).size)
        assertEquals(2, editAudioProcessors(clip.edit.copy(volume = .5f)).size)
        assertTrue(editedVideoItem(android.net.Uri.parse(source.uri), clip.edit.copy(volume = 0f), source.duration).removeAudio)
        assertEquals(2, studioComposition(p).sequences.single().editedMediaItems.size)
    }
    @Test fun projectRecoveryProtectsSavedWorkAndPlayhead() {
        val p = project(); StudioProjectStore.save(context, p, 3456)
        assertEquals(StudioSavedProject(p, 3456), StudioProjectStore.load(context, p.id))
        val file = File(context.filesDir, "video_studio/${p.id}.json")
        file.writeText("damaged"); assertEquals(p, StudioProjectStore.load(context, p.id).project)
        File(file.path + ".recovery").writeText("damaged too")
        assertThrows(IOException::class.java) { StudioProjectStore.save(context, p.copy(name = "Must not overwrite"), 0) }
        assertEquals("damaged", file.readText())
        assertThrows(IllegalArgumentException::class.java) { StudioProjectStore.load(context, "../library") }
        StudioProjectStore.delete(context, p.id)
    }
    @Test fun serialCheckpointsKeepFinalEditAndCursor() {
        val p = project(); val end = p.copy(name = "Final")
        StudioSaves.save(context, p, 100); StudioSaves.save(context, end, 4000); StudioSaves.await()
        assertEquals(StudioSavedProject(end, 4000), StudioProjectStore.load(context, p.id))
        StudioProjectStore.delete(context, p.id)
    }
    @Test fun montageSubtitlesApplyCalibrationTrimSpeedAndClipOrder() = runBlocking {
        val p = project()
        val srt = File(context.cacheDir, "studio-captions.srt").apply { writeText("1\n00:00:01,000 --> 00:00:03,000\nFirst\n\n2\n00:00:08,000 --> 00:00:09,000\nSecond\n") }
        val uri = android.net.Uri.parse("content://studio/captions-test")
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStream(uri, srt.readBytes().inputStream())
        val key = mediaKey(source.uri)
        val prefs = context.getSharedPreferences("video_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("${key}_subtitle", uri.toString()).putLong("${key}_offset", 1000).putString("${key}_scale", "1").commit()
        try {
            val (timeline, protected) = studioSubtitleTimeline(context, p)
            assertEquals(listOf(SubtitleCue(0, 1000, "First"), SubtitleCue(4000, 6000, "Second")), timeline.cues)
            assertTrue(protected.contains(uri))
        } finally { srt.delete(); prefs.edit().remove("${key}_subtitle").remove("${key}_offset").remove("${key}_scale").commit() }
    }
}
