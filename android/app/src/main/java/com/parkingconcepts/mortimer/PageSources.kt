package com.parkingconcepts.mortimer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
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

fun rarErrorMessage(error: Throwable): String {
    val name = error.javaClass.simpleName
    return if (name.contains("RarV5", ignoreCase = true) || (error.message ?: "").contains("rar5", ignoreCase = true))
        "Este CBR usa el formato RAR5, que todavía no se puede leer. Conviértelo a CBZ para abrirlo aquí."
    else "No se pudo abrir el CBR: " + (error.message ?: name)
}

/** Opens a comic or PDF as pages. Runs on a background thread. */
fun openPageSource(context: Context, uri: Uri, format: String, onProgress: (Int) -> Unit): PageSource {
    val kind = sniffArchive(readHead(context, uri)) ?: when (format) {
        "cbr" -> "rar"
        "pdf" -> "pdf"
        else -> "zip"
    }
    val stamp = System.currentTimeMillis()
    return when (kind) {
        "pdf" -> {
            val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("No se pudo abrir el PDF.")
            PdfPageSource(fd)
        }
        "rar" -> {
            val dir = File(context.cacheDir, "rar_$stamp").apply { mkdirs() }
            try {
                val files = context.contentResolver.openInputStream(uri)?.use { extractRarImages(it, dir, onProgress) }
                    ?: error("No se pudo leer el archivo.")
                check(files.isNotEmpty()) { "El CBR no contiene páginas de imagen compatibles." }
                FolderPageSource(dir, files)
            } catch (error: Throwable) {
                dir.deleteRecursively()
                if (error is IllegalStateException) throw error
                throw IllegalStateException(rarErrorMessage(error), error)
            }
        }
        else -> {
            val file = copyToCache(context, uri, "comic_$stamp.zip", onProgress)
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

private fun extractRarImages(input: InputStream, dir: File, onProgress: (Int) -> Unit): List<File> {
    val out = ArrayList<Pair<String, File>>()
    com.github.junrar.Archive(input).use { archive ->
        val headers = archive.fileHeaders.filter { !it.isDirectory && extensionOf(it.fileName) in IMAGE_EXTENSIONS }
        headers.forEachIndexed { i, header ->
            val target = File(dir, "%05d.%s".format(i, extensionOf(header.fileName)))
            target.outputStream().use { archive.extractFile(header, it) }
            out.add(header.fileName.replace('\\', '/') to target)
            onProgress(((i + 1) * 100) / headers.size)
        }
    }
    return out.sortedBy { naturalKey(it.first) }.map { it.second }
}

/** First page of a RAR archive, for covers. */
fun firstRarImage(input: InputStream): ByteArray? {
    com.github.junrar.Archive(input).use { archive ->
        val header = archive.fileHeaders
            .filter { !it.isDirectory && extensionOf(it.fileName) in IMAGE_EXTENSIONS }
            .minByOrNull { naturalKey(it.fileName.replace('\\', '/')) } ?: return null
        val bytes = ByteArrayOutputStream()
        archive.extractFile(header, bytes)
        return bytes.toByteArray()
    }
}
