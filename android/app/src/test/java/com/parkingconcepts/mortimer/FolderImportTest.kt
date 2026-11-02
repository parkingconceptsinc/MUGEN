package com.parkingconcepts.mortimer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
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
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class FolderImportTest {
    private lateinit var context: Context
    private lateinit var provider: FolderProvider
    private val tree = Uri.parse("content://mugen.test.documents/tree/root")

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        provider = FolderProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "mugen.test.documents" })
        ShadowContentResolver.registerProviderInternal("mugen.test.documents", provider)
    }

    @Test fun scansBeyondOldFiveLevelLimitAndKeepsFolderPaths() = runBlocking {
        for (depth in 0 until 8) {
            provider.rows[if (depth == 0) "root" else "dir$depth"] =
                listOf(arrayOf("dir${depth + 1}", "Folder${depth + 1}", DocumentsContract.Document.MIME_TYPE_DIR, 0L))
        }
        provider.rows["dir8"] = listOf(arrayOf("track", "Track.mp3", "audio/mpeg", 100L))
        val result = itemsFromTree(context, tree, "audio").single()
        assertEquals("Track.mp3", result.title)
        assertTrue(result.folder.endsWith("Folder7/Folder8"))
        assertEquals(100L, result.size)
    }

    @Test fun repeatedDocumentIdsDoNotCauseInfiniteRecursionOrDuplicateFiles() = runBlocking {
        provider.rows["root"] = listOf(
            arrayOf("root", "Cycle", DocumentsContract.Document.MIME_TYPE_DIR, 0L),
            arrayOf("song", "Song.mp3", "audio/mpeg", 10L),
        )
        val result = itemsFromTree(context, tree, "audio")
        assertEquals(1, result.size)
        assertEquals(1, provider.queries)
    }

    @Test fun unreadableSubfolderFailsInsteadOfReportingPartialImportAsSuccess() {
        provider.rows["root"] = listOf(
            arrayOf("song", "Song.mp3", "audio/mpeg", 10L),
            arrayOf("missing", "Missing", DocumentsContract.Document.MIME_TYPE_DIR, 0L),
        )
        assertThrows(IOException::class.java) { runBlocking { itemsFromTree(context, tree, "audio") } }
    }

    @Test fun cancellationIsNotConvertedIntoAnEmptySuccessfulScan() {
        provider.beforeQuery = { throw CancellationException("cancelled") }
        assertThrows(CancellationException::class.java) { runBlocking { itemsFromTree(context, tree, "audio") } }
    }

    @Test fun scanFiltersUnsupportedFiles() = runBlocking {
        provider.rows["root"] = listOf(
            arrayOf("music", "Song.MP3", "audio/mpeg", 10L),
            arrayOf("book", "Book.epub", "application/epub+zip", 10L),
        )
        assertEquals(listOf("Song.MP3"), itemsFromTree(context, tree, "audio").map { it.title })
    }

    @Test fun largeFolderReportsProgressAndDoesNotStopAtOneHundredFiles() = runBlocking {
        provider.rows["root"] = (1..305).map { arrayOf<Any>("song$it", "Song$it.mp3", "audio/mpeg", 10L) }
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = itemsFromTree(context, tree, "audio", onProgress = { visited, found -> progress.add(visited to found) })
        assertEquals(305, result.size)
        assertEquals(305 to 305, progress.last())
        assertTrue(progress.any { it.first == 100 })
        assertTrue(progress.any { it.first == 200 })
    }

    @Test fun duplicateFileIdsAcrossFoldersAreOnlyReturnedOnce() = runBlocking {
        val song = arrayOf<Any>("song", "Song.mp3", "audio/mpeg", 10L)
        provider.rows["root"] = listOf(song, arrayOf("child", "Child", DocumentsContract.Document.MIME_TYPE_DIR, 0L))
        provider.rows["child"] = listOf(song, song)
        assertEquals(1, itemsFromTree(context, tree, "audio").size)
    }

    @Test fun limitsFailExplicitlyInsteadOfReturningTruncatedSuccessfulLibrary() {
        provider.rows["root"] = (1..3).map { arrayOf<Any>("song$it", "Song$it.mp3", "audio/mpeg", 10L) }
        assertThrows(IllegalStateException::class.java) { runBlocking { itemsFromTree(context, tree, "audio", maxResults = 2) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { itemsFromTree(context, tree, "audio", maxEntries = 2) } }
    }

    @Test fun cancellationFromProgressDoesNotReturnPartialFiles() {
        provider.rows["root"] = (1..305).map { arrayOf<Any>("song$it", "Song$it.mp3", "audio/mpeg", 10L) }
        assertThrows(CancellationException::class.java) { runBlocking {
            itemsFromTree(context, tree, "audio", onProgress = { visited, _ ->
                if (visited == 100) throw CancellationException("cancelled while scanning")
            })
        } }
    }

    private class FolderProvider : ContentProvider() {
        val rows = mutableMapOf<String, List<Array<Any>>>()
        var queries = 0
        var beforeQuery: () -> Unit = {}
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            queries++
            beforeQuery()
            val items = rows[DocumentsContract.getDocumentId(uri)] ?: return null
            return MatrixCursor(projection!!).apply { items.forEach { addRow(it) } }
        }
        override fun getType(uri: Uri) = DocumentsContract.Document.MIME_TYPE_DIR
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
