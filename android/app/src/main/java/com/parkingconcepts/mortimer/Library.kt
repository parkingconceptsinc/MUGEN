package com.parkingconcepts.mortimer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One file in the library. The file itself stays where it is (phone, SD or USB);
 * only its content URI, reading progress and a few details are stored.
 */
data class LibraryItem(
    val uri: String,
    val title: String,
    /** "audio", "videos", "books" or "comics". */
    val kind: String,
    /** "pdf", "epub", "cbz", "cbr", "txt", "audio", "video" or "other". */
    val format: String,
    val folder: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val size: Long = 0,
    val artist: String = "",
    val album: String = "",
    /** Reader-specific position: a page number for comics/PDF, "chapter:fraction" for EPUB and text. */
    val position: String = "",
    /** 0..1 */
    val progress: Float = 0f,
    val pages: Int = 0,
    val openedAt: Long = 0,
) {
    val androidUri: Uri get() = Uri.parse(uri)
    val finished: Boolean get() = progress >= 0.995f
    val started: Boolean get() = openedAt > 0 && progress > 0f && !finished

    val statusLabel: String
        get() = when {
            finished -> "Finished"
            openedAt == 0L || progress <= 0f -> if (pages > 0 && (format == "cbz" || format == "cbr" || format == "pdf")) "$pages pages" else format.uppercase()
            format == "epub" || format == "txt" -> "${(progress * 100).toInt().coerceAtLeast(1)}% leído"
            else -> "Página ${position.toIntOrNull()?.plus(1) ?: 1} de $pages"
        }

    fun toJson(): JSONObject = JSONObject()
        .put("uri", uri).put("title", title).put("kind", kind).put("format", format)
        .put("folder", folder).put("addedAt", addedAt).put("size", size)
        .put("artist", artist).put("album", album)
        .put("position", position).put("progress", progress.toDouble()).put("pages", pages).put("openedAt", openedAt)

    companion object {
        fun fromJson(o: JSONObject) = LibraryItem(
            uri = o.getString("uri"),
            title = o.optString("title", "Archivo"),
            kind = o.optString("kind", "books"),
            format = o.optString("format", "other"),
            folder = o.optString("folder", ""),
            addedAt = o.optLong("addedAt", 0),
            size = o.optLong("size", 0),
            artist = o.optString("artist", ""),
            album = o.optString("album", ""),
            position = o.optString("position", ""),
            progress = o.optDouble("progress", 0.0).toFloat(),
            pages = o.optInt("pages", 0),
            openedAt = o.optLong("openedAt", 0),
        )
    }
}

val READABLE_EXTENSIONS = setOf("pdf", "epub", "cbz", "cbr", "cb7", "7z", "zip", "rar", "txt", "md", "markdown", "log", "nfo", "csv")
val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "amr", "mka", "aiff", "aif", "alac", "3gp", "mid", "midi")
val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "3g2", "ts", "m2ts", "mts", "mpg", "mpeg", "wmv", "flv", "ogv")

fun extensionOf(name: String): String = name.substringBefore("?").substringAfterLast('.', "").lowercase()

fun formatOf(name: String, kind: String): String = when (kind) {
    "audio" -> "audio"
    "videos" -> "video"
    else -> when (extensionOf(name)) {
        "pdf" -> "pdf"
        "epub" -> "epub"
        "cbz", "zip" -> "cbz"
        "cbr", "rar", "cb7", "7z" -> "cbr"
        "txt", "md", "markdown", "log", "nfo", "csv" -> "txt"
        else -> "other"
    }
}

object LibraryStore {
    private const val FILE = "library.json"

    private fun file(context: Context) = File(context.filesDir, FILE)

    @Synchronized
    fun load(context: Context): List<LibraryItem> {
        val f = file(context)
        if (!f.exists()) return migrate(context)
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i -> runCatching { LibraryItem.fromJson(arr.getJSONObject(i)) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(context: Context, items: List<LibraryItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        val f = file(context)
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(f)) { f.writeText(arr.toString()); tmp.delete() }
    }

    @Synchronized
    fun update(context: Context, transform: (List<LibraryItem>) -> List<LibraryItem>): List<LibraryItem> {
        val next = transform(load(context))
        save(context, next)
        return next
    }

    fun find(context: Context, uri: String): LibraryItem? = load(context).firstOrNull { it.uri == uri }

    fun saveProgress(context: Context, uri: String, position: String, progress: Float, pages: Int) {
        update(context) { list ->
            list.map {
                if (it.uri == uri) it.copy(position = position, progress = progress.coerceIn(0f, 1f), pages = pages, openedAt = System.currentTimeMillis()) else it
            }
        }
    }

    /** Moves the old SharedPreferences library (uri\ttitle string sets) into library.json. */
    private fun migrate(context: Context): List<LibraryItem> {
        val prefs = context.getSharedPreferences("mortimer_library", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val items = listOf("audio", "videos", "books", "comics").flatMap { kind ->
            prefs.getStringSet(kind, emptySet()).orEmpty().mapNotNull { row ->
                val parts = row.split("\t", limit = 2)
                if (parts.size != 2) null else LibraryItem(uri = parts[0], title = parts[1], kind = kind, format = formatOf(parts[1], kind), addedAt = now)
            }
        }
        runCatching { save(context, items) }
        return items
    }
}

fun displayName(context: Context, uri: Uri, fallback: String): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
    }
}.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

fun fileSize(context: Context, uri: Uri): Long = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
    }
}.getOrNull() ?: 0L

fun persistReadPermission(context: Context, uri: Uri) {
    runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
}

/** Builds library items for files picked one by one. */
fun itemsFromUris(context: Context, uris: List<Uri>, kind: String): List<LibraryItem> = uris.map { uri ->
    persistReadPermission(context, uri)
    val name = displayName(context, uri, "Archivo")
    LibraryItem(uri = uri.toString(), title = name, kind = kind, format = formatOf(name, kind), size = fileSize(context, uri))
}

/**
 * Walks a folder picked with OpenDocumentTree (up to a few levels deep) and returns
 * the files that belong on [kind]'s shelf. Sub-folder names become the series/album.
 */
fun itemsFromTree(context: Context, tree: Uri, kind: String): List<LibraryItem> {
    runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    val accepted = when (kind) {
        "audio" -> AUDIO_EXTENSIONS
        "videos" -> VIDEO_EXTENSIONS
        "comics" -> setOf("cbz", "cbr", "cb7", "7z", "zip", "rar", "pdf")
        else -> READABLE_EXTENSIONS
    }
    val out = ArrayList<LibraryItem>()
    val rootId = DocumentsContract.getTreeDocumentId(tree)
    fun walk(docId: String, folderName: String, depth: Int) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val dirs = ArrayList<Pair<String, String>>()
        runCatching {
            context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        dirs.add(id to name)
                    } else if (extensionOf(name) in accepted) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        out.add(LibraryItem(uri = uri.toString(), title = name, kind = kind, format = formatOf(name, kind), folder = folderName, size = if (c.isNull(3)) 0 else c.getLong(3)))
                    }
                }
            }
        }
        if (depth < 5) dirs.forEach { (id, name) -> walk(id, name, depth + 1) }
    }
    val rootName = rootId.substringAfterLast(':').substringAfterLast('/').ifBlank { "Folder" }
    walk(rootId, rootName, 0)
    return out
}

/** Natural ordering so "page 2" comes before "page 10". */
fun naturalKey(path: String): String =
    Regex("""\d+""").replace(path.lowercase()) { m -> m.value.padStart(12, '0') }
