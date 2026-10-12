package com.parkingconcepts.mortimer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Cover thumbnails for the shelves, extracted once and kept in app storage. */
object Covers {
    private const val SIZE = 420
    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 12).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun dir(context: Context) = File(context.filesDir, "covers").apply { mkdirs() }

    fun clear(context: Context) {
        memory.evictAll()
        dir(context).deleteRecursively()
    }

    fun forget(context: Context, uri: String) {
        memory.remove(uri)
        File(dir(context), hash(uri) + ".jpg").delete()
        File(dir(context), hash(uri) + ".none").delete()
    }

    fun cached(uri: String): Bitmap? = memory.get(uri)

    /** Runs on a background thread. Returns null when the file has no usable cover. */
    fun load(context: Context, item: LibraryItem): Bitmap? {
        memory.get(item.uri)?.let { return it }
        val name = hash(item.uri)
        val jpg = File(dir(context), "$name.jpg")
        val none = File(dir(context), "$name.none")
        if (!jpg.exists()) {
            if (none.exists()) return null
            val extracted = runCatching { extract(context, item) }.getOrNull()
            if (extracted == null) {
                runCatching { none.createNewFile() }
                return null
            }
            runCatching { jpg.outputStream().use { extracted.compress(Bitmap.CompressFormat.JPEG, 82, it) } }
            memory.put(item.uri, extracted)
            return extracted
        }
        return BitmapFactory.decodeFile(jpg.path)?.also { memory.put(item.uri, it) }
    }

    private fun scaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun extract(context: Context, item: LibraryItem): Bitmap? {
        val uri = item.androidUri
        val resolver = context.contentResolver
        return when (item.format) {
            "audio" -> MediaMetadataRetriever().run {
                try { setDataSource(context, uri); embeddedPicture?.let { scaled(it) } } finally { release() }
            }
            "video" -> MediaMetadataRetriever().run {
                try {
                    setDataSource(context, uri)
                    getFrameAtTime(3_000_000)?.let { frame ->
                        val s = SIZE.toFloat() / maxOf(frame.width, frame.height)
                        if (s < 1f) Bitmap.createScaledBitmap(frame, (frame.width * s).toInt(), (frame.height * s).toInt(), true) else frame
                    }
                } finally { release() }
            }
            "txt", "other" -> null
            else -> when (sniffArchive(readHead(context, uri)) ?: if (item.format == "epub") "zip" else item.format) {
                "pdf" -> resolver.openFileDescriptor(uri, "r")?.use { fd ->
                    PdfRenderer(fd).use { pdf ->
                        if (pdf.pageCount == 0) return null
                        pdf.openPage(0).use { page ->
                            val s = SIZE.toFloat() / page.width
                            val bmp = Bitmap.createBitmap(SIZE, (page.height * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bmp
                        }
                    }
                }
                "rar" -> firstArchiveImage(context, uri)?.let { scaled(it) }
                else -> if (item.format == "epub") epubCover(context, item) else zipFirstImage(context, item)
            }
        }
    }

    private fun zipFirstImage(context: Context, item: LibraryItem): Bitmap? {
        val resolver = context.contentResolver
        val names = ArrayList<String>()
        resolver.openInputStream(item.androidUri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (!e.isDirectory && extensionOf(e.name) in IMAGE_EXTENSIONS && !e.name.startsWith("__MACOSX")) names.add(e.name)
                }
            }
        }
        val first = names.minByOrNull { naturalKey(it) } ?: return null
        resolver.openInputStream(item.androidUri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.name == first) return scaled(zip.readBytes())
                }
            }
        }
        return null
    }

    private fun epubCover(context: Context, item: LibraryItem): Bitmap? {
        val tmp = File(context.cacheDir, "cover_${System.nanoTime()}.epub")
        try {
            context.contentResolver.openInputStream(item.androidUri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: return null
            ZipFile(tmp).use { zip ->
                val path = EpubPackage.read(zip).cover ?: return null
                val entry = zip.getEntry(path) ?: return null
                return scaled(zip.getInputStream(entry).use { it.readBytes() })
            }
        } finally {
            tmp.delete()
        }
    }
}

/** Cover art, or a tile with the format and title while there is none. */
@Composable
fun CoverImage(item: LibraryItem, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    val c = LocalMortimerColors.current
    var bitmap by remember(item.uri) { mutableStateOf(Covers.cached(item.uri)) }
    var tried by remember(item.uri) { mutableStateOf(bitmap != null) }
    LaunchedEffect(item.uri) {
        if (bitmap == null) {
            bitmap = withContext(Dispatchers.IO) { runCatching { Covers.load(context, item) }.getOrNull() }
            tried = true
        }
    }
    val current = bitmap
    if (current != null) {
        Image(current.asImageBitmap(), contentDescription = item.title, contentScale = contentScale, modifier = modifier)
    } else {
        Box(modifier.background(c.panel2), contentAlignment = Alignment.Center) {
            if (tried) {
                Text(
                    item.title.substringBeforeLast('.'),
                    color = c.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp),
                )
                Text(item.format.uppercase(), color = c.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp))
            }
        }
    }
}
