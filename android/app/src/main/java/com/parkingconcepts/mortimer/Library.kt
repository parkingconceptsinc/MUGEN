package com.parkingconcepts.mortimer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.AtomicFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

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
            finished -> "Leído"
            openedAt == 0L || progress <= 0f -> if (pages > 0 && (format == "cbz" || format == "cbr" || format == "pdf")) "$pages páginas" else format.uppercase()
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
    private fun backup(context: Context) = File(context.filesDir, "library.backup.json")

    private fun exists(file: File) = file.exists() || File(file.path + ".bak").exists()

    private fun decode(bytes: ByteArray): List<LibraryItem> {
        val arr = JSONArray(bytes.toString(Charsets.UTF_8))
        return (0 until arr.length()).map { i ->
            LibraryItem.fromJson(arr.getJSONObject(i)).also {
                require(it.uri.isNotBlank()) { "Library entry has no file URI." }
                require(it.progress.isFinite()) { "Library progress is invalid." }
            }
        }.distinctBy { it.uri }
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (failure: Throwable) {
            atomic.failWrite(output)
            throw failure
        }
    }

    @Synchronized
    fun load(context: Context): List<LibraryItem> {
        val f = file(context)
        val recovery = backup(context)
        if (!exists(f) && !exists(recovery)) return migrate(context)
        try {
            return decode(AtomicFile(f).readFully())
        } catch (failure: Exception) {
            val bytes = try {
                AtomicFile(recovery).readFully().also { decode(it) }
            } catch (backupFailure: Exception) {
                throw IOException("Could not read the library or its backup. Your files were kept; no changes were saved.", failure)
            }
            // Keep the damaged original for recovery before restoring the last good copy.
            runCatching {
                if (f.isFile) f.copyTo(File(context.filesDir, "library.damaged.json"), overwrite = true)
                writeAtomic(f, bytes)
            }
            return decode(bytes)
        }
    }

    private fun save(context: Context, items: List<LibraryItem>) {
        val arr = JSONArray()
        items.distinctBy { it.uri }.forEach { arr.put(it.toJson()) }
        val bytes = arr.toString().toByteArray(Charsets.UTF_8)
        decode(bytes) // Validate the complete snapshot before touching either file.
        writeAtomic(file(context), bytes)
        // The primary has already committed. A full disk must not turn a successful
        // save into an error just because refreshing the additional backup failed.
        runCatching { writeAtomic(backup(context), bytes) }
    }

    @Synchronized
    fun update(context: Context, transform: (List<LibraryItem>) -> List<LibraryItem>): List<LibraryItem> {
        val next = transform(load(context)).distinctBy { it.uri }
        save(context, next)
        return next
    }

    fun find(context: Context, uri: String): LibraryItem? =
        runCatching { load(context).firstOrNull { it.uri == uri } }.getOrNull()

    fun add(context: Context, items: List<LibraryItem>): List<LibraryItem> = update(context) { current ->
        val known = current.mapTo(HashSet()) { it.uri }
        current + items.filter { known.add(it.uri) }
    }

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
                val parts = row.split('\t')
                if (parts.size < 2 || parts[0].isBlank()) null else LibraryItem(
                    uri = parts[0], title = parts[1], kind = kind,
                    format = formatOf(parts[1], kind), addedAt = now,
                    artist = parts.getOrNull(2).orEmpty(), album = parts.getOrNull(3).orEmpty(),
                )
            }
        }
        save(context, items)
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
suspend fun itemsFromUris(context: Context, uris: List<Uri>, kind: String): List<LibraryItem> = uris.distinct().map { uri ->
    currentCoroutineContext().ensureActive()
    persistReadPermission(context, uri)
    val name = displayName(context, uri, "Archivo")
    LibraryItem(uri = uri.toString(), title = name, kind = kind, format = formatOf(name, kind), size = fileSize(context, uri))
}

/**
 * Walks a folder picked with OpenDocumentTree (up to a few levels deep) and returns
 * the files that belong on [kind]'s shelf. Sub-folder names become the series/album.
 */
suspend fun itemsFromTree(context: Context, tree: Uri, kind: String): List<LibraryItem> {
    runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    val accepted = when (kind) {
        "audio" -> AUDIO_EXTENSIONS
        "videos" -> VIDEO_EXTENSIONS
        "comics" -> setOf("cbz", "cbr", "cb7", "7z", "zip", "rar", "pdf")
        else -> READABLE_EXTENSIONS
    }
    val out = ArrayList<LibraryItem>()
    val visited = HashSet<String>()
    val jobContext = currentCoroutineContext()
    val rootId = DocumentsContract.getTreeDocumentId(tree)
    fun walk(docId: String, folderName: String, depth: Int) {
        jobContext.ensureActive()
        if (!visited.add(docId)) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val dirs = ArrayList<Pair<String, String>>()
        val cursor = context.contentResolver.query(children, cols, null, null, null)
            ?: throw IOException("Could not read folder $folderName. Check its access permission.")
        cursor.use { c ->
                while (c.moveToNext()) {
                    jobContext.ensureActive()
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
        check(depth < 64 || dirs.isEmpty()) { "The folder nesting is too deep. Import a smaller subfolder." }
        dirs.forEach { (id, name) -> walk(id, "$folderName/$name", depth + 1) }
    }
    val rootName = rootId.substringAfterLast(':').substringAfterLast('/').ifBlank { "Carpeta" }
    walk(rootId, rootName, 0)
    return out
}

/** Natural ordering so "page 2" comes before "page 10". */
fun naturalKey(path: String): String =
    Regex("""\d+""").replace(path.lowercase()) { m -> m.value.padStart(12, '0') }
