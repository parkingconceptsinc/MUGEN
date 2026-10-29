package com.parkingconcepts.mortimer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import java.util.zip.ZipFile

val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

/** Pages of a comic or PDF, decoded on demand at the size the screen needs. */
interface PageSource : Closeable {
    val count: Int

    /** Decodes page [index] so that it is at least as large as [maxW]×[maxH] would need (never upscaled). */
    fun decode(index: Int, maxW: Int, maxH: Int): Bitmap?
}

private fun decodeBytes(bytes: ByteArray, maxW: Int, maxH: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
        inSampleSize = imageSampleSize(bounds.outWidth, bounds.outHeight, maxW, maxH)
    })
}

private fun decodeFile(file: File, maxW: Int, maxH: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return null
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
        inSampleSize = imageSampleSize(bounds.outWidth, bounds.outHeight, maxW, maxH)
    })
}

class ZipPageSource(private val file: File, private val deleteOnClose: Boolean) : PageSource {
    private val zip = ZipFile(file)
    private var closed = false
    private val pages: List<String> = try { zip.entries().asSequence()
        .filter { !it.isDirectory && extensionOf(it.name) in IMAGE_EXTENSIONS && !it.name.substringAfterLast('/').startsWith(".") && !it.name.startsWith("__MACOSX") }
        .map { it.name }
        .take(10_001)
        .sortedBy { naturalKey(it) }
        .toList()
        .also { check(it.size <= 10_000) { "El archivo contiene demasiadas páginas." } }
    } catch (failure: Throwable) { zip.close(); throw failure }

    override val count get() = pages.size

    @Synchronized
    override fun decode(index: Int, maxW: Int, maxH: Int): Bitmap? {
        if (closed) return null
        val entry = zip.getEntry(pages.getOrNull(index) ?: return null) ?: return null
        val bytes = zip.getInputStream(entry).use { it.readBytesLimited(MAX_IMAGE_BYTES) }
        return decodeBytes(bytes, maxW, maxH)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { zip.close() }
        if (deleteOnClose) file.delete()
    }
}

/** RAR pages are extracted once into a cache folder, because RAR archives are not random-access. */
class FolderPageSource(private val dir: File, private val files: List<File>) : PageSource {
    private var closed = false
    override val count get() = files.size
    @Synchronized
    override fun decode(index: Int, maxW: Int, maxH: Int): Bitmap? =
        if (closed) null else files.getOrNull(index)?.let { decodeFile(it, maxW, maxH) }
    @Synchronized
    override fun close() { if (!closed) { closed = true; dir.deleteRecursively() } }
}

class PdfPageSource(private val descriptor: ParcelFileDescriptor) : PageSource {
    private val renderer = PdfRenderer(descriptor)
    private var closed = false
    override val count = renderer.pageCount

    @Synchronized
    override fun decode(index: Int, maxW: Int, maxH: Int): Bitmap? {
        if (closed || index !in 0 until renderer.pageCount) return null
        val page = renderer.openPage(index)
        try {
            val scale = minOf(
                maxW.coerceAtLeast(1).toFloat() / page.width,
                maxH.coerceAtLeast(1).toFloat() / page.height,
                4f,
                kotlin.math.sqrt(MAX_BITMAP_PIXELS.toDouble() / (page.width.toDouble() * page.height)).toFloat(),
                8192f / maxOf(page.width, page.height),
            )
            val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        } finally {
            page.close()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }
}

/** What the file really is, whatever its extension says (many .cbr files are zips and vice versa). */
fun sniffArchive(head: ByteArray): String? = when {
    head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> "zip"
    head.size >= 4 && head[0] == 'R'.code.toByte() && head[1] == 'a'.code.toByte() && head[2] == 'r'.code.toByte() && head[3] == '!'.code.toByte() -> "rar"
    head.size >= 6 && head[0] == '7'.code.toByte() && head[1] == 'z'.code.toByte() && head[2] == 0xBC.toByte() && head[3] == 0xAF.toByte() -> "rar"
    head.size >= 4 && head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'D'.code.toByte() && head[3] == 'F'.code.toByte() -> "pdf"
    else -> null
}

fun readHead(context: Context, uri: Uri): ByteArray =
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buf = ByteArray(8)
        val n = input.read(buf)
        if (n <= 0) ByteArray(0) else buf.copyOf(n)
    } ?: ByteArray(0)

private fun copyToCache(context: Context, uri: Uri, checkCancelled: () -> Unit, onProgress: (Int) -> Unit): File {
    val target = File.createTempFile("comic_", ".zip", context.cacheDir)
    val total = fileSize(context, uri)
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                input.copyLimitedTo(output, MAX_CACHE_BYTES, checkCancelled) { copied ->
                    if (total > 0) onProgress((copied * 100 / total).coerceIn(0, 100).toInt())
                }
            }
        } ?: error("No se pudo leer el archivo. Vuelve a importarlo.")
        return target
    } catch (failure: Throwable) {
        target.delete()
        throw failure
    }
}

fun rarErrorMessage(error: Throwable): String =
    "No se pudo abrir el archivo comprimido: " + (error.message ?: error.javaClass.simpleName)

