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

private fun sampleFor(w: Int, h: Int, maxW: Int, maxH: Int): Int {
    var sample = 1
    while (w / (sample * 2) >= maxW && h / (sample * 2) >= maxH) sample *= 2
    return sample
}

private fun decodeBytes(bytes: ByteArray, maxW: Int, maxH: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
        inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, maxW, maxH)
    })
}

private fun decodeFile(file: File, maxW: Int, maxH: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return null
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
        inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, maxW, maxH)
    })
}

class ZipPageSource(private val file: File, private val deleteOnClose: Boolean) : PageSource {
    private val zip = ZipFile(file)
    private val pages: List<String> = zip.entries().asSequence()
        .filter { !it.isDirectory && extensionOf(it.name) in IMAGE_EXTENSIONS && !it.name.substringAfterLast('/').startsWith(".") && !it.name.startsWith("__MACOSX") }
        .map { it.name }
        .sortedBy { naturalKey(it) }
        .toList()

    override val count get() = pages.size

    override fun decode(index: Int, maxW: Int, maxH: Int): Bitmap? {
        val entry = zip.getEntry(pages.getOrNull(index) ?: return null) ?: return null
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        return decodeBytes(bytes, maxW, maxH)
    }

    override fun close() {
        runCatching { zip.close() }
        if (deleteOnClose) file.delete()
    }
}

/** RAR pages are extracted once into a cache folder, because RAR archives are not random-access. */
class FolderPageSource(private val dir: File, private val files: List<File>) : PageSource {
    override val count get() = files.size
    override fun decode(index: Int, maxW: Int, maxH: Int): Bitmap? = files.getOrNull(index)?.let { decodeFile(it, maxW, maxH) }
    override fun close() { dir.deleteRecursively() }
}

class PdfPageSource(private val descriptor: ParcelFileDescriptor) : PageSource {
    private val renderer = PdfRenderer(descriptor)
    override val count get() = renderer.pageCount

    @Synchronized
    override fun decode(index: Int, maxW: Int, maxH: Int): Bitmap? {
        if (index !in 0 until renderer.pageCount) return null
        val page = renderer.openPage(index)
        try {
            val scale = minOf(maxW.toFloat() / page.width, maxH.toFloat() / page.height).coerceIn(0.5f, 4f)
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

private fun copyToCache(context: Context, uri: Uri, name: String, onProgress: (Int) -> Unit): File {
    val target = File(context.cacheDir, name)
    val total = fileSize(context, uri)
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output ->
            val buf = ByteArray(64 * 1024)
            var copied = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                copied += n
                if (total > 0) onProgress((copied * 100 / total).toInt())
            }
        }
    } ?: error("No se pudo leer el archivo. Vuelve a importarlo.")
    return target
}

fun rarErrorMessage(error: Throwable): String =
    "Could not open the archive: " + (error.message ?: error.javaClass.simpleName)

/** Opens a comic or PDF as pages. Runs on a background thread. */
fun openPageSource(context: Context, uri: Uri, format: String, onProgress: (String) -> Unit): PageSource {
    val kind = sniffArchive(readHead(context, uri)) ?: when (format) {
        "cbr" -> "rar"
        "pdf" -> "pdf"
        else -> "zip"
    }
    val stamp = System.currentTimeMillis()
    return when (kind) {
        "pdf" -> {
            val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Could not open the PDF.")
            PdfPageSource(fd)
        }
        "rar" -> {
            val dir = File(context.cacheDir, "rar_$stamp").apply { mkdirs() }
            try {
                val files = extractArchiveImages(context, uri, dir) { n -> onProgress("$n páginas") }
                check(files.isNotEmpty()) { "El CBR no contiene páginas de imagen compatibles." }
                FolderPageSource(dir, files)
            } catch (error: Throwable) {
                dir.deleteRecursively()
                if (error is IllegalStateException) throw error
                throw IllegalStateException(rarErrorMessage(error), error)
            }
        }
        else -> {
            val file = copyToCache(context, uri, "comic_$stamp.zip") { pct -> onProgress("$pct%") }
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

private fun writeEntry(archive: Long, target: File) {
    ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE).use { out ->
        Archive.readDataIntoFd(archive, out.fd)
    }
}

/** Extracts every page image into [dir]; [onProgress] gets the number of pages found so far. */
private fun extractArchiveImages(context: Context, uri: Uri, dir: File, onProgress: (Int) -> Unit): List<File> {
    val out = ArrayList<Pair<String, File>>()
    forEachArchiveEntry(context, uri) { archive, _, name, isFile ->
        if (isFile && isPageName(name)) {
            val target = File(dir, "%05d.%s".format(out.size, extensionOf(name)))
            writeEntry(archive, target)
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
        return if (found) tmp.readBytes() else null
    } finally {
        tmp.delete()
    }
}
