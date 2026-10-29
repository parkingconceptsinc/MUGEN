package com.parkingconcepts.mortimer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowMediaExtractor
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StudioImportTest {
    private val base: Context get() = RuntimeEnvironment.getApplication()
    private val uri = Uri.parse("content://studio.import.test/video")
    private lateinit var provider: VideoProvider
    @Before fun setup() {
        provider = VideoProvider().apply { attachInfo(base, ProviderInfo().apply { authority = "studio.import.test" }) }
        ShadowContentResolver.registerProviderInternal("studio.import.test", provider)
        val data = DataSource.toDataSource(base, uri)
        mapOf(MediaMetadataRetriever.METADATA_KEY_DURATION to "4000", MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH to "320",
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT to "180", MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION to "0")
            .forEach { (key, value) -> ShadowMediaMetadataRetriever.addMetadata(data, key, value) }
    }
    @Test fun importsReadableContentUriThroughTheActualMetadataPath() = runBlocking {
        val item = LibraryItem(uri.toString(), "Video", "videos", "video")
        val clip = importStudioClips(base, listOf(item)).single()
        assertEquals(item.uri, clip.source.uri)
        assertEquals(4000L, clip.source.duration)
        assertEquals(320, clip.source.width)
        assertEquals(VideoEditSpec(0, 4000), clip.edit)
        assertEquals(1, provider.opens)
    }
    @Test fun deniedFolderFailsBeforeOpaqueNativeMetadataErrors() {
        val item = LibraryItem("content://studio.import.test/tree/root/document/root%2Fvideo.mp4", "Video", "videos", "video")
        val denied = object : ContextWrapper(base) {
            override fun checkUriPermission(uri: Uri, pid: Int, uid: Int, modeFlags: Int) = PackageManager.PERMISSION_DENIED
        }
        val failure = assertThrows(StudioAccessException::class.java) { runBlocking { importStudioClips(denied, listOf(item)) } }
        assertEquals(item, failure.item)
        assertTrue(failure.message!!.contains("Reconnect"))
        assertEquals(0, provider.opens)
    }
    @Test fun providerRevocationForAStandaloneDocumentIsAlsoRecoverable() {
        provider.denied = true
        val item = LibraryItem(uri.toString(), "Video", "videos", "video")
        assertEquals(item, assertThrows(StudioAccessException::class.java) { checkStudioSourceAccess(base, item) }.item)
        provider.denied = false
        runBlocking { assertEquals(1, importStudioClips(base, listOf(item)).size) }
    }
    @Test fun folderRecoveryGroupsSourcesWithoutChangingTheirUris() {
        fun source(tree: String, file: String) = Uri.parse("content://studio.import.test/tree/$tree/document/$tree%2F$file")
        val denied = object : ContextWrapper(base) {
            override fun checkUriPermission(uri: Uri, pid: Int, uid: Int, modeFlags: Int) =
                if (DocumentsContract.getTreeDocumentId(uri) == "books") PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
        val videos = source("videos", "a.mp4")
        val missing = missingLibraryFolders(denied, listOf(videos, source("videos", "b.mp4"), source("books", "a.epub"), source("comics", "a.cbz")))
        assertEquals(listOf("videos", "comics"), missing.map { it.name })
        assertEquals(videos, missing.first().sample)
        assertNull(libraryFolderFor(Uri.parse("file:///video.mp4")))
        assertNull(libraryFolderFor(uri))
        assertThrows(IllegalArgumentException::class.java) {
            reconnectLibraryFolder(denied, missing.first(), Uri.parse("content://studio.import.test/tree/other"))
        }
    }
    @Test fun fallsBackToVideoTrackMetadataWhenRetrieverRejectsTheContainer() {
        val data = DataSource.toDataSource(base, uri)
        ShadowMediaMetadataRetriever.addException(data, IllegalArgumentException("setDataSource failed"))
        val format = MediaFormat.createVideoFormat("video/avc", 1920, 1080).apply {
            setLong(MediaFormat.KEY_DURATION, 6_000_000); setInteger(MediaFormat.KEY_FRAME_RATE, 24); setInteger(MediaFormat.KEY_ROTATION, 90)
        }
        ShadowMediaExtractor.addTrack(data, format, byteArrayOf(1))
        val source = readStudioSource(base, uri, "Fallback")
        assertEquals(6000L, source.duration)
        assertEquals(1080, source.width); assertEquals(1920, source.height)
        assertEquals(24.0, source.fps, .001)
    }
    @Test fun cancellationAndPartialBatchFailureNeverBecomeSuccessfulImports() {
        val item = LibraryItem(uri.toString(), "Video", "videos", "video")
        provider.cancel = true
        assertThrows(CancellationException::class.java) { runBlocking { importStudioClips(base, listOf(item)) } }
        provider.cancel = false
        assertThrows(IllegalArgumentException::class.java) { runBlocking { importStudioClips(base, emptyList()) } }
    }
    class VideoProvider : ContentProvider() {
        var denied = false
        var cancel = false
        var opens = 0
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opens++
            if (cancel) throw CancellationException()
            if (denied) throw SecurityException("Permission revoked")
            val file = File(context!!.cacheDir, "studio-provider-fixture").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri) = "video/mp4"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
