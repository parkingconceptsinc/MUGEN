package com.parkingconcepts.mortimer

import org.json.JSONArray
import org.json.JSONObject

/** Portable dry synth parameters. Import never starts playback or a sequence. */
internal object SynthPatch {
    const val MAX_BYTES = 16 * 1024
    private val ranges = linkedMapOf(
        "attack" to .002..1.0, "decay" to .01..2.0, "sustain" to 0.0..1.0, "release" to .01..2.0,
        "cutoff" to 80.0..20000.0, "resonance" to .5..4.0, "detuneCents" to 0.0..25.0,
        "blend" to 0.0..1.0, "vibratoHz" to .1..10.0, "vibratoDepth" to 0.0..1.0,
        "volume" to 0.0..0.8, "bpm" to 40.0..240.0)
    fun export(settings: SynthSettings): String {
        val s = settings.clean()
        val value = JSONObject().put("wave", s.wave).put("octave", s.octave).put("sequence", false).put("steps", JSONArray(s.steps))
        mapOf("attack" to s.attack, "decay" to s.decay, "sustain" to s.sustain, "release" to s.release,
            "cutoff" to s.cutoff, "resonance" to s.resonance, "detuneCents" to s.detuneCents, "blend" to s.blend,
            "vibratoHz" to s.vibratoHz, "vibratoDepth" to s.vibratoDepth, "volume" to s.volume, "bpm" to s.bpm)
            .forEach { (key, number) -> value.put(key, kotlin.math.round(number.toDouble() * 1_000_000.0) / 1_000_000.0) }
        return JSONObject().put("format", "mugen-synth").put("version", 1).put("settings", value).toString(2)
    }
    fun parse(text: String): SynthSettings = try {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Synth patch exceeds 16 KiB." }
        val doc = JSONObject(text)
        require(doc.optString("format") == "mugen-synth" && doc.opt("version") == 1) { "Choose a MUGEN synth patch (version 1)." }
        val s = doc.getJSONObject("settings")
        fun number(key: String): Double {
            val raw = s.opt(key)
            require(raw is Number) { "Synth parameter $key must be a number." }
            val n = raw.toDouble()
            require(n.isFinite() && n in ranges.getValue(key)) { "Synth parameter $key is out of range." }
            return n
        }
        fun integer(key: String, min: Int, max: Int): Int {
            val raw = s.opt(key)
            require(raw is Number && raw.toDouble().isFinite() && raw.toDouble() == raw.toInt().toDouble() && raw.toInt() in min..max) { "Invalid synth $key." }
            return raw.toInt()
        }
        require(s.opt("sequence") is Boolean) { "Synth sequence flag is missing." }
        val values = s.getJSONArray("steps")
        require(values.length() == 16) { "A synth patch needs exactly 16 steps." }
        val steps = List(16) { i ->
            val raw = values.opt(i)
            require(raw is Number && raw.toDouble().isFinite() && raw.toDouble() == raw.toInt().toDouble() && (raw.toInt() == -1 || raw.toInt() in 24..108)) { "Invalid sequencer step ${i + 1}." }
            raw.toInt()
        }
        SynthSettings(wave = integer("wave", 0, 3), octave = integer("octave", 2, 6),
            attack = number("attack").toFloat(), decay = number("decay").toFloat(), sustain = number("sustain").toFloat(), release = number("release").toFloat(),
            cutoff = number("cutoff").toFloat(), resonance = number("resonance").toFloat(), detuneCents = number("detuneCents").toFloat(), blend = number("blend").toFloat(),
            vibratoHz = number("vibratoHz").toFloat(), vibratoDepth = number("vibratoDepth").toFloat(), volume = number("volume").toFloat(), bpm = number("bpm").toFloat(), steps = steps, sequence = false)
    } catch (failure: org.json.JSONException) {
        throw IllegalArgumentException("Malformed synth patch. Keep the original file and choose a supported patch.", failure)
    }
}
