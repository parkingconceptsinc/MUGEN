package com.parkingconcepts.mortimer

import kotlin.math.roundToLong

internal data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)
internal data class SubtitleReference(val subtitleMs: Long, val videoMs: Long) {
    init { require(subtitleMs >= 0 && videoMs >= 0) { "Reference times cannot be negative." } }
}
internal data class SubtitleSync(val offsetMs: Long = 0, val scale: Double = 1.0) {
    init {
        require(scale.isFinite() && scale in 0.5..2.0) { "Subtitle timing scale must be between 0.5 and 2." }
        require(offsetMs in -86_400_000L..86_400_000L) { "Subtitle offset cannot exceed 24 hours." }
    }
    fun videoTime(subtitleMs: Long) = (subtitleMs * scale + offsetMs).roundToLong()
    fun sourceTime(videoMs: Long) = (videoMs.toDouble() - offsetMs) / scale

    companion object {
        fun calibrate(first: SubtitleReference, second: SubtitleReference? = null): SubtitleSync {
            val scale = if (second == null) 1.0 else {
                val sourceGap = second.subtitleMs - first.subtitleMs
                val videoGap = second.videoMs - first.videoMs
                require(sourceGap >= 1000 && videoGap >= 1000) { "Choose a second phrase at least one second after the first." }
                videoGap.toDouble() / sourceGap
            }
            return SubtitleSync((first.videoMs - first.subtitleMs * scale).roundToLong(), scale)
        }
    }
}

internal fun decodeSubtitleBytes(bytes: ByteArray): String = when {
    bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> bytes.toString(Charsets.UTF_16LE)
    bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> bytes.toString(Charsets.UTF_16BE)
    else -> bytes.toString(Charsets.UTF_8).let { if ('\uFFFD' in it) bytes.toString(java.nio.charset.Charset.forName("windows-1252")) else it }
}

internal class SubtitleTimeline(cues: List<SubtitleCue>) {
    val cues = cues.sortedBy { it.startMs }
    private val maxEnd = LongArray(cues.size)
    init { this.cues.forEachIndexed { i, cue -> maxEnd[i] = maxOf(cue.endMs, maxEnd.getOrElse(i - 1) { 0L }) } }

    fun textAt(videoMs: Long, sync: SubtitleSync): String {
        val time = sync.sourceTime(videoMs)
        var lo = 0
        var hi = cues.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= time) lo = mid + 1 else hi = mid
        }
        val active = ArrayList<String>()
        var i = lo - 1
        while (i >= 0 && maxEnd[i] > time && active.size < 8) {
            if (cues[i].endMs > time) active.add(cues[i].text)
            i--
        }
        return active.asReversed().joinToString("\n")
    }
}

/** Apply calibration once, optionally intersecting and rebasing to an edited clip. */
internal fun SubtitleTimeline.toSrt(sync: SubtitleSync, clipStartMs: Long = 0, clipEndMs: Long = Long.MAX_VALUE): String {
    require(clipStartMs >= 0 && clipEndMs > clipStartMs)
    fun stamp(ms: Long): String = java.lang.String.format(java.util.Locale.ROOT, "%02d:%02d:%02d,%03d",
        ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    var index = 0
    return buildString {
        for (cue in cues) {
            val start = maxOf(clipStartMs, sync.videoTime(cue.startMs))
            val end = minOf(clipEndMs, sync.videoTime(cue.endMs))
            if (end <= start) continue
            append(++index).append('\n').append(stamp(start - clipStartMs)).append(" --> ")
                .append(stamp(end - clipStartMs)).append('\n')
            append(cue.text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                .append("\n\n")
        }
    }
}

internal fun subtitleTimestamp(raw: String): Long? {
    val match = Regex("^(?:(\\d{1,6}):)?(\\d{2}):(\\d{2})[.,](\\d{1,3})$").matchEntire(raw) ?: return null
    val hours = match.groupValues[1].toLongOrNull() ?: 0
    val minutes = match.groupValues[2].toLong()
    val seconds = match.groupValues[3].toLong()
    if (minutes > 59 || seconds > 59) return null
    return ((hours * 60 + minutes) * 60 + seconds) * 1000 + match.groupValues[4].padEnd(3, '0').toLong()
}

internal fun parseSubtitles(text: String): SubtitleTimeline {
    require(text.length <= 4 * 1024 * 1024) { "Subtitle file is too large." }
    val lines = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').lines()
    val cues = ArrayList<SubtitleCue>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trim()
        if (line == "NOTE" || line.startsWith("NOTE ") || line == "STYLE" || line == "REGION") {
            while (i < lines.size && lines[i].isNotBlank()) i++
            continue
        }
        if (!line.contains("-->")) { i++; continue }
        val parts = line.split("-->", limit = 2)
        val start = subtitleTimestamp(parts[0].trim())
        val end = subtitleTimestamp(parts[1].trim().substringBefore(' ').substringBefore('\t'))
        i++
        val body = ArrayList<String>()
        while (i < lines.size && lines[i].isNotBlank()) { body.add(lines[i]); i++ }
        if (start == null || end == null || end <= start) continue
        val plain = body.joinToString("\n").replace(Regex("<[^>]*>"), "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&").trim()
        require(plain.length <= 8192) { "A subtitle phrase is too long." }
        if (plain.isNotEmpty()) cues.add(SubtitleCue(start, end, plain))
        require(cues.size <= 20_000) { "Subtitle file contains too many phrases." }
    }
    require(cues.isNotEmpty()) { "No valid SRT or WebVTT subtitles were found." }
    return SubtitleTimeline(cues)
}
