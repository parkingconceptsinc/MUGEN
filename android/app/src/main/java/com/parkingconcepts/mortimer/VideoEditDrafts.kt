package com.parkingconcepts.mortimer

import android.content.Context
import org.json.JSONObject

internal object VideoEditDrafts {
    fun load(context: Context, uri: String, duration: Long): VideoEditSpec? = runCatching {
        val raw = context.getSharedPreferences("video_edit_drafts", Context.MODE_PRIVATE).getString(mediaKey(uri), null) ?: return null
        val json = JSONObject(raw)
        require(json.getLong("duration") == duration) // A replaced source must not reuse a stale trim.
        VideoEditSpec(json.getLong("start"), json.getLong("end"), json.getInt("rotation"), json.getBoolean("mute"), json.getInt("height")).validate(duration)
    }.getOrNull()

    fun save(context: Context, uri: String, duration: Long, spec: VideoEditSpec) {
        spec.validate(duration)
        val json = JSONObject().put("duration", duration).put("start", spec.startMs).put("end", spec.endMs)
            .put("rotation", spec.rotation).put("mute", spec.mute).put("height", spec.height)
        context.getSharedPreferences("video_edit_drafts", Context.MODE_PRIVATE).edit().putString(mediaKey(uri), json.toString()).apply()
    }
}
