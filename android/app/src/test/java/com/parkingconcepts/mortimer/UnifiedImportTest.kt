package com.parkingconcepts.mortimer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class UnifiedImportTest {
    private lateinit var context: Context
    private val authority = "mugen.mixed.documents"
    private val names = listOf("Track.FLAC", "Movie.MKV", "Book.EPUB", "Manual.PDF", "Notes.md", "Manga.CBZ", "Photo.JPEG", "download", "app.exe")
    private val kinds = listOf("audio", "videos", "books", "books", "books", "comics", "photos", "books")
    private fun uri(id: Int) = Uri.parse("content://$authority/document/$id")

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
                val columns = projection!!
                return MatrixCursor(columns).apply {
                    val ids = if (uri.lastPathSegment == "children") names.indices.toList() else listOf(DocumentsContract.getDocumentId(uri).toInt())
                    ids.forEach { id -> addRow(columns.map { column -> when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id.toString()
                        OpenableColumns.DISPLAY_NAME -> names[id]
                        OpenableColumns.SIZE -> 100L
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> if (names[id] == "download") "application/pdf" else "application/octet-stream"
                        else -> null
                    } }.toTypedArray()) }
                }
            }
            override fun getType(uri: Uri) = if (names[DocumentsContract.getDocumentId(uri).toInt()] == "download") "application/pdf" else "application/octet-stream"
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(context, ProviderInfo().apply { authority = this@UnifiedImportTest.authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        context.filesDir.listFiles()?.filter { it.name.startsWith("library.") }?.forEach { it.delete() }
        context.getSharedPreferences("mortimer_library", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun mixedFilesAndFolderPicksRouteIdenticallyAndReimportKeepsReadingProgress() = runBlocking {
        val picked = itemsFromUris(context, names.indices.map(::uri) + uri(0))
        val folder = itemsFromTree(context, Uri.parse("content://$authority/tree/root"))
        assertEquals(kinds, picked.map { it.kind })
        assertEquals(kinds, folder.map { it.kind })
        assertEquals("pdf", picked.last().format)
        val book = picked.first { it.format == "epub" }.copy(progress = .5f, position = "2:0.5", openedAt = 123)
        LibraryStore.add(context, picked.map { if (it.uri == book.uri) book else it })
        val saved = LibraryStore.add(context, folder)
        assertEquals(8, saved.size)
        assertEquals(.5f, saved.first { it.format == "epub" }.progress)
        assertEquals(listOf("Movie.MKV"), itemsFromUris(context, names.indices.map(::uri), "videos").map { it.title })
    }

    @Test fun importedPhotosRemainAvailableWithoutGalleryAccessAndIdsSurviveReload() = runBlocking {
        val picked = itemsFromUris(context, listOf(uri(6)))
        LibraryStore.add(context, picked)
        val photo = mergeImportedPhotos(emptyList(), LibraryStore.load(context)).single()
        assertEquals(uri(6), photo.uri)
        assertTrue(photo.id < 0)
        assertEquals(photo.id, mergeImportedPhotos(emptyList(), LibraryStore.load(context)).single().id)
        assertEquals(1, mergeImportedPhotos(listOf(photo), picked + picked).size)
        assertEquals("photos" to "photo", scanKind(java.io.File("Photo.JPEG")))
        assertEquals("audio" to "audio", scanKind(java.io.File("Track.FLAC")))
    }
}
