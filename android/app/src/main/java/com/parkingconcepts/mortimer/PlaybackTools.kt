package com.parkingconcepts.mortimer

import android.content.Context
import java.security.MessageDigest

internal fun mediaKey(uri: String): String = MessageDigest.getInstance("SHA-256")
    .digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }

internal object PlaybackPositions {
    fun get(context: Context, uri: String): Long = context.getSharedPreferences("playback_positions", Context.MODE_PRIVATE)
        .getLong(mediaKey(uri), 0L).coerceIn(0L, 7 * 24 * 60 * 60 * 1000L)
    fun save(context: Context, uri: String, position: Long, duration: Long = 0) {
        if (uri.isBlank()) return
        val resume = if (duration > 0 && position >= duration - 2000) 0L else position.coerceAtLeast(0)
        context.getSharedPreferences("playback_positions", Context.MODE_PRIVATE).edit().putLong(mediaKey(uri), resume).apply()
    }
}

internal data class PlaybackLoop(val startMs: Long, val endMs: Long) {
    init { require(startMs >= 0 && endMs - startMs >= 500) { "B must be at least half a second after A." } }
    fun shouldRepeat(position: Long) = position >= endMs
}

internal data class VideoEditSpec(val startMs: Long, val endMs: Long, val rotation: Int = 0, val mute: Boolean = false, val height: Int = 0) {
    fun validate(duration: Long): VideoEditSpec {
        require(duration > 0 && startMs >= 0 && endMs <= duration && endMs - startMs >= 500) { "Choose a range of at least half a second inside this video." }
        require(rotation in listOf(0, 90, 180, 270)) { "Invalid rotation." }
        require(height in listOf(0, 720, 1080)) { "Invalid output size." }
        return this
    }
}

internal fun playerTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
}
