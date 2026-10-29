package com.parkingconcepts.mortimer

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

internal class StudioAccessException(val item: LibraryItem, cause: Throwable? = null) : IOException(
    "Access to this video has expired. Reconnect its folder or select the video again.", cause)

internal data class StudioImportRequest(val items: List<LibraryItem>, val fresh: Boolean, val failed: LibraryItem)

/** Probe access before native metadata APIs, which otherwise hide SecurityException behind setDataSource errors. */
internal fun checkStudioSourceAccess(context: Context, item: LibraryItem) {
    val uri = item.androidUri
    if (libraryFolderFor(uri) != null && !hasLibraryUriAccess(context, uri)) throw StudioAccessException(item)
    try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { } ?: throw FileNotFoundException("Could not open ${item.title}.")
    } catch (failure: SecurityException) { throw StudioAccessException(item, failure) }
}

/** All-or-nothing metadata import. The current project is untouched if any file cannot be read. */
internal suspend fun importStudioClips(context: Context, items: List<LibraryItem>): List<StudioClip> = withContext(Dispatchers.IO) {
    require(items.isNotEmpty() && items.size <= 64) { "Choose between 1 and 64 videos." }
    items.map { item ->
        currentCoroutineContext().ensureActive()
        checkStudioSourceAccess(context, item)
        val source = readStudioSource(context, item.androidUri, item.title)
        val draft = VideoEditDrafts.load(context, item.uri, source.duration)
        StudioClip(source = source, edit = (draft ?: VideoEditSpec(0, source.duration)).copy(height = 0, aspect = "source"))
    }
}
