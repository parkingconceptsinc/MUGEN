package com.parkingconcepts.mortimer

import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StudioBundleTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun archive() = File.createTempFile("portable-test-", ".zip", context.cacheDir).apply {
        StudioBundleTest::class.java.classLoader!!.getResourceAsStream("studio-reference.mugenstudio")!!.use { input -> outputStream().use { out -> input.copyTo(out) } }
    }
    private fun manifest(): StudioBundleManifest {
        val text = javaClass.classLoader!!.getResourceAsStream("studio-portable/manifest.json")!!.bufferedReader().use { it.readText() }
        return StudioBundleCodec.parse(text)
    }
    private fun probe(file: File, m: StudioBundleMedia) = StudioSource(Uri.fromFile(file).toString(), m.name, (m.duration * 1000).toLong(), m.width, m.height)
    @Test fun neutralFixtureAndNativeAdaptersPreserveTimingAndPictureSettings() {
        val m = manifest(); val c = m.clips.single(); val edit = StudioBundleCodec.nativeEdit(c, 4000)
        assertEquals(500L, edit.startMs); assertEquals(3500L, edit.endMs); assertEquals(1.25f, edit.speed, 0f)
        val source = StudioSource("file:///synthetic.mp4", "Reference", 4000, 320, 180)
        val copy = StudioBundleCodec.portableClip(StudioClip(c.id, source, edit), c.mediaId)
        assertEquals(c.rotation, copy.rotation); assertEquals(c.flip, copy.flip)
        assertEquals(c.contrast, copy.contrast, .000001); assertEquals(c.saturation, copy.saturation, .000001)
        assertEquals(c.brightness, copy.brightness, .000001); assertEquals(c.volume, copy.volume, .000001)
        for (rotation in listOf(0, 90, 180, 270)) for (flip in listOf(false, true)) {
            val item = c.copy(rotation = rotation, flip = flip)
            val restored = StudioBundleCodec.portableClip(StudioClip(c.id, source, StudioBundleCodec.nativeEdit(item, 4000)), c.mediaId)
            assertEquals(rotation, restored.rotation); assertEquals(flip, restored.flip)
        }
        assertThrows(IllegalArgumentException::class.java) { StudioBundleCodec.nativeEdit(c.copy(volume = 1.5), 4000) }
        assertThrows(IllegalArgumentException::class.java) { StudioBundleCodec.portableClip(StudioClip(c.id, source, edit.copy(contrast = .8f)), c.mediaId) }
    }
    @Test fun sharedZipImportsAsDistinctProjectsAndReexportsOriginalMediaBytes() {
        val input = archive(); val output = File.createTempFile("portable-output-", ".zip", context.cacheDir)
        val ids = mutableListOf<String>()
        try {
            val first = StudioBundleTransfer.importFile(context, input, probe = ::probe).also { ids.add(it.project.id) }
            val second = StudioBundleTransfer.importFile(context, input, probe = ::probe).also { ids.add(it.project.id) }
            assertNotEquals(first.project.id, second.project.id)
            assertNotEquals(manifest().id, first.project.id)
            val source = File(Uri.parse(first.project.clips.single().source.uri).path!!)
            val expected = javaClass.classLoader!!.getResourceAsStream("studio-portable/media/source-000.bin")!!.use { it.readBytes() }
            assertArrayEquals(expected, source.readBytes())
            StudioBundleTransfer.exportFile(context, first.project, output)
            val entry = StudioStoredZip.index(output).single { it.name == "media/source-000.bin" }
            val bytes = java.io.ByteArrayOutputStream(); StudioStoredZip.copy(output, entry, bytes)
            assertArrayEquals(expected, bytes.toByteArray())
            assertEquals(first.project.id, StudioProjectStore.load(context, first.project.id).project.id)
        } finally { ids.forEach { StudioProjectStore.delete(context, it) }; input.delete(); output.delete() }
    }
    @Test fun crcFailureAndCancellationLeaveNoRegisteredProjectsOrStagedMedia() {
        val input = archive(); val before = StudioProjectStore.list(context).toSet()
        val root = File(context.filesDir, "video_studio_media"); val oldMedia = root.list().orEmpty().toSet()
        try {
            assertThrows(CancellationException::class.java) { StudioBundleTransfer.importFile(context, input, { throw CancellationException("cancel test") }, ::probe) }
            val record = StudioStoredZip.index(input).single { it.name.startsWith("media/") }
            RandomAccessFile(input, "rw").use { f -> f.seek(record.offset); val byte = f.readUnsignedByte(); f.seek(record.offset); f.write(byte xor 1) }
            assertThrows(IllegalArgumentException::class.java) { StudioBundleTransfer.importFile(context, input, probe = ::probe) }
            assertEquals(before, StudioProjectStore.list(context).toSet()); assertEquals(oldMedia, root.list().orEmpty().toSet())
        } finally { input.delete() }
    }
    @Test fun schemaRejectsUnknownFieldsUnsafePathsAndFutureVersions() {
        val good = manifest().json()
        for (mutate in listOf<(JSONObject) -> Unit>({ it.put("version", 9) }, { it.put("surprise", true) },
            { it.getJSONArray("media").getJSONObject(0).put("path", "../source.mp4") },
            { it.getJSONObject("project").getJSONArray("clips").getJSONObject(0).put("crop", true) })) {
            val bad = JSONObject(good.toString()); mutate(bad)
            assertThrows(IllegalArgumentException::class.java) { StudioBundleCodec.parse(bad.toString()) }
        }
    }
}
