package com.parkingconcepts.mortimer

import android.app.Activity
import android.net.Uri
import android.view.LayoutInflater
import android.view.TextureView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MediaToolsTest {
    @Test fun videoSurfaceUsesTextureAndNativeControlsInsteadOfInvisibleOverlay() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = LayoutInflater.from(activity).inflate(R.layout.video_surface, null) as PlayerView
        assertTrue(view.videoSurfaceView is TextureView)
        assertTrue(view.useController)
        view.controllerShowTimeoutMs = 0
        view.controllerHideOnTouch = false
        assertEquals(0, view.controllerShowTimeoutMs)
        assertFalse(view.controllerHideOnTouch)
    }
    @Test fun editorBuildsClippedMutedOutputWithRotationAndResize() {
        val item = editedVideoItem(Uri.parse("content://video/1"), VideoEditSpec(1000, 5000, 90, true, 720), 10_000)
        assertTrue(item.removeAudio)
        assertEquals(1000L, item.mediaItem.clippingConfiguration.startPositionMs)
        assertEquals(5000L, item.mediaItem.clippingConfiguration.endPositionMs)
        assertEquals(2, item.effects.videoEffects.size)
    }
    @Test fun editorRejectsEmptyOrOutOfRangeClips() {
        listOf(VideoEditSpec(0, 0), VideoEditSpec(900, 1100), VideoEditSpec(-1, 600), VideoEditSpec(1000, 2000)).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.validate(1000) }
        }
        assertThrows(IllegalArgumentException::class.java) { VideoEditSpec(0, 1000, 45).validate(1000) }
        assertThrows(IllegalArgumentException::class.java) { VideoEditSpec(0, 1000, height = 99).validate(1000) }
    }
    @Test fun bookmarksRemainSeparateForEachDocumentAndDeduplicatePositions() {
        val context = RuntimeEnvironment.getApplication()
        val uri = "content://reader/test-bookmarks"
        ReaderBookmarks.update(context, uri) { emptyList() }
        ReaderBookmarks.update(context, uri) { it + ReaderBookmark("2:0.5", "Chapter 3") + ReaderBookmark("2:0.5", "Duplicate") }
        assertEquals(listOf(ReaderBookmark("2:0.5", "Chapter 3")), ReaderBookmarks.load(context, uri))
        assertTrue(ReaderBookmarks.load(context, "$uri-other").isEmpty())
    }
    @Test fun bookmarkRemovalDoesNotDeleteOtherPositions() {
        val context = RuntimeEnvironment.getApplication()
        val uri = "content://reader/remove"
        ReaderBookmarks.update(context, uri) { listOf(ReaderBookmark("1", "Page 2"), ReaderBookmark("3", "Page 4")) }
        ReaderBookmarks.update(context, uri) { list -> list.filter { it.position != "1" } }
        assertEquals("3", ReaderBookmarks.load(context, uri).single().position)
    }
    @Test fun finalCheckpointCommitsWithoutWaitingForUiDebounce() {
        val context = RuntimeEnvironment.getApplication()
        val uri = "content://reader/final-position"
        LibraryStore.add(context, listOf(LibraryItem(uri, "Book", "books", "epub")))
        ReaderCheckpoints.save(context, ReadingCheckpoint(uri, "2:0.2", 0.2f, 5))
        ReaderCheckpoints.save(context, ReadingCheckpoint(uri, "3:0.8", 0.8f, 5))
        ReaderCheckpoints.awaitPendingWrites()
        assertEquals("3:0.8", LibraryStore.find(context, uri)?.position)
    }
    @Test fun mediaPositionsResetNearEndAndRemainIndependent() {
        val context = RuntimeEnvironment.getApplication()
        PlaybackPositions.save(context, "content://media/one", 5000, 10_000)
        PlaybackPositions.save(context, "content://media/two", 9900, 10_000)
        assertEquals(5000L, PlaybackPositions.get(context, "content://media/one"))
        assertEquals(0L, PlaybackPositions.get(context, "content://media/two"))
    }
    @Test fun repeatLoopRequiresUsefulRangeAndSeeksOnlyAtBoundary() {
        assertThrows(IllegalArgumentException::class.java) { PlaybackLoop(1000, 1200) }
        val loop = PlaybackLoop(1000, 2000)
        assertFalse(loop.shouldRepeat(1999))
        assertTrue(loop.shouldRepeat(2000))
    }
}
