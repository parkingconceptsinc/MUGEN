package com.parkingconcepts.mortimer

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MediaStudioNextTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val a = SavedTrack("content://music/a", "A", "Artist", "Album")
    private val b = SavedTrack("content://music/b", "B")
    @Before fun reset() {
        context.filesDir.listFiles()?.filter { it.name.startsWith("music_") }?.forEach { it.delete() }
    }
    @Test fun correctedSrtRoundTripsOffsetDriftOverlapAndLiteralTags() {
        val timeline = SubtitleTimeline(listOf(SubtitleCue(1000, 3000, "<hello> & café\nSecond line"), SubtitleCue(2000, 4000, "Overlap")))
        val parsed = parseSubtitles(timeline.toSrt(SubtitleSync(-1500, 1.5)))
        assertEquals(listOf(SubtitleCue(0, 3000, "<hello> & café\nSecond line"), SubtitleCue(1500, 4500, "Overlap")), parsed.cues)
        assertEquals("<hello> & café\nSecond line\nOverlap", parsed.textAt(2000, SubtitleSync()))
    }
    @Test fun clipSubtitlesAreIntersectedRebasedAndRenumbered() {
        val source = SubtitleTimeline(listOf(SubtitleCue(0, 1000, "Outside"), SubtitleCue(1500, 2500, "A"), SubtitleCue(2800, 5000, "B")))
        val text = source.toSrt(SubtitleSync(500), 2500, 4000)
        assertTrue(text.startsWith("1\n00:00:00,000 --> 00:00:00,500"))
        assertEquals(listOf(SubtitleCue(0, 500, "A"), SubtitleCue(800, 1500, "B")), parseSubtitles(text).cues)
        assertEquals("", source.toSrt(SubtitleSync(-10_000)))
        assertThrows(IllegalArgumentException::class.java) { source.toSrt(SubtitleSync(), 1000, 1000) }
    }
    @Test fun largeCalibratedHoursRoundTripWithoutTimestampOverflow() {
        val source = SubtitleTimeline(listOf(SubtitleCue(3_596_400_000, 3_596_401_000, "Late")))
        assertEquals(7_192_800_000, parseSubtitles(source.toSrt(SubtitleSync(scale = 2.0))).cues.single().startMs)
    }
    @Test fun editorDraftSurvivesReopenAndRejectsReplacedSource() {
        val spec = VideoEditSpec(1234, 9876, 270, true, 720)
        VideoEditDrafts.save(context, a.uri, 10_000, spec)
        assertEquals(spec, VideoEditDrafts.load(context, a.uri, 10_000))
        assertNull(VideoEditDrafts.load(context, b.uri, 10_000))
        assertNull(VideoEditDrafts.load(context, a.uri, 9000))
        assertThrows(IllegalArgumentException::class.java) { VideoEditDrafts.save(context, a.uri, 10_000, spec.copy(endMs = 50)) }
        assertEquals(spec, VideoEditDrafts.load(context, a.uri, 10_000))
    }
    @Test fun searchFindsLiteralTextInEveryChapterAndKeepsBoundedSnippets() = runBlocking {
        val chapters = mapOf("a" to "prefix [a+b] middle [A+B]", "b" to "x".repeat(1000), "c" to "last [a+b] chapter")
        val result = searchBook(chapters.keys.toList(), "[a+b]", { chapters[it]!!.toByteArray() }, { it })
        assertEquals(listOf(0, 2), result.hits.map { it.chapter })
        assertEquals(listOf(2, 1), result.hits.map { it.count })
        assertEquals(3, result.searched)
        assertFalse(result.limited)
        assertTrue(result.hits.all { it.snippet.length <= 165 })
    }
    @Test fun searchReportsByteMatchAndUnreadableLimits() = runBlocking {
        val chapters = listOf("a", "b", "c")
        val limited = searchBook(chapters, "word", { "word word word".toByteArray() }, { it }, maxMatches = 2)
        assertTrue(limited.limited); assertEquals(2, limited.hits.single().count)
        val bytes = searchBook(chapters, "word", { "word".toByteArray() }, { it }, maxBytes = 5)
        assertEquals(1, bytes.searched); assertTrue(bytes.limited)
        val skipped = searchBook(chapters, "word", { if (it == "b") throw IOException() else "word".toByteArray() }, { it })
        assertEquals(1, skipped.unreadable); assertEquals(2, skipped.searched)
        assertTrue(searchBook(chapters, " ", { error("must not read") }, { it }).hits.isEmpty())
    }
    @Test fun searchNeverSwallowsCancellation() {
        assertThrows(CancellationException::class.java) {
            runBlocking { searchBook(listOf("a"), "word", { throw CancellationException() }, { it }) }
        }
    }
    @Test fun globalSearchExcludesHeadScriptsAndStylesButDecodesReadableEntities() {
        val text = bookSearchText("<html><head><title>Hidden</title><style>Hidden</style></head><body><p>Caf&eacute; &amp; tea</p><script>Hidden</script><style>Hidden</style><p>Second</p></body></html>")
        assertFalse(text.contains("Hidden"))
        assertTrue(text.contains("Café & tea"))
        assertTrue(text.contains("Second"))
        assertEquals("Visible", bookSearchText("Visible<script>" + "<script>".repeat(10000)))
    }
    @Test fun playlistEditsAndQueueRestoreKeepOrderDuplicatesAndSelectedIndex() {
        val first = MusicPlaylist(UUID.randomUUID().toString(), "Road", listOf(b, a, b))
        MusicPlaylists.update(context) { listOf(first) }
        assertEquals(first, MusicPlaylists.load(context).single())
        MusicPlaylists.update(context) { it.map { list -> list.copy(name = "Trip") } }
        assertEquals("Trip", MusicPlaylists.load(context).single().name)
        val queue = AudioQueue(listOf(b, a, b), 2)
        MusicPlaylists.saveQueue(context, queue)
        assertEquals(queue, MusicPlaylists.loadQueue(context))
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, queue.tracks[0].mediaItem().mediaMetadata.mediaType)
        MusicPlaylists.update(context) { emptyList() }
        assertTrue(MusicPlaylists.load(context).isEmpty())
        assertEquals(queue, MusicPlaylists.loadQueue(context))
    }
    @Test fun recoverySurvivesCorruptPrimaryAndRefusesToOverwriteTwoBadCopies() {
        val queue = AudioQueue(listOf(a))
        MusicPlaylists.saveQueue(context, queue)
        val primary = File(context.filesDir, "music_queue.json")
        primary.writeText("broken")
        assertEquals(queue, MusicPlaylists.loadQueue(context))
        File(context.filesDir, "music_queue.json.recovery").writeText("also broken")
        assertThrows(IOException::class.java) { MusicPlaylists.saveQueue(context, AudioQueue(listOf(b))) }
        assertEquals("broken", primary.readText())
    }
    @Test fun playlistsRejectInvalidOrOversizedUpdatesBeforeTouchingSavedData() {
        val list = MusicPlaylist(UUID.randomUUID().toString(), "Good", listOf(a))
        MusicPlaylists.update(context) { listOf(list) }
        assertThrows(IllegalArgumentException::class.java) { MusicPlaylists.update(context) { it + list } }
        assertThrows(IllegalArgumentException::class.java) { MusicPlaylists.update(context) { listOf(list.copy(name = " ")) } }
        assertThrows(IllegalArgumentException::class.java) { SavedTrack("https://example.test/audio", "Remote").validate() }
        assertThrows(IllegalArgumentException::class.java) { AudioQueue(listOf(a), 4).validate() }
        assertEquals(list, MusicPlaylists.load(context).single())
    }
    @Test fun musicSnapshotRejectsVideosAndMixedQueues() {
        fun player(items: List<MediaItem>, selected: Int = 0): Player = java.lang.reflect.Proxy.newProxyInstance(
            Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getMediaItemCount" -> items.size
                "getMediaItemAt" -> items[args!![0] as Int]
                "getCurrentMediaItemIndex" -> selected
                else -> error(method.name)
            }
        } as Player
        val video = MediaItem.Builder().setUri(Uri.parse("content://video/1")).setMediaMetadata(
            MediaMetadata.Builder().setMediaType(MediaMetadata.MEDIA_TYPE_MOVIE).build()).build()
        assertEquals(AudioQueue(listOf(a, b, a), 2), snapshotAudioQueue(player(listOf(a.mediaItem(), b.mediaItem(), a.mediaItem()), 2)))
        assertNull(snapshotAudioQueue(player(listOf(video))))
        assertNull(snapshotAudioQueue(player(listOf(a.mediaItem(), video))))
        assertNull(snapshotAudioQueue(player(emptyList())))
    }
}
