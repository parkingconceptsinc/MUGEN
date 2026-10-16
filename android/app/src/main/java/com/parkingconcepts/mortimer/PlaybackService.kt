package com.parkingconcepts.mortimer

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executors

/**
 * Single Media3 library/session for the app UI and Android Auto.
 * Both clients now control the same ExoPlayer and browse the same library.
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {
    private var librarySession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private val searchResults = linkedMapOf<String, List<MediaItem>>()
    // MediaStore queries and SAF-library reads must not run on Media3's service callback thread.
    private val libraryExecutor: ListeningExecutorService = MoreExecutors.listeningDecorator(
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "MUGEN-library").apply { isDaemon = true }
        }
    )

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // Keep the native equalizer/balance pipeline while sharing this one player
        // between the in-app controller and Android Auto.
        val renderers = object : DefaultRenderersFactory(this) {
            @UnstableApi
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(AudioEffects.balanceProcessor))
                .build()
        }
        player = ExoPlayer.Builder(this, renderers).build().apply {
            setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
        }
        val sessionId = (getSystemService(AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
        player.setAudioSessionId(sessionId)
        AudioEffects.attach(this, sessionId)
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val uri = mediaItem?.localConfiguration?.uri ?: return
                // Checking imported SAF entries reads persistent storage; keep that work
                // off the player/application thread so transitions stay responsive.
                runCatching {
                    libraryExecutor.execute {
                        if (isKnownAudioUri(uri)) {
                            getSharedPreferences("mortimer_library", MODE_PRIVATE)
                                .edit()
                                .putString(LAST_PLAYED_URI_KEY, uri.toString())
                                .apply()
                        }
                    }
                }
            }
        })
        librarySession = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = librarySession

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Mortimer Player")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId != ROOT_ID) {
                return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
            }
            return libraryExecutor.submit<LibraryResult<ImmutableList<MediaItem>>> {
                val allItems = loadAudioLibrary()
                // Use Long arithmetic so a malformed/very large page index cannot overflow Int.
                val safePage = page.coerceAtLeast(0).toLong()
                val safePageSize = pageSize.coerceAtLeast(0).toLong()
                val from = (safePage * safePageSize).coerceIn(0L, allItems.size.toLong()).toInt()
                val to = (from.toLong() + safePageSize).coerceAtMost(allItems.size.toLong()).toInt()
                LibraryResult.ofItemList(ImmutableList.copyOf(allItems.subList(from, to)), params)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            val resultFuture = SettableFuture.create<LibraryResult<Void>>()
            libraryExecutor.execute {
                try {
                    val results = findAudioMatches(query)
                    rememberSearchResults(query, results)
                    // MediaLibrarySession notifications belong on the player application looper.
                    Handler(player.applicationLooper).post {
                        try {
                            session.notifySearchResultChanged(browser, query, results.size, params)
                            resultFuture.set(LibraryResult.ofVoid())
                        } catch (failure: Throwable) {
                            resultFuture.setException(failure)
                        }
                    }
                } catch (failure: Throwable) {
                    resultFuture.setException(failure)
                }
            }
            return resultFuture
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return libraryExecutor.submit<LibraryResult<ImmutableList<MediaItem>>> {
                val allMatches = searchResults[query] ?: findAudioMatches(query)
                val safePage = page.coerceAtLeast(0).toLong()
                val safePageSize = pageSize.coerceAtLeast(0).toLong()
                val from = (safePage * safePageSize).coerceIn(0L, allMatches.size.toLong()).toInt()
                val to = (from.toLong() + safePageSize).coerceAtMost(allMatches.size.toLong()).toInt()
                LibraryResult.ofItemList(
                    ImmutableList.copyOf(allMatches.subList(from, to)),
                    params
                )
            }
        }

        @UnstableApi
        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return libraryExecutor.submit<LibraryResult<MediaItem>> {
                val uri = runCatching { Uri.parse(mediaId) }.getOrNull()
                if (uri == null || uri.scheme == null) {
                    LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                } else {
                    val item = MediaItem.Builder()
                        .setMediaId(uri.toString())
                        .setUri(uri)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(displayName(uri))
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .build()
                        )
                        .build()
                    LibraryResult.ofItem(item, null)
                }
            }
        }

        @UnstableApi
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaItemsWithStartPosition> {
            return libraryExecutor.submit<MediaItemsWithStartPosition> {
                val library = loadAudioLibrary()
                if (library.isEmpty()) {
                    throw IllegalStateException("No local audio is available for playback resumption.")
                }
                val lastPlayed = lastPlayedItem(library)
                val selectedIndex = library.indexOfFirst { it.mediaId == lastPlayed?.mediaId }
                    .takeIf { it >= 0 } ?: 0
                MediaItemsWithStartPosition(library, selectedIndex, C.TIME_UNSET)
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            return libraryExecutor.submit<List<MediaItem>> {
                val resolved = mutableListOf<MediaItem>()
                mediaItems.forEach { item ->
                    val searchQuery = item.requestMetadata.searchQuery
                    if (searchQuery != null) {
                        val library = loadAudioLibrary()
                        val selected = if (searchQuery.isBlank()) {
                            lastPlayedItem(library) ?: library.firstOrNull()
                        } else {
                            findAudioMatches(searchQuery).firstOrNull()
                        }
                        if (selected != null) resolved += selected
                    } else {
                        resolveUri(item)?.let { uri ->
                            resolved += MediaItem.Builder()
                                .setMediaId(uri.toString())
                                .setUri(uri)
                                .setMediaMetadata(
                                    item.mediaMetadata.buildUpon()
                                        .setTitle(item.mediaMetadata.title ?: displayName(uri))
                                        .setIsPlayable(true)
                                        .build()
                                )
                                .build()
                        }
                    }
                }
                resolved
            }
        }

        @UnstableApi
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaItemsWithStartPosition> {
            return libraryExecutor.submit<MediaItemsWithStartPosition> {
                if (mediaItems.size == 1) {
                    val selectedUri = resolveUri(mediaItems.first())
                    if (selectedUri != null && isKnownAudioUri(selectedUri)) {
                        val queue = loadAudioLibrary()
                        val selectedIndex = queue.indexOfFirst { it.localConfiguration?.uri == selectedUri }
                        if (selectedIndex >= 0) {
                            return@submit MediaItemsWithStartPosition(queue, selectedIndex, startPositionMs)
                        }
                    }
                }
                MediaItemsWithStartPosition(
                    mediaItems.mapNotNull { item ->
                        resolveUri(item)?.let { uri ->
                            MediaItem.Builder()
                                .setMediaId(uri.toString())
                                .setUri(uri)
                                .setMediaMetadata(
                                    item.mediaMetadata.buildUpon()
                                        .setTitle(item.mediaMetadata.title ?: displayName(uri))
                                        .setIsPlayable(true)
                                        .build()
                                )
                                .build()
                        }
                    },
                    startIndex,
                    startPositionMs
                )
            }
        }

        private fun resolveUri(item: MediaItem): Uri? {
            item.localConfiguration?.uri?.let { return it }
            item.requestMetadata.mediaUri?.let { return it }
            val rawId = item.mediaId
            if (!rawId.startsWith("content://") && !rawId.startsWith("file://")) return null
            return runCatching { Uri.parse(rawId) }.getOrNull()
        }
    }

    private fun isKnownAudioUri(uri: Uri): Boolean {
        val rawUri = uri.toString()
        // The current app persists imported items in library.json. Keep checking the
        // legacy preference set too so users upgrading from an older build can resume.
        val imported = LibraryStore.load(this).any { it.kind == "audio" && it.uri == rawUri }
        if (imported) return true
        val legacy = getSharedPreferences("mortimer_library", MODE_PRIVATE)
            .getStringSet("audio", emptySet()).orEmpty()
        if (legacy.any { row -> row.substringBefore('\t') == rawUri }) return true

        // MediaStore audio rows use paths such as /external/audio/media/<id>.
        return uri.authority == "media" && uri.pathSegments.any { it.equals("audio", ignoreCase = true) }
    }

    private fun loadAudioLibrary(): List<MediaItem> {
        val items = linkedMapOf<Uri, MediaItem>()
        if (hasAudioPermission()) {
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.MediaColumns.DISPLAY_NAME
            )
            runCatching {
                contentResolver.query(
                    collection,
                    projection,
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                    null,
                    "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val fileNameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                        val title = cursor.getString(titleColumn)?.takeIf { it.isNotBlank() }
                            ?: cursor.getString(fileNameColumn)?.takeIf { it.isNotBlank() }
                            ?: "Unknown title"
                        val artist = cursor.getString(artistColumn)?.takeIf { it.isNotBlank() }
                            ?: "Unknown artist"
                        val album = cursor.getString(albumColumn)?.takeIf { it.isNotBlank() }.orEmpty()
                        items[uri] = buildAudioItem(uri, title, artist, album)
                    }
                }
            }
        }

        // Read the current JSON library first; this is where current versions store
        // SAF imports from phone storage, SD cards, and USB drives.
        LibraryStore.load(this)
            .asSequence()
            .filter { it.kind == "audio" && it.uri.isNotBlank() }
            .sortedBy { it.title.lowercase() }
            .forEach { imported ->
                runCatching { Uri.parse(imported.uri) }.getOrNull()?.let { uri ->
                    if (uri !in items) {
                        items[uri] = buildAudioItem(
                            uri,
                            imported.title.ifBlank { "Unknown title" },
                            imported.artist.ifBlank { "Unknown artist" },
                            imported.album
                        )
                    }
                }
            }

        // Retain legacy imports while older installations migrate to library.json.
        getSharedPreferences("mortimer_library", MODE_PRIVATE)
            .getStringSet("audio", emptySet()).orEmpty()
            .mapNotNull { row ->
                val parts = row.split('\t')
                if (parts.size >= 2 && parts[0].isNotBlank()) parts else null
            }
            .sortedBy { it[1].lowercase() }
            .forEach { parts ->
                runCatching { Uri.parse(parts[0]) }.getOrNull()?.let { uri ->
                    if (uri !in items) {
                        items[uri] = buildAudioItem(
                            uri,
                            parts[1].ifBlank { "Unknown title" },
                            parts.getOrNull(2)?.ifBlank { "Unknown artist" } ?: "Unknown artist",
                            parts.getOrNull(3).orEmpty(),
                            parts.getOrNull(4).orEmpty()
                        )
                    }
                }
            }
        return items.values.toList()
    }

    private fun findAudioMatches(query: String): List<MediaItem> {
        val normalized = query.trim().lowercase()
        val library = loadAudioLibrary()
        if (normalized.isEmpty()) return library

        return library.filter { item ->
            val metadata = item.mediaMetadata
            sequenceOf(metadata.title, metadata.artist, metadata.albumTitle)
                .filterNotNull()
                .any { it.toString().lowercase().contains(normalized) }
        }.sortedWith(
            compareBy<MediaItem> {
                if (it.mediaMetadata.title?.toString()?.equals(normalized, ignoreCase = true) == true) 0 else 1
            }.thenBy {
                if (it.mediaMetadata.title?.toString()?.startsWith(normalized, ignoreCase = true) == true) 0 else 1
            }.thenBy { it.mediaMetadata.title?.toString()?.lowercase().orEmpty() }
        )
    }

    private fun rememberSearchResults(query: String, results: List<MediaItem>) {
        if (searchResults.size >= MAX_CACHED_SEARCHES && query !in searchResults) {
            searchResults.remove(searchResults.keys.first())
        }
        searchResults[query] = results
    }

    private fun lastPlayedItem(library: List<MediaItem>): MediaItem? {
        val lastPlayedUri = getSharedPreferences("mortimer_library", MODE_PRIVATE)
            .getString(LAST_PLAYED_URI_KEY, null)
        return library.firstOrNull { it.mediaId == lastPlayedUri }
    }

    private fun buildAudioItem(
        uri: Uri,
        title: String,
        artist: String,
        album: String,
        genre: String = ""
    ): MediaItem =
        MediaItem.Builder()
            .setMediaId(uri.toString())
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setGenre(genre)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()

    private fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "Unknown title"
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: name
                }
            }
        }
        return name
    }

    private fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        // Release audio effects and player even if session construction failed partway through.
        AudioEffects.release()
        librarySession?.release()
        librarySession = null
        if (::player.isInitialized) player.release()
        libraryExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val ROOT_ID = "mortimer_root"
        private const val LAST_PLAYED_URI_KEY = "last_played_uri"
        private const val MAX_CACHED_SEARCHES = 12
    }
}
