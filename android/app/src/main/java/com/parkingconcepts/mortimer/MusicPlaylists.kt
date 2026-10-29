package com.parkingconcepts.mortimer

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

internal data class SavedTrack(val uri: String, val title: String, val artist: String = "", val album: String = "") {
    fun validate(): SavedTrack {
        require(uri.length in 1..8192 && Uri.parse(uri).scheme in listOf("content", "file")) { "A track needs a local file URI." }
        require(listOf(title, artist, album).all { it.length <= 1024 }) { "Track metadata is too long." }
        return this
    }
    fun mediaItem(): MediaItem = MediaItem.Builder().setMediaId(uri).setUri(uri).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).setIsPlayable(true).setIsBrowsable(false).build()).build()
    fun json(): JSONObject = JSONObject().put("uri", uri).put("title", title).put("artist", artist).put("album", album)
    companion object {
        fun from(json: JSONObject) = SavedTrack(json.getString("uri"), json.getString("title"), json.getString("artist"), json.getString("album")).validate()
    }
}

internal data class AudioQueue(val tracks: List<SavedTrack>, val selected: Int = 0) {
    fun validate(): AudioQueue {
        require(tracks.size in 1..10_000 && selected in tracks.indices) { "Choose an audio queue of 1–10000 tracks." }
        tracks.forEach { it.validate() }
        return this
    }
    fun json(): JSONObject = JSONObject().put("tracks", JSONArray().apply { tracks.forEach { put(it.json()) } }).put("selected", selected)
    companion object {
        fun from(json: JSONObject): AudioQueue {
            val arr = json.getJSONArray("tracks")
            require(arr.length() in 1..10_000)
            return AudioQueue((0 until arr.length()).map { SavedTrack.from(arr.getJSONObject(it)) }, json.getInt("selected")).validate()
        }
    }
}

internal data class MusicPlaylist(val id: String, val name: String, val tracks: List<SavedTrack>) {
    fun validate(): MusicPlaylist {
        require(UUID.fromString(id).toString() == id && name.isNotBlank() && name.length <= 80)
        AudioQueue(tracks).validate()
        return this
    }
}

/** Called only on the player's application looper. Video/mixed queues never replace saved music. */
internal fun snapshotAudioQueue(player: Player): AudioQueue? {
    if (player.mediaItemCount !in 1..10_000) return null
    val tracks = (0 until player.mediaItemCount).map { index ->
        val item = player.getMediaItemAt(index)
        val uri = item.localConfiguration?.uri ?: Uri.parse(item.mediaId)
        val music = item.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_MUSIC ||
            (item.mediaMetadata.mediaType == null && (item.localConfiguration?.mimeType?.startsWith("audio/") == true ||
                (uri.authority == "media" && "audio" in uri.pathSegments)))
        if (!music) return null
        SavedTrack(uri.toString(), item.mediaMetadata.title?.toString().orEmpty().take(1024),
            item.mediaMetadata.artist?.toString().orEmpty().take(1024), item.mediaMetadata.albumTitle?.toString().orEmpty().take(1024))
    }
    return runCatching { AudioQueue(tracks, player.currentMediaItemIndex).validate() }.getOrNull()
}

/** Atomic files plus an independent validated recovery snapshot. Corruption is never treated as an empty catalog. */
internal object MusicPlaylists {
    private const val MAX_BYTES = 16 * 1024 * 1024
    private fun read(context: Context, name: String, validate: (JSONObject) -> Unit): JSONObject? {
        val primary = File(context.filesDir, name)
        val backup = File(context.filesDir, "$name.recovery")
        if (listOf(primary, backup).none { it.exists() || File(it.path + ".bak").exists() }) return null
        for (file in listOf(primary, backup)) {
            try {
                return AtomicFile(file).openRead().use { stream -> JSONObject(stream.readBytesLimited(MAX_BYTES).toString(Charsets.UTF_8)) }
                    .also { require(it.getInt("version") == 1); validate(it) }
            } catch (_: Exception) { /* Try the independent snapshot; leave damaged files intact. */ }
        }
        throw IOException("Could not read $name or its recovery copy. Your saved data was kept.")
    }
    private fun write(context: Context, name: String, value: JSONObject) {
        val bytes = value.put("version", 1).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Saved music data exceeds 16 MB." }
        fun atomic(file: File) {
            val target = AtomicFile(file)
            val stream = target.startWrite()
            try { stream.write(bytes); target.finishWrite(stream) }
            catch (failure: Throwable) { target.failWrite(stream); throw failure }
        }
        atomic(File(context.filesDir, name))
        runCatching { atomic(File(context.filesDir, "$name.recovery")) }
    }
    private fun decodeLists(value: JSONObject): List<MusicPlaylist> {
        val arr = value.getJSONArray("lists")
        require(arr.length() <= 100)
        val lists = (0 until arr.length()).map { index ->
            val json = arr.getJSONObject(index)
            MusicPlaylist(json.getString("id"), json.getString("name"), AudioQueue.from(json).tracks).validate()
        }
        require(lists.map { it.id }.distinct().size == lists.size && lists.sumOf { it.tracks.size } <= 50_000)
        return lists
    }
    @Synchronized fun load(context: Context): List<MusicPlaylist> = read(context, "music_playlists.json") { decodeLists(it) }?.let(::decodeLists).orEmpty()
    @Synchronized fun update(context: Context, transform: (List<MusicPlaylist>) -> List<MusicPlaylist>): List<MusicPlaylist> {
        val lists = transform(load(context))
        val json = JSONObject().put("lists", JSONArray().apply { lists.forEach {
            put(AudioQueue(it.tracks).json().put("id", it.id).put("name", it.name))
        } })
        decodeLists(json)
        write(context, "music_playlists.json", json)
        return lists
    }
    @Synchronized fun loadQueue(context: Context): AudioQueue? = read(context, "music_queue.json") { AudioQueue.from(it) }?.let(AudioQueue::from)
    @Synchronized fun saveQueue(context: Context, queue: AudioQueue) {
        queue.validate()
        loadQueue(context) // Refuse to overwrite an unreadable primary and recovery file.
        write(context, "music_queue.json", queue.json())
    }
}
