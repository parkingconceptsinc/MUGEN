package com.parkingconcepts.mortimer

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** withContext may discard a successfully opened resource when its caller is cancelled. */
internal suspend fun <T : Closeable> openResourceOnIo(open: suspend () -> T): T {
    var resource: T? = null
    try {
        return withContext(Dispatchers.IO) { open().also { resource = it } }
    } catch (failure: Throwable) {
        withContext(NonCancellable + Dispatchers.IO) { runCatching { resource?.close() } }
        throw failure
    }
}

internal const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
internal const val MAX_BOOK_RESOURCE_BYTES = 16 * 1024 * 1024
internal const val MAX_METADATA_BYTES = 4 * 1024 * 1024
internal const val MAX_CACHE_BYTES = 1024L * 1024 * 1024
internal const val MAX_BITMAP_PIXELS = 4 * 1024 * 1024L

/** Enforce limits on actual bytes, including streams with absent or incorrect size metadata. */
internal fun InputStream.copyLimitedTo(
    output: OutputStream,
    limit: Long,
    checkCancelled: () -> Unit = {},
    onProgress: (Long) -> Unit = {},
): Long {
    require(limit >= 0)
    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    while (true) {
        checkCancelled()
        // Read at most one byte past the limit to detect overflow without consuming
        // an unbounded chunk from a large or damaged archive.
        val available = if (limit - copied >= buffer.size) buffer.size else (limit - copied).toInt() + 1
        val count = read(buffer, 0, available)
        if (count < 0) return copied
        if (count == 0) {
            val next = read()
            if (next < 0) return copied
            if (copied == limit) throw IOException("This file exceeds the supported size limit.")
            output.write(next)
            copied++
        } else {
            if (count > limit - copied) throw IOException("This file exceeds the supported size limit.")
            output.write(buffer, 0, count)
            copied += count
        }
        onProgress(copied)
    }
}

internal fun InputStream.readBytesLimited(limit: Int, checkCancelled: () -> Unit = {}): ByteArray =
    ByteArrayOutputStream().use { output ->
        copyLimitedTo(output, limit.toLong(), checkCancelled)
        output.toByteArray()
    }

internal fun imageSampleSize(width: Int, height: Int, maxW: Int, maxH: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    while (sample < (1 shl 30)) {
        val w = width.toLong() / sample
        val h = height.toLong() / sample
        val tooLarge = w * h > MAX_BITMAP_PIXELS || maxOf(w, h) > 8192
        val canSample = w / 2 >= maxW.coerceAtLeast(1) && h / 2 >= maxH.coerceAtLeast(1)
        if (!tooLarge && !canSample) break
        sample *= 2
    }
    return sample
}
