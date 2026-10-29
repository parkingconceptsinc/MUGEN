package com.parkingconcepts.mortimer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToLong

internal const val MIN_TRIM_MS = 500L
internal data class StudioSource(val uri: String, val title: String, val duration: Long, val width: Int = 0, val height: Int = 0,
    val fps: Double = 30.0, val estimatedFps: Boolean = true) {
    fun validate(): StudioSource {
        require(Uri.parse(uri).scheme in listOf("content", "file") && uri.length <= 8192 && title.length <= 1024)
        require(duration in MIN_TRIM_MS..604_800_000) { "The video «$title» must be between 0.5 seconds and 7 days long." }
        require(width in 0..32768 && height in 0..32768 && fps.isFinite() && fps in 1.0..240.0) { "Unsupported video dimensions or frame rate: $title" }
        return this
    }
    fun snap(time: Long) = ((time * fps / 1000).roundToLong() * 1000 / fps).roundToLong().coerceIn(0, duration)
}
internal data class StudioClip(val id: String = UUID.randomUUID().toString(), val source: StudioSource, val edit: VideoEditSpec) {
    val duration: Long get() = ((edit.endMs - edit.startMs) / edit.speed.toDouble()).roundToLong()
    fun validate(): StudioClip { UUID.fromString(id); source.validate(); edit.validate(source.duration); return this }
}
internal data class StudioLocation(val index: Int, val sourceMs: Long, val offsetMs: Long)
internal data class StudioProject(val id: String = UUID.randomUUID().toString(), val name: String, val clips: List<StudioClip>,
    val aspect: String = "source", val height: Int = 720) {
    val duration: Long get() = clips.sumOf { it.duration }
    fun validate(): StudioProject {
        UUID.fromString(id)
        require(name.isNotBlank() && name.length <= 80 && clips.size in 1..64) { "A project needs between 1 and 64 clips." }
        clips.forEach { it.validate() }
        require(clips.map { it.id }.distinct().size == clips.size && duration <= 86_400_000) { "Project is too long or clip identifiers are duplicated." }
        require(aspect in listOf("source", "16:9", "9:16", "1:1") && height in listOf(0, 720, 1080))
        return this
    }
    fun offset(index: Int) = clips.take(index).sumOf { it.duration }
    fun locate(time: Long): StudioLocation {
        var rest = time.coerceIn(0, duration)
        var offset = 0L
        clips.forEachIndexed { index, clip ->
            if (rest < clip.duration || index == clips.lastIndex) return StudioLocation(index,
                (clip.edit.startMs + rest.toDouble() * clip.edit.speed).roundToLong().coerceIn(clip.edit.startMs, clip.edit.endMs), offset)
            rest -= clip.duration; offset += clip.duration
        }
        error("Empty project")
    }
    fun projectTime(clipId: String, sourceMs: Long): Long {
        val index = clips.indexOfFirst { it.id == clipId }.coerceAtLeast(0)
        val clip = clips[index]
        return offset(index) + ((sourceMs.coerceIn(clip.edit.startMs, clip.edit.endMs) - clip.edit.startMs) / clip.edit.speed.toDouble()).roundToLong()
    }
    fun replace(id: String, edit: VideoEditSpec) = copy(clips = clips.map { if (it.id == id) it.copy(edit = edit.validate(it.source.duration)) else it }).validate()
    fun split(time: Long): StudioProject {
        require(clips.size < 64) { "Up to 64 clips are supported." }
        val at = locate(time)
        val clip = clips[at.index]
        val source = clip.source.snap(at.sourceMs)
        require(source - clip.edit.startMs >= MIN_TRIM_MS && clip.edit.endMs - source >= MIN_TRIM_MS) { "Keep at least 0.5 source seconds on each side of the cut." }
        val pieces = listOf(clip.copy(edit = clip.edit.copy(endMs = source)), clip.copy(id = UUID.randomUUID().toString(), edit = clip.edit.copy(startMs = source)))
        return copy(clips = clips.take(at.index) + pieces + clips.drop(at.index + 1)).validate()
    }
    fun move(id: String, delta: Int): StudioProject {
        val index = clips.indexOfFirst { it.id == id }
        require(index >= 0)
        val target = (index + delta).coerceIn(0, clips.lastIndex)
        val next = clips.toMutableList().apply { add(target, removeAt(index)) }
        return copy(clips = next).validate()
    }
    fun json(): JSONObject = JSONObject().put("version", 1).put("id", id).put("name", name).put("aspect", aspect).put("height", height)
        .put("clips", JSONArray().apply { clips.forEach { clip ->
            val s = clip.source; val e = clip.edit
            put(JSONObject().put("id", clip.id).put("uri", s.uri).put("title", s.title).put("duration", s.duration)
                .put("width", s.width).put("height", s.height).put("fps", s.fps).put("estimated", s.estimatedFps)
                .put("in", e.startMs).put("out", e.endMs).put("rotation", e.rotation).put("mute", e.mute)
                .put("flip", e.flip).put("brightness", e.brightness).put("contrast", e.contrast).put("saturation", e.saturation)
                .put("speed", e.speed).put("volume", e.volume))
        } })
    companion object {
        fun from(json: JSONObject): StudioProject {
            require(json.getInt("version") == 1)
            val list = json.getJSONArray("clips"); require(list.length() in 1..64)
            return StudioProject(json.getString("id"), json.getString("name"), (0 until list.length()).map { i ->
                val c = list.getJSONObject(i)
                val source = StudioSource(c.getString("uri"), c.getString("title"), c.getLong("duration"), c.getInt("width"), c.getInt("height"), c.getDouble("fps"), c.getBoolean("estimated"))
                StudioClip(c.getString("id"), source, VideoEditSpec(c.getLong("in"), c.getLong("out"), c.getInt("rotation"), c.getBoolean("mute"),
                    flip = c.getBoolean("flip"), brightness = c.getDouble("brightness").toFloat(), contrast = c.getDouble("contrast").toFloat(),
                    saturation = c.getDouble("saturation").toFloat(), speed = c.getDouble("speed").toFloat(), volume = c.getDouble("volume").toFloat()))
            }, json.getString("aspect"), json.getInt("height")).validate()
        }
    }
}

