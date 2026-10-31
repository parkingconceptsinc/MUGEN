package com.parkingconcepts.mortimer

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AudioLabProfessionalTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun wave(): File = File.createTempFile("lab-professional-", ".wav", context.cacheDir).apply {
        RandomAccessFile(this, "rw").use { out -> out.write(ByteArray(44 + 1600)); waveHeader(out, 8000, 1, 800) }
    }
    @Test fun savingReservesOneOutputAndFailedOrCancelledSaveCanRetrySameRender() {
        val first = wave(); val second = wave()
        try {
            val state = WaveExportState()
            assertTrue(state.reserve(first)); assertTrue(state.busy)
            assertFalse(state.reserve(second)); assertEquals(first, state.pending)
            state.retain("Destination unavailable")
            assertFalse(state.busy); assertTrue(first.isFile); assertEquals(first, state.pending)
            assertTrue(state.reserve(first)) // Same bytes, no re-render required.
            assertFalse(state.complete(second)) // A stale callback cannot release this reservation.
            assertTrue(state.busy); assertEquals(first, state.pending)
            assertTrue(state.complete(first)); assertFalse(state.busy); assertNull(state.pending)
            assertTrue(first.isFile) // The ownership layer, not the state machine, handles temporary cleanup.
        } finally { first.delete(); second.delete() }
    }
    @Test fun providerFailureAndCancellationRetainTheRenderedSource() = runBlocking<Unit> {
        val source = wave(); val before = source.readBytes()
        try {
            val failed = object : OutputStream() { override fun write(value: Int) { throw IOException("Storage unavailable") } }
            assertThrows(IOException::class.java) { runBlocking { copyWaveExport(source, failed) } }
            assertArrayEquals(before, source.readBytes())
            val good = ByteArrayOutputStream(); copyWaveExport(source, good)
            assertArrayEquals(before, good.toByteArray())
            lateinit var job: Job
            var wrote = false
            job = launch {
                copyWaveExport(source, object : ByteArrayOutputStream() {
                    override fun write(bytes: ByteArray, offset: Int, count: Int) {
                        super.write(bytes, offset, count); wrote = true; job.cancel()
                    }
                })
            }
            job.join(); assertTrue(wrote); assertTrue(job.isCancelled)
            assertArrayEquals(before, source.readBytes())
        } finally { source.delete() }
    }
    @Test fun inPlaceVoiceRenderAndOverflowingHeaderCannotDamageOriginal() = runBlocking<Unit> {
        val source = wave(); val before = source.readBytes()
        try {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { renderVoiceWave(source, source, AudioEffects.Settings(), false) } }
            assertArrayEquals(before, source.readBytes())
            RandomAccessFile(source, "rw").use { out -> assertThrows(IllegalArgumentException::class.java) { waveHeader(out, 48000, 2, Long.MAX_VALUE) } }
            assertArrayEquals(before, source.readBytes())
        } finally { source.delete() }
    }
    @Test fun corruptPresetDoesNotHideHealthyPresetsAndFutureSettingsAreRejected() {
        val values = JSONObject().put("Healthy", AudioEffects.Settings(enabled = true, preamp = -3f).json()).put("Broken", "bad entry")
        context.getSharedPreferences("mortimer_prefs", 0).edit().putString("audio_lab_presets", values.toString()).commit()
        val loaded = AudioEffects.loadUserPresets(context)
        assertEquals(setOf("Healthy"), loaded.keys); assertEquals(-3f, loaded.getValue("Healthy").preamp, 0f)
        assertThrows(IllegalArgumentException::class.java) { AudioEffects.Settings.from(JSONObject().put("version", 999)) }
    }
    @Test fun bundleImportRejectsMissingAndMistypedSharedSettingsInsteadOfDefaulting() {
        fun bundle(settings:JSONObject) = JSONObject().put("format","mugen-audio-lab").put("version",1)
            .put("presets",JSONObject().put("Imported",settings)).toString()
        val valid=AudioEffects.Settings(enabled=true).json()
        assertTrue(AudioEffects.importPresetBundle(bundle(valid)).getValue("Imported").enabled)
        val invalid=listOf(
            JSONObject(),
            AudioEffects.Settings().json().apply { remove("version") },
            AudioEffects.Settings().json().apply { remove("enabled") },
            AudioEffects.Settings().json().put("enabled","true"),
            AudioEffects.Settings().json().put("bands",org.json.JSONArray(List(9){0})),
            AudioEffects.Settings().json().put("bands",org.json.JSONArray(List(10){if(it==3) "0" else 0})),
            AudioEffects.Settings().json().put("bands",org.json.JSONArray(List(10){if(it==3) "Infinity" else 0})),
        )
        invalid.forEach { settings ->
            assertThrows(IllegalArgumentException::class.java) { AudioEffects.importPresetBundle(bundle(settings)) }
        }
        // Legacy local preferences still migrate without the file-only contract.
        assertEquals(AudioEffects.Settings(),AudioEffects.Settings.from(JSONObject()))
    }
    @Test fun presetBackupRoundTripsAndCollisionsMergeWithoutReplacingExistingSound() {
        val original = mapOf("Vocal" to AudioEffects.SCENES.getValue("Warm vocal"))
        val imported = AudioEffects.importPresetBundle(AudioEffects.exportPresetBundle(original))
        assertEquals(original, imported)
        val merged = AudioEffects.mergePresetBundle(original, imported)
        assertEquals(setOf("Vocal", "Vocal (2)"), merged.keys)
        assertEquals(original.getValue("Vocal"), merged.getValue("Vocal"))
        assertEquals("Vocal (2)", merged.getValue("Vocal (2)").preset)
        assertThrows(IllegalArgumentException::class.java) { AudioEffects.importPresetBundle("{}") }
        assertThrows(IllegalArgumentException::class.java) { AudioEffects.importPresetBundle("x".repeat(256*1024+1)) }
        val full = (1..32).associate { "Preset $it" to AudioEffects.Settings() }
        assertThrows(IllegalArgumentException::class.java) { AudioEffects.mergePresetBundle(full, imported) }
        assertEquals(32, full.size)
    }
}
