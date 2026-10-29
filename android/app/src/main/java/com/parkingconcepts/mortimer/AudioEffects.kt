package com.parkingconcepts.mortimer

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import org.json.JSONArray
import org.json.JSONObject

/** Immutable, validated snapshots cross from the UI into the audio thread. No device audiofx dependency. */
object AudioEffects {
    val BANDS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    const val RANGE = 12f
    val PRESETS = linkedMapOf(
        "Flat" to List(10) { 0f },
        "Bass boost" to listOf(5f,5f,4f,2f,0f,-1f,0f,1f,1f,0f),
        "Rock" to listOf(4f,3f,2f,0f,-2f,-1f,1f,3f,4f,3f),
        "Pop" to listOf(-1f,1f,3f,4f,3f,1f,0f,-1f,-1f,-1f),
        "Jazz" to listOf(3f,2f,1f,2f,-1f,-1f,0f,1f,2f,3f),
        "Classical" to listOf(3f,2f,1f,0f,-1f,-1f,0f,2f,3f,3f),
        "Electronic" to listOf(4f,4f,1f,0f,-2f,2f,1f,1f,4f,4f),
        "Vocal clarity" to listOf(-3f,-3f,-2f,0f,2f,3f,3f,1f,0f,-1f),
        "Warm acoustic" to listOf(2f,3f,2f,0f,1f,1f,2f,2f,1f,0f),
        "Night listening" to listOf(0f,0f,0f,1f,2f,2f,1f,0f,-1f,-2f)
    )
    val KEYS = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    val SCALES = listOf("Chromatic", "Major", "Minor", "Pentatonic")
    data class Settings(
        val enabled: Boolean = false, val preset: String = "Flat", val bands: List<Float> = List(10) { 0f },
        val preamp: Float = 0f, val balance: Float = 0f, val boost: Float = 1f,
        val autoHeadroom: Boolean = true, val width: Float = 1f,
        val highPass: Boolean = false, val highPassHz: Float = 80f,
        val lowPass: Boolean = false, val lowPassHz: Float = 18000f, val resonance: Float = .707f,
        val compressor: Boolean = false, val threshold: Float = -18f, val ratio: Float = 3f,
        val attackMs: Float = 10f, val releaseMs: Float = 150f, val makeup: Float = 0f,
        val gate: Boolean = false, val gateDb: Float = -50f,
        val limiter: Boolean = true, val ceiling: Float = -.5f,
        val delayMix: Float = 0f, val delayMs: Float = 300f, val delayFeedback: Float = .35f,
        val delaySync: Boolean = false, val delayBeats: Float = .5f, val bpm: Float = 120f,
        val reverbMix: Float = 0f, val room: Float = .5f, val damping: Float = .45f,
        val chorusMix: Float = 0f, val chorusRate: Float = .8f, val chorusDepthMs: Float = 4f,
        val driveDb: Float = 0f, val driveMix: Float = 0f,
        val crusherMix: Float = 0f, val crusherBits: Int = 10, val crusherHold: Int = 1,
        val tremoloMix: Float = 0f, val tremoloHz: Float = 4f,
        val robotMix: Float = 0f, val robotHz: Float = 80f,
        val tune: Boolean = false, val tuneKey: Int = 0, val tuneScale: Int = 0,
        val tuneAmount: Float = 1f, val retuneMs: Float = 30f, val shiftSemitones: Float = 0f,
    ) {
        fun sanitized(): Settings = copy(
            preset = preset.take(64), bands = List(10) { bands.getOrElse(it) { 0f }.safe(-12f,12f,0f) },
            preamp=preamp.safe(-24f,12f,0f), balance=balance.safe(-1f,1f,0f), boost=boost.safe(1f,2f,1f), width=width.safe(0f,2f,1f),
            highPassHz=highPassHz.safe(20f,1200f,80f), lowPassHz=lowPassHz.safe(1500f,22000f,18000f), resonance=resonance.safe(.5f,2f,.707f),
            threshold=threshold.safe(-60f,0f,-18f), ratio=ratio.safe(1f,20f,3f), attackMs=attackMs.safe(.5f,100f,10f), releaseMs=releaseMs.safe(20f,1000f,150f), makeup=makeup.safe(0f,12f,0f), gateDb=gateDb.safe(-90f,-10f,-50f), ceiling=ceiling.safe(-12f,-.1f,-.5f),
            delayMix=delayMix.safe(0f,.7f,0f), delayMs=delayMs.safe(10f,1500f,300f), delayFeedback=delayFeedback.safe(0f,.85f,.35f), delayBeats=delayBeats.safe(.125f,2f,.5f), bpm=bpm.safe(40f,240f,120f),
            reverbMix=reverbMix.safe(0f,.6f,0f), room=room.safe(0f,1f,.5f), damping=damping.safe(0f,.95f,.45f),
            chorusMix=chorusMix.safe(0f,.7f,0f), chorusRate=chorusRate.safe(.1f,3f,.8f), chorusDepthMs=chorusDepthMs.safe(.5f,8f,4f),
            driveDb=driveDb.safe(0f,24f,0f), driveMix=driveMix.safe(0f,1f,0f), crusherMix=crusherMix.safe(0f,1f,0f), crusherBits=crusherBits.coerceIn(4,16), crusherHold=crusherHold.coerceIn(1,16),
            tremoloMix=tremoloMix.safe(0f,1f,0f), tremoloHz=tremoloHz.safe(.2f,20f,4f), robotMix=robotMix.safe(0f,1f,0f), robotHz=robotHz.safe(20f,400f,80f),
            tuneKey=tuneKey.coerceIn(0,11), tuneScale=tuneScale.coerceIn(0,3), tuneAmount=tuneAmount.safe(0f,1f,1f), retuneMs=retuneMs.safe(5f,500f,30f), shiftSemitones=shiftSemitones.safe(-12f,12f,0f)
        )
        fun json(): JSONObject = JSONObject().apply {
            put("version",2); put("enabled",enabled); put("preset",preset); put("bands",JSONArray(bands.map { it.toDouble() }))
            mapOf("preamp" to preamp,"balance" to balance,"boost" to boost,"width" to width,"highPassHz" to highPassHz,"lowPassHz" to lowPassHz,"resonance" to resonance,
                "threshold" to threshold,"ratio" to ratio,"attackMs" to attackMs,"releaseMs" to releaseMs,"makeup" to makeup,"gateDb" to gateDb,"ceiling" to ceiling,
                "delayMix" to delayMix,"delayMs" to delayMs,"delayFeedback" to delayFeedback,"delayBeats" to delayBeats,"bpm" to bpm,"reverbMix" to reverbMix,"room" to room,"damping" to damping,
                "chorusMix" to chorusMix,"chorusRate" to chorusRate,"chorusDepthMs" to chorusDepthMs,"driveDb" to driveDb,"driveMix" to driveMix,"crusherMix" to crusherMix,
                "tremoloMix" to tremoloMix,"tremoloHz" to tremoloHz,"robotMix" to robotMix,"robotHz" to robotHz,"tuneAmount" to tuneAmount,"retuneMs" to retuneMs,"shiftSemitones" to shiftSemitones).forEach { (k,v)->put(k,v.toDouble()) }
            mapOf("autoHeadroom" to autoHeadroom,"highPass" to highPass,"lowPass" to lowPass,"compressor" to compressor,"gate" to gate,"limiter" to limiter,"delaySync" to delaySync,"tune" to tune).forEach { (k,v)->put(k,v) }
            put("crusherBits",crusherBits);put("crusherHold",crusherHold);put("tuneKey",tuneKey);put("tuneScale",tuneScale)
        }
        companion object {
            fun from(o: JSONObject): Settings {
                fun f(k:String,d:Float)=o.optDouble(k,d.toDouble()).toFloat()
                fun b(k:String,d:Boolean=false)=o.optBoolean(k,d)
                return Settings(enabled=b("enabled"),preset=o.optString("preset","Flat").let { when(it){"Plano"->"Flat";"Personalizado"->"Custom";else->it} },
                    bands=List(10){o.optJSONArray("bands")?.optDouble(it,0.0)?.toFloat()?:0f},preamp=f("preamp",0f),balance=f("balance",0f),boost=f("boost",1f),autoHeadroom=b("autoHeadroom",true),width=f("width",1f),
                    highPass=b("highPass"),highPassHz=f("highPassHz",80f),lowPass=b("lowPass"),lowPassHz=f("lowPassHz",18000f),resonance=f("resonance",.707f),
                    compressor=b("compressor"),threshold=f("threshold",-18f),ratio=f("ratio",3f),attackMs=f("attackMs",10f),releaseMs=f("releaseMs",150f),makeup=f("makeup",0f),gate=b("gate"),gateDb=f("gateDb",-50f),limiter=b("limiter",true),ceiling=f("ceiling",-.5f),
                    delayMix=f("delayMix",0f),delayMs=f("delayMs",300f),delayFeedback=f("delayFeedback",.35f),delaySync=b("delaySync"),delayBeats=f("delayBeats",.5f),bpm=f("bpm",120f),reverbMix=f("reverbMix",0f),room=f("room",.5f),damping=f("damping",.45f),
                    chorusMix=f("chorusMix",0f),chorusRate=f("chorusRate",.8f),chorusDepthMs=f("chorusDepthMs",4f),driveDb=f("driveDb",0f),driveMix=f("driveMix",0f),crusherMix=f("crusherMix",0f),crusherBits=o.optInt("crusherBits",10),crusherHold=o.optInt("crusherHold",1),
                    tremoloMix=f("tremoloMix",0f),tremoloHz=f("tremoloHz",4f),robotMix=f("robotMix",0f),robotHz=f("robotHz",80f),tune=b("tune"),tuneKey=o.optInt("tuneKey",0),tuneScale=o.optInt("tuneScale",0),tuneAmount=f("tuneAmount",1f),retuneMs=f("retuneMs",30f),shiftSemitones=f("shiftSemitones",0f)).sanitized()
            }
        }
    }
    val SCENES: Map<String,Settings> = linkedMapOf(
        "Clean master" to Settings(enabled=true,preset="Clean master"),
        "Warm vocal" to Settings(enabled=true,preset="Warm vocal",bands=PRESETS.getValue("Vocal clarity"),highPass=true,compressor=true,reverbMix=.12f),
        "Hard tune" to Settings(enabled=true,preset="Hard tune",highPass=true,tune=true,retuneMs=5f,compressor=true,reverbMix=.1f),
        "Dream voice" to Settings(enabled=true,preset="Dream voice",tune=true,retuneMs=80f,chorusMix=.2f,delayMix=.2f,reverbMix=.3f),
        "Robot" to Settings(enabled=true,preset="Robot",robotMix=.8f,robotHz=90f,driveMix=.2f,driveDb=10f),
        "Lo-fi tape" to Settings(enabled=true,preset="Lo-fi tape",highPass=true,lowPass=true,lowPassHz=6500f,crusherMix=.45f,crusherBits=10,driveMix=.25f,driveDb=5f),
        "Wide room" to Settings(enabled=true,preset="Wide room",width=1.4f,reverbMix=.22f,chorusMix=.12f),
        "Dub echo" to Settings(enabled=true,preset="Dub echo",delayMix=.4f,delaySync=true,delayBeats=.75f,delayFeedback=.65f),
        "Night listening" to Settings(enabled=true,preset="Night listening",bands=PRESETS.getValue("Night listening"),compressor=true,threshold=-30f,ratio=5f,makeup=5f)
    )
    fun load(context:Context):Settings=runCatching { context.getSharedPreferences("mortimer_prefs",0).getString("audio_effects",null)?.let { Settings.from(JSONObject(it)) }?:Settings() }.getOrDefault(Settings())
    fun save(context:Context,s:Settings) { context.getSharedPreferences("mortimer_prefs",0).edit().putString("audio_effects",s.sanitized().json().toString()).apply() }
    fun loadVoice(context:Context):Settings=runCatching { context.getSharedPreferences("mortimer_prefs",0).getString("voice_effects",null)?.let { Settings.from(JSONObject(it)) }?:SCENES.getValue("Warm vocal").copy(tune=true,preset="Voice studio") }.getOrDefault(SCENES.getValue("Warm vocal"))
    fun saveVoice(context:Context,s:Settings){context.getSharedPreferences("mortimer_prefs",0).edit().putString("voice_effects",s.sanitized().json().toString()).apply()}
    fun loadUserPresets(context:Context):Map<String,Settings> = runCatching {
        val prefs=context.getSharedPreferences("mortimer_prefs",0)
        val raw=prefs.getString("audio_lab_presets",null)
        if(raw!=null){ val o=JSONObject(raw);o.keys().asSequence().take(32).associateWith { Settings.from(o.getJSONObject(it)) } }
        else { val o=JSONObject(prefs.getString("eq_custom_presets","{}")?:"{}");o.keys().asSequence().take(32).associateWith { k->Settings(enabled=true,preset=k,bands=List(10){o.getJSONArray(k).optDouble(it,0.0).toFloat()}).sanitized() } }
    }.getOrDefault(emptyMap())
    fun saveUserPresets(context:Context,presets:Map<String,Settings>){ require(presets.size<=32);val o=JSONObject();presets.forEach{(k,v)->require(k.isNotBlank()&&k.length<=48);o.put(k,v.sanitized().json())};context.getSharedPreferences("mortimer_prefs",0).edit().putString("audio_lab_presets",o.toString()).apply() }
    @OptIn(UnstableApi::class) val processor=ProAudioProcessor()
    @Volatile private var current=Settings()
    @OptIn(UnstableApi::class) val available get()=processor.configured
    @OptIn(UnstableApi::class) fun attach(context:Context,sessionId:Int){ apply(load(context)) }
    fun release() { }
    @OptIn(UnstableApi::class) fun apply(s:Settings){ current=s.sanitized();processor.settings=current }
    fun currentSettings()=current
    private fun Float.safe(min:Float,max:Float,fallback:Float)=if(isFinite())coerceIn(min,max) else fallback
}