/** Whole-edit transactions: dragging a handle creates one undo step, not hundreds. */
internal class StudioHistory(initial: StudioProject) {
    var current = initial.validate(); private set
    private val undo = ArrayDeque<StudioProject>()
    private val redo = ArrayDeque<StudioProject>()
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    fun commit(next: StudioProject): StudioProject {
        next.validate()
        if (next == current) return current
        undo.addLast(current); while (undo.size > 50) undo.removeFirst()
        redo.clear(); current = next; return current
    }
    fun undo(): StudioProject { if (canUndo) { redo.addLast(current); current = undo.removeLast() }; return current }
    fun redo(): StudioProject { if (canRedo) { undo.addLast(current); current = redo.removeLast() }; return current }
}

internal fun studioTime(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    return java.lang.String.format(java.util.Locale.ROOT, "%02d:%02d:%02d.%03d", t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000)
}
internal fun parseStudioTime(value: String): Long? {
    val match = Regex("^(?:(\\d{1,3}):)?(\\d{1,2}):(\\d{2})(?:[.,](\\d{1,3}))?$").matchEntire(value.trim())
    if (match != null) {
        val hours = match.groupValues[1].toLongOrNull() ?: 0
        val minutes = match.groupValues[2].toLong()
        val seconds = match.groupValues[3].toLong()
        if (minutes > 59 || seconds > 59) return null
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + match.groupValues[4].padEnd(3, '0').toLong()
    }
    return value.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..604800.0 }?.let { (it * 1000).roundToLong() }
}

internal fun readStudioSource(context: Context, uri: Uri, title: String): StudioSource {
    val reader = MediaMetadataRetriever()
    val extractor = MediaExtractor()
    try {
        val metadataReady = runCatching { reader.setDataSource(context, uri) }.isSuccess
        fun metadata(key: Int) = if (metadataReady) reader.extractMetadata(key) else null
        val track = runCatching {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount).asSequence().map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        }.getOrNull()
        fun integer(key: String) = track?.let { runCatching { it.getInteger(key) }.getOrNull() }
        val fps = track?.let { format ->
            runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrElse {
                runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrNull()
            }
        }?.takeIf { it.isFinite() && it in 1.0..240.0 }
        val duration = metadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
            ?: track?.let { runCatching { it.getLong(MediaFormat.KEY_DURATION) / 1000 }.getOrNull() }
            ?: error("Could not read this video's duration. Select it again or try another format.")
        val rotation = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: integer(MediaFormat.KEY_ROTATION) ?: 0
        val w = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.takeIf { it > 0 } ?: integer(MediaFormat.KEY_WIDTH) ?: 0
        val h = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.takeIf { it > 0 } ?: integer(MediaFormat.KEY_HEIGHT) ?: 0
        require(w > 0 && h > 0) { "This file does not contain a readable video track. Select another video." }
        return StudioSource(uri.toString(), title.take(1024), duration, if (rotation % 180 == 0) w else h,
            if (rotation % 180 == 0) h else w, fps ?: 30.0, fps == null).validate()
    } finally { reader.release(); extractor.release() }
}
