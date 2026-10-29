package com.parkingconcepts.mortimer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PlaybackRequestResolverTest {
    @Test fun nativeSingleSongSelectionDoesNotAddUnselectedDeviceMusic() {
        val result = resolver().resolveQueue(listOf(second), 0, 5000L, expandSingleItem = false)
        assertEquals(listOf(second.mediaId), result.mediaItems.map { it.mediaId })
        assertEquals(0, result.startIndex)
        assertEquals(5000L, result.startPositionMs)
    }
    private val first = song("1", "First song")
    private val second = song("2", "Second song")
    private val third = song("3", "Third song")
    private val library = listOf(first, second, third)

    private fun resolver(last: MediaItem? = second, songs: List<MediaItem> = library) =
        PlaybackRequestResolver(
            loadLibrary = { songs },
            findMatches = { query -> songs.filter { it.mediaMetadata.title.toString().contains(query, ignoreCase = true) } },
            lastPlayed = { last },
            isKnownAudioUri = { uri -> songs.any { it.localConfiguration?.uri == uri } },
            displayName = { "File ${it.lastPathSegment}" },
        )

    @Test fun voiceSearchStartsMatchingSongInFullQueue() {
        // Legacy playFromSearch reaches onSetMediaItems with no URI or media ID.
        val result = resolver().resolveQueue(listOf(search("Second")), 0, C.TIME_UNSET)
        assertEquals(library, result.mediaItems)
        assertEquals(1, result.startIndex)
        assertEquals(C.TIME_UNSET, result.startPositionMs)
    }

    @Test fun blankVoiceCommandResumesLastSong() {
        val result = resolver().resolveQueue(listOf(search("  ")), 0, C.TIME_UNSET)
        assertEquals(library, result.mediaItems)
        assertEquals(1, result.startIndex)
    }

    @Test fun blankVoiceCommandWithoutHistoryStartsFirstSong() {
        val result = resolver(last = null).resolveQueue(listOf(search("")), 0, C.TIME_UNSET)
        assertEquals(library, result.mediaItems)
        assertEquals(0, result.startIndex)
    }

    @Test fun unmatchedVoiceCommandDoesNotPlayAnUnrelatedSong() {
        val result = resolver().resolveQueue(listOf(search("No match")), 0, C.TIME_UNSET)
        assertTrue(result.mediaItems.isEmpty())
        assertEquals(C.INDEX_UNSET, result.startIndex)
    }

    @Test fun emptyLibraryLeavesVoicePlaybackEmpty() {
        val result = resolver(last = null, songs = emptyList())
            .resolveQueue(listOf(search("")), 0, C.TIME_UNSET)
        assertTrue(result.mediaItems.isEmpty())
        assertEquals(C.INDEX_UNSET, result.startIndex)
    }

    @Test fun browsedMediaIdExpandsQueueAndPreservesSeekPosition() {
        val request = MediaItem.Builder().setMediaId(third.mediaId).build()
        val result = resolver().resolveQueue(listOf(request), 0, 12_000L)
        assertEquals(library, result.mediaItems)
        assertEquals(2, result.startIndex)
        assertEquals(12_000L, result.startPositionMs)
    }

    @Test fun mediaUriRequestsPreserveMetadataAndBecomePlayable() {
        val request = MediaItem.Builder()
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse("content://videos/7")).build())
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Video").setIsBrowsable(true).build())
            .build()
        val result = resolver().resolveItem(request)!!
        assertEquals("content://videos/7", result.mediaId)
        assertEquals(Uri.parse("content://videos/7"), result.localConfiguration?.uri)
        assertEquals("Video", result.mediaMetadata.title)
        assertEquals(true, result.mediaMetadata.isPlayable)
        assertEquals(false, result.mediaMetadata.isBrowsable)
    }

    @Test fun localUriGetsMissingTitleWithoutExpandingUnknownItem() {
        val request = MediaItem.fromUri("file:///storage/video.mp4")
        val result = resolver().resolveQueue(listOf(request), 0, 5_000L)
        assertEquals(1, result.mediaItems.size)
        assertEquals("File video.mp4", result.mediaItems.first().mediaMetadata.title)
        assertEquals(0, result.startIndex)
        assertEquals(5_000L, result.startPositionMs)
    }

    @Test fun removingUnresolvedEarlierRequestKeepsSelectedSongAndPosition() {
        val result = resolver().resolveQueue(listOf(unknown(), first, third), 2, 25_000L)
        assertEquals(listOf(first.mediaId, third.mediaId), result.mediaItems.map { it.mediaId })
        assertEquals(1, result.startIndex)
        assertEquals(third.mediaId, result.mediaItems[result.startIndex].mediaId)
        assertEquals(25_000L, result.startPositionMs)
    }

    @Test fun removingSelectedRequestUsesNextSongAtDefaultPosition() {
        val result = resolver().resolveQueue(listOf(first, unknown(), third), 1, 25_000L)
        assertEquals(1, result.startIndex)
        assertEquals(third.mediaId, result.mediaItems[result.startIndex].mediaId)
        assertEquals(C.TIME_UNSET, result.startPositionMs)
    }

    @Test fun removingSelectedLastRequestFallsBackToLastPlayableSong() {
        val result = resolver().resolveQueue(listOf(first, third, unknown()), 2, 25_000L)
        assertEquals(1, result.startIndex)
        assertEquals(third.mediaId, result.mediaItems[result.startIndex].mediaId)
        assertEquals(C.TIME_UNSET, result.startPositionMs)
    }

    @Test fun unsetStartIndexRetainsMedia3ResetSemantics() {
        val result = resolver().resolveQueue(listOf(first, third), C.INDEX_UNSET, C.TIME_UNSET)
        assertEquals(C.INDEX_UNSET, result.startIndex)
        assertEquals(C.TIME_UNSET, result.startPositionMs)
        assertEquals(2, result.mediaItems.size)
        assertNull(resolver().resolveItem(unknown()))
    }

    private fun unknown() = MediaItem.Builder().setMediaId("unknown-id").build()

    private fun search(query: String) = MediaItem.Builder()
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).build())
        .build()

    private fun song(id: String, title: String) = MediaItem.Builder()
        .setMediaId("content://media/external/audio/media/$id")
        .setUri("content://media/external/audio/media/$id")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setIsBrowsable(false).setIsPlayable(true).build())
        .build()
}
