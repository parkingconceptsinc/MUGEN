package com.parkingconcepts.mortimer

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SynthPatchTest {
    @Test fun readsTheSameSharedPatchAsThePwa() {
        val text = javaClass.classLoader!!.getResourceAsStream("synth-patch.json")!!.bufferedReader().use { it.readText() }
        assertEquals(SynthSettings(), SynthPatch.parse(text))
    }
    @Test fun portablePatchRoundTripsAndNeverStartsASequence() {
        val source = SynthSettings(wave = 2, octave = 5, sequence = true, bpm = 95f, steps = List(16) { if (it % 2 == 0) 69 else -1 })
        val copy = SynthPatch.parse(SynthPatch.export(source))
        assertEquals(source.copy(sequence = false), copy)
        val manual = JSONObject(SynthPatch.export(source)).apply { getJSONObject("settings").put("sequence", true) }
        assertFalse(SynthPatch.parse(manual.toString()).sequence)
    }
    @Test fun boundaryFloatsSurvivePortableJsonWithoutRoundingOutsideContract() {
        val s = SynthSettings(attack = .002f, decay = .01f, sustain = 0f, release = .01f, resonance = .5f, volume = .8f, vibratoHz = .1f)
        assertEquals(s, SynthPatch.parse(SynthPatch.export(s)))
    }
    @Test fun malformedOrFuturePatchesAreRejectedBeforeApplyingSettings() {
        val valid = SynthPatch.export(SynthSettings())
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("version", 2) }, { it.remove("settings") },
            { it.getJSONObject("settings").put("wave", 1.5) },
            { it.getJSONObject("settings").put("cutoff", "800") },
            { it.getJSONObject("settings").put("bpm", 0) },
            { it.getJSONObject("settings").getJSONArray("steps").put(0, 999) },
            { it.getJSONObject("settings").put("sequence", "true") }
        )) {
            val doc = JSONObject(valid); change(doc)
            assertThrows(IllegalArgumentException::class.java) { SynthPatch.parse(doc.toString()) }
        }
        assertThrows(IllegalArgumentException::class.java) { SynthPatch.parse("x".repeat(SynthPatch.MAX_BYTES + 1)) }
    }
}
