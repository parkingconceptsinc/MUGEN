package com.parkingconcepts.mortimer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition

/** Resolves both legacy Android Auto requests and native controller requests. */
@UnstableApi
internal class PlaybackRequestResolver(
    private val loadLibrary: () -> List<MediaItem>,
    private val findMatches: (String) -> List<MediaItem>,
    private val lastPlayed: (List<MediaItem>) -> MediaItem?,
    private val isKnownAudioUri: (Uri) -> Boolean,
    private val displayName: (Uri) -> String,
) {
    fun resolveItem(item: MediaItem): MediaItem? {
        item.requestMetadata.searchQuery?.let { query ->
            return if (query.isBlank()) {
                val library = loadLibrary()
                lastPlayed(library) ?: library.firstOrNull()
            } else {
                findMatches(query).firstOrNull()
            }
        }
        val uri = item.localConfiguration?.uri
            ?: item.requestMetadata.mediaUri
            ?: item.mediaId.takeIf { it.startsWith("content://") || it.startsWith("file://") }
                ?.let(Uri::parse)
            ?: return null
        return item.buildUpon()
            .setMediaId(uri.toString())
            .setUri(uri)
            .setMediaMetadata(
                item.mediaMetadata.buildUpon()
                    .setTitle(item.mediaMetadata.title ?: displayName(uri))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }

    fun resolveQueue(
        requests: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
        expandSingleItem: Boolean = true,
    ): MediaItemsWithStartPosition {
        val resolved = requests.mapIndexedNotNull { index, item ->
            resolveItem(item)?.let { index to it }
        }
        if (expandSingleItem && requests.size == 1 && resolved.size == 1) {
            val selectedUri = resolved.first().second.localConfiguration?.uri
            if (selectedUri != null && isKnownAudioUri(selectedUri)) {
                val library = loadLibrary()
                val selectedIndex = library.indexOfFirst { it.localConfiguration?.uri == selectedUri }
                if (selectedIndex >= 0) {
                    return MediaItemsWithStartPosition(library, selectedIndex, startPositionMs)
                }
            }
        }
        val items = resolved.map { it.second }
        if (items.isEmpty() || startIndex == C.INDEX_UNSET) {
            return MediaItemsWithStartPosition(items, C.INDEX_UNSET, startPositionMs)
        }
        // Dropping an unresolved request must not shift playback to a different song,
        // or leave an index outside the resulting queue. If the selected request was
        // dropped, start the next playable item (or the last one) at its default position.
        val exactIndex = resolved.indexOfFirst { it.first == startIndex }
        val selectedIndex = if (exactIndex >= 0) exactIndex else
            resolved.indexOfFirst { it.first > startIndex }.takeIf { it >= 0 } ?: items.lastIndex
        return MediaItemsWithStartPosition(
            items,
            selectedIndex,
            if (exactIndex >= 0) startPositionMs else C.TIME_UNSET,
        )
    }
}