/** Opens a comic or PDF as pages. Runs on a background thread. */
suspend fun openPageSource(context: Context, uri: Uri, format: String, onProgress: (String) -> Unit): PageSource {
    val jobContext = currentCoroutineContext()
    val checkCancelled = { jobContext.ensureActive() }
    val kind = sniffArchive(readHead(context, uri)) ?: when (format) {
        "cbr" -> "rar"
        "pdf" -> "pdf"
        else -> "zip"
    }
    return when (kind) {
        "pdf" -> {
            val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("No se pudo abrir el PDF.")
            try {
                PdfPageSource(fd).also { if (it.count == 0) { it.close(); error("El PDF no contiene páginas.") } }
            } catch (failure: Throwable) { fd.close(); throw failure }
        }
        "rar" -> {
            val dir = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "rar_").toFile()
            try {
                val files = extractArchiveImages(context, uri, dir, checkCancelled) { n -> onProgress("$n páginas") }
                check(files.isNotEmpty()) { "El CBR no contiene páginas de imagen compatibles." }
                FolderPageSource(dir, files)
            } catch (error: Throwable) {
                dir.deleteRecursively()
                if (error is CancellationException) throw error
                if (error is IllegalStateException) throw error
                throw IllegalStateException(rarErrorMessage(error), error)
            }
        }
        else -> {
            val file = copyToCache(context, uri, checkCancelled) { pct -> onProgress("$pct%") }
            val source = runCatching { ZipPageSource(file, deleteOnClose = true) }.getOrElse {
                file.delete()
                error("El archivo no es un CBZ válido.")
            }
            if (source.count == 0) {
                source.close()
                error("El archivo no contiene páginas de imagen compatibles.")
            }
            source
        }
    }
}

/** Opens [uri] with libarchive (RAR4, RAR5, 7z, ZIP…) and visits every entry header. */
private inline fun forEachArchiveEntry(context: Context, uri: Uri, visit: (archive: Long, entry: Long, name: String, isFile: Boolean) -> Boolean) {
    val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("No se pudo leer el archivo.")
    pfd.use {
        val archive = Archive.readNew()
        try {
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatAll(archive)
            Archive.readOpenFd(archive, it.fd, 64 * 1024)
            while (true) {
                val entry = Archive.readNextHeader(archive)
                if (entry == 0L) break
                val name = ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.let { bytes -> String(bytes) } ?: ""
                val isFile = (ArchiveEntry.filetype(entry) and ArchiveEntry.AE_IFMT) == ArchiveEntry.AE_IFREG
                if (!visit(archive, entry, name.replace('\\', '/'), isFile)) break
            }
        } finally {
            runCatching { Archive.free(archive) }
        }
    }
}

private fun isPageName(name: String) =
    extensionOf(name) in IMAGE_EXTENSIONS && !name.substringAfterLast('/').startsWith(".") && !name.startsWith("__MACOSX")

private fun writeEntry(archive: Long, target: File, checkCancelled: () -> Unit = {}, limit: Long = MAX_IMAGE_BYTES.toLong()): Long {
    var written = 0L
    val buffer = ByteBuffer.allocateDirect(64 * 1024)
    val bytes = ByteArray(buffer.capacity())
    target.outputStream().use { out ->
        while (true) {
            checkCancelled()
            buffer.clear()
            Archive.readData(archive, buffer)
            buffer.flip()
            val size = buffer.remaining()
            if (size == 0) break
            check(written + size <= limit) { "La página o el archivo necesita demasiado espacio de caché." }
            buffer.get(bytes, 0, size)
            out.write(bytes, 0, size)
            written += size
        }
    }
    return written
}

/** Extracts every page image into [dir]; [onProgress] gets the number of pages found so far. */
private fun extractArchiveImages(context: Context, uri: Uri, dir: File, checkCancelled: () -> Unit, onProgress: (Int) -> Unit): List<File> {
    val out = ArrayList<Pair<String, File>>()
    var totalBytes = 0L
    forEachArchiveEntry(context, uri) { archive, _, name, isFile ->
        checkCancelled()
        if (isFile && isPageName(name)) {
            check(out.size < 10_000) { "El archivo contiene demasiadas páginas." }
            val target = File(dir, "%05d.%s".format(out.size, extensionOf(name)))
            totalBytes += writeEntry(archive, target, checkCancelled, minOf(MAX_IMAGE_BYTES.toLong(), MAX_CACHE_BYTES - totalBytes))
            out.add(name to target)
            onProgress(out.size)
        }
        true
    }
    return out.sortedBy { naturalKey(it.first) }.map { it.second }
}

/** First page of a RAR/7z archive, for covers. */
fun firstArchiveImage(context: Context, uri: Uri): ByteArray? {
    var first: String? = null
    forEachArchiveEntry(context, uri) { _, _, name, isFile ->
        if (isFile && isPageName(name) && (first == null || naturalKey(name) < naturalKey(first!!))) first = name
        true
    }
    val wanted = first ?: return null
    val tmp = File(context.cacheDir, "cover_${System.nanoTime()}")
    try {
        var found = false
        forEachArchiveEntry(context, uri) { archive, _, name, isFile ->
            if (isFile && name == wanted) { writeEntry(archive, tmp); found = true; false } else true
        }
        return if (found) tmp.inputStream().use { it.readBytesLimited(MAX_IMAGE_BYTES) } else null
    } finally {
        tmp.delete()
    }
}
