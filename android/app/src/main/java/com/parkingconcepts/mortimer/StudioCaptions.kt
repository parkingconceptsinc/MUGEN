package com.parkingconcepts.mortimer

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import kotlin.math.roundToLong

internal data class StudioCaptions(val timeline: SubtitleTimeline?, val sync: SubtitleSync, val uri: Uri?, val enabled: Boolean = true)
internal fun loadStudioCaptions(context: Context, video: String, budget: Int = MAX_METADATA_BYTES): Pair<StudioCaptions, Int> {
    val prefs = context.getSharedPreferences("video_settings", Context.MODE_PRIVATE)
    val key = mediaKey(video)
    val raw = prefs.getString("${key}_subtitle", null) ?: return StudioCaptions(null, SubtitleSync(), null) to 0
    val sync = SubtitleSync(prefs.getLong("${key}_offset", 0), prefs.getString("${key}_scale", "1")!!.toDouble())
    val uri = Uri.parse(raw)
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytesLimited(minOf(MAX_METADATA_BYTES, budget)) }
        ?: error("No se pueden abrir los subtítulos. Selecciona el archivo de nuevo.")
    return StudioCaptions(parseSubtitles(decodeSubtitleBytes(bytes)), sync, uri, prefs.getBoolean("${key}_enabled", true)) to bytes.size
}

internal suspend fun studioSubtitleTimeline(context: Context, project: StudioProject): Pair<SubtitleTimeline, Set<Uri>> {
    val captions = HashMap<String, StudioCaptions>()
    var remaining = 16 * 1024 * 1024
    val cues = ArrayList<SubtitleCue>()
    val protected = project.clips.mapTo(HashSet()) { Uri.parse(it.source.uri) }
    var offset = 0L
    for (clip in project.clips) {
        currentCoroutineContext().ensureActive()
        val caption = captions[clip.source.uri] ?: loadStudioCaptions(context, clip.source.uri, remaining).let { (loaded, bytes) -> remaining -= bytes; captions[clip.source.uri] = loaded; loaded }
        caption.uri?.let { protected.add(it) }
        caption.timeline?.cues?.forEach { cue ->
            currentCoroutineContext().ensureActive()
            val start = maxOf(clip.edit.startMs, caption.sync.videoTime(cue.startMs))
            val end = minOf(clip.edit.endMs, caption.sync.videoTime(cue.endMs))
            if (end > start) {
                val outputStart = offset + ((start - clip.edit.startMs).toDouble() / clip.edit.speed).roundToLong()
                val outputEnd = offset + ((end - clip.edit.startMs).toDouble() / clip.edit.speed).roundToLong()
                if (outputEnd > outputStart) cues.add(SubtitleCue(outputStart, outputEnd, cue.text))
            }
            require(cues.size <= 50_000) { "Demasiadas frases para el SRT del montaje." }
        }
        offset += clip.duration
    }
    require(cues.isNotEmpty()) { "No hay subtítulos dentro de los clips del montaje." }
    val timeline = SubtitleTimeline(cues)
    require(timeline.toSrt(SubtitleSync()).toByteArray().size <= MAX_METADATA_BYTES) { "El SRT del montaje supera 4 MiB." }
    return timeline to protected
}

@Composable
internal fun StudioCaptionTools(clip: StudioClip, sourceHead: Long, playing: Boolean, onSeek: (Long) -> Unit, onPlay: () -> Unit,
    pause: () -> Unit, onLoaded: (StudioCaptions) -> Unit, openRequest: Int = 0, showButton: Boolean = true) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("video_settings", Context.MODE_PRIVATE) }
    val key = mediaKey(clip.source.uri)
    var refresh by remember { mutableIntStateOf(0) }
    var captions by remember(key) { mutableStateOf(StudioCaptions(null, SubtitleSync(), null)) }
    var message by remember(key) { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf(false) }
    var reference by remember(key, captions.uri) { mutableStateOf<SubtitleReference?>(null) }
    LaunchedEffect(openRequest) { if (openRequest > 0) { pause(); open = true } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            persistReadPermission(context, uri)
            prefs.edit().putString("${key}_subtitle", uri.toString()).putLong("${key}_offset", 0).putString("${key}_scale", "1").apply()
            refresh++
        }
    }
    LaunchedEffect(key, refresh) {
        captions = StudioCaptions(null, SubtitleSync(), null); onLoaded(captions)
        try { captions = withContext(Dispatchers.IO) { loadStudioCaptions(context, clip.source.uri).first }; message = captions.timeline?.let { "${it.cues.size} frases cargadas" }; onLoaded(captions) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { message = failure.message }
    }
    // This composable stays mounted outside the inspector, so captions continue loading during playback.
    if (open) SubtitleControls(captions.timeline, captions.sync, captions.enabled, sourceHead, message,
        onLoad = { pause(); picker.launch(arrayOf("text/*", "application/x-subrip", "*/*")) }, onEnabled = { enabled ->
            captions = captions.copy(enabled = enabled); onLoaded(captions)
            prefs.edit().putBoolean("${key}_enabled", enabled).apply()
        },
        reference = reference, onReference = { reference = it }, playing = playing, onTogglePlayback = onPlay,
        onSeek = { onSeek(sourceHead + it) }, onSync = { sync ->
            captions = captions.copy(sync = sync); onLoaded(captions)
            prefs.edit().putLong("${key}_offset", sync.offsetMs).putString("${key}_scale", sync.scale.toString()).apply()
        }, onClose = { open = false })
    if (showButton) TextButton(onClick = { pause(); open = true }) { Text("Subtítulos del visor · cargar / sincronizar") }
}
