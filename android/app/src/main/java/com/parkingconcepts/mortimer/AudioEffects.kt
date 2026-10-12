package com.parkingconcepts.mortimer

import android.content.Context
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10

/**
 * The PWA's audio effects on Android: a 10-band equalizer with presets, preamp,
 * left/right balance and volume boost. The service attaches them to the player's
 * audio session; the screens change them through [apply]. Both run in one process.
 */
object AudioEffects {
    val BANDS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    const val RANGE = 12f

    val PRESETS: LinkedHashMap<String, List<Float>> = linkedMapOf(
        "Plano" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Más graves" to listOf(7f, 6f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f),
        "Menos graves" to listOf(-6f, -5f, -4f, -2f, -1f, 0f, 0f, 0f, 0f, 0f),
        "Más agudos" to listOf(0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f, 7f),
        "Rock" to listOf(5f, 4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 5f),
        "Pop" to listOf(-1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f, -1f, -1f),
        "Jazz" to listOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f),
        "Clásica" to listOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f),
        "Hip-Hop" to listOf(6f, 5f, 2f, 3f, -1f, -1f, 1f, 0f, 2f, 3f),
        "Electrónica" to listOf(5f, 4f, 1f, 0f, -2f, 2f, 1f, 1f, 4f, 5f),
        "Dance" to listOf(4f, 6f, 4f, 0f, 0f, -2f, -2f, -2f, 0f, 0f),
        "Latina" to listOf(3f, 2f, 0f, 0f, -1f, -1f, -1f, 0f, 3f, 4f),
        "Reguetón" to listOf(6f, 5f, 3f, 0f, -1f, 0f, 1f, 2f, 3f, 3f),
        "Voces" to listOf(-2f, -3f, -2f, 1f, 4f, 4f, 3f, 1f, 0f, -2f),
        "Acústica" to listOf(4f, 4f, 3f, 1f, 2f, 1f, 3f, 3f, 3f, 2f),
        "Sonoridad" to listOf(6f, 4f, 0f, 0f, -2f, 0f, -1f, -5f, 5f, 1f),
    )

    data class Settings(
        val enabled: Boolean = false,
        val preset: String = "Plano",
        val bands: List<Float> = List(BANDS.size) { 0f },
        val preamp: Float = 0f,
        /** -1 (left) … 1 (right). */
        val balance: Float = 0f,
        /** 1 … 2 */
        val boost: Float = 1f,
    )

    private const val PREFS = "mortimer_prefs"

    fun load(context: Context): Settings {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("audio_effects", null) ?: return Settings()
        return runCatching {
            val o = JSONObject(raw)
            val arr = o.optJSONArray("bands") ?: JSONArray()
            Settings(
                enabled = o.optBoolean("enabled", false),
                preset = o.optString("preset", "Plano"),
                bands = List(BANDS.size) { i -> arr.optDouble(i, 0.0).toFloat().coerceIn(-RANGE, RANGE) },
                preamp = o.optDouble("preamp", 0.0).toFloat().coerceIn(-RANGE, RANGE),
                balance = o.optDouble("balance", 0.0).toFloat().coerceIn(-1f, 1f),
                boost = o.optDouble("boost", 1.0).toFloat().coerceIn(1f, 2f),
            )
        }.getOrDefault(Settings())
    }

    fun save(context: Context, s: Settings) {
        val o = JSONObject()
            .put("enabled", s.enabled).put("preset", s.preset)
            .put("bands", JSONArray(s.bands.map { it.toDouble() }))
            .put("preamp", s.preamp.toDouble()).put("balance", s.balance.toDouble()).put("boost", s.boost.toDouble())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("audio_effects", o.toString()).apply()
    }

    fun loadCustomPresets(context: Context): Map<String, List<Float>> = runCatching {
        val o = JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("eq_custom_presets", "{}") ?: "{}")
        o.keys().asSequence().associateWith { k ->
            val arr = o.getJSONArray(k)
            List(BANDS.size) { i -> arr.optDouble(i, 0.0).toFloat() }
        }
    }.getOrDefault(emptyMap())

    fun saveCustomPresets(context: Context, presets: Map<String, List<Float>>) {
        val o = JSONObject()
        presets.forEach { (k, v) -> o.put(k, JSONArray(v.map { it.toDouble() })) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("eq_custom_presets", o.toString()).apply()
    }

    @OptIn(UnstableApi::class)
    val balanceProcessor = BalanceAudioProcessor()

    private var equalizer: Equalizer? = null
    private var loudness: LoudnessEnhancer? = null
    private var current = Settings()

    /** True once the player's audio session has an equalizer this device supports. */
    val available: Boolean get() = equalizer != null

    @Synchronized
    fun attach(context: Context, sessionId: Int) {
        release()
        equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull()
        loudness = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
        apply(load(context))
    }

    @Synchronized
    fun release() {
        runCatching { equalizer?.release() }
        runCatching { loudness?.release() }
        equalizer = null
        loudness = null
    }

    /** Gain of the 10-band curve at [hz], interpolated on a log-frequency scale. */
    private fun curveAt(bands: List<Float>, hz: Float): Float {
        if (hz <= BANDS.first()) return bands.first()
        if (hz >= BANDS.last()) return bands.last()
        val i = BANDS.indexOfLast { it <= hz }
        val f0 = ln(BANDS[i].toFloat())
        val f1 = ln(BANDS[i + 1].toFloat())
        val t = (ln(hz) - f0) / (f1 - f0)
        return bands[i] + (bands[i + 1] - bands[i]) * t
    }

    @OptIn(UnstableApi::class)
    @Synchronized
    fun apply(s: Settings) {
        current = s
        balanceProcessor.balance = s.balance
        equalizer?.let { eq ->
            runCatching {
                eq.enabled = s.enabled
                if (s.enabled) {
                    val range = eq.bandLevelRange
                    val min = range[0].toInt()
                    val max = range[1].toInt()
                    val cut = minOf(0f, s.preamp)
                    for (b in 0 until eq.numberOfBands) {
                        val hz = eq.getCenterFreq(b.toShort()) / 1000f
                        val db = curveAt(s.bands, hz) + cut
                        eq.setBandLevel(b.toShort(), (db * 100).toInt().coerceIn(min, max).toShort())
                    }
                }
            }
        }
        loudness?.let { le ->
            runCatching {
                val gainDb = (if (s.enabled) maxOf(0f, s.preamp) else 0f) + 20f * log10(s.boost)
                le.setTargetGain((gainDb * 100).toInt())
                le.enabled = gainDb > 0.01f
            }
        }
    }

    fun currentSettings(): Settings = current
}

/** Scales the left or right channel of 16-bit stereo audio; other formats pass through untouched. */
@UnstableApi
class BalanceAudioProcessor : BaseAudioProcessor() {
    @Volatile
    var balance: Float = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        val b = balance
        if (abs(b) < 0.001f) {
            out.put(inputBuffer)
        } else {
            val left = if (b > 0f) 1f - b else 1f
            val right = if (b < 0f) 1f + b else 1f
            while (inputBuffer.remaining() >= 4) {
                out.putShort((inputBuffer.getShort() * left).toInt().toShort())
                out.putShort((inputBuffer.getShort() * right).toInt().toShort())
            }
            while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        }
        out.flip()
    }
}
