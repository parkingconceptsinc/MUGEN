package com.parkingconcepts.mortimer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

class SafeIoTest {
    @Test fun readsStreamsAtExactLimitIncludingEmptyFiles() {
        assertArrayEquals(byteArrayOf(1, 2, 3), ByteArrayInputStream(byteArrayOf(1, 2, 3)).readBytesLimited(3))
        assertArrayEquals(byteArrayOf(), ByteArrayInputStream(byteArrayOf()).readBytesLimited(0))
    }

    @Test fun rejectsActualSizeOverflowWithoutTrustingAvailable() {
        val input = object : ByteArrayInputStream(ByteArray(100)) { override fun available() = 0 }
        assertThrows(IOException::class.java) { input.readBytesLimited(99) }
    }

    @Test fun copyDoesNotWritePastLimit() {
        val output = ByteArrayOutputStream()
        assertThrows(IOException::class.java) { ByteArrayInputStream(ByteArray(200_000)).copyLimitedTo(output, 100_000) }
        assertTrue(output.size() <= 100_000)
    }

    @Test fun handlesZeroLengthBulkReadsWithoutSpinning() {
        val input = object : InputStream() {
            var next = 0
            override fun read(bytes: ByteArray, off: Int, len: Int) = 0
            override fun read() = if (next < 3) ++next else -1
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), input.readBytesLimited(3))
    }

    @Test fun copyCancellationPropagatesBeforeWritingMoreData() {
        var calls = 0
        val output = ByteArrayOutputStream()
        assertThrows(CancellationException::class.java) {
            ByteArrayInputStream(ByteArray(200_000)).copyLimitedTo(output, 300_000, {
                if (++calls == 2) throw CancellationException("cancelled")
            })
        }
        assertEquals(64 * 1024, output.size())
    }

    @Test fun supportsLongMaxLimitWithoutOverflow() {
        val output = ByteArrayOutputStream()
        assertEquals(3L, ByteArrayInputStream(byteArrayOf(1, 2, 3)).copyLimitedTo(output, Long.MAX_VALUE))
    }

    @Test fun extremeImageDimensionsRemainInsidePixelAndTextureLimits() {
        listOf(100_000 to 100_000, 100_000 to 10, Int.MAX_VALUE to Int.MAX_VALUE).forEach { (w, h) ->
            val sample = imageSampleSize(w, h, 1080, 1920)
            assertTrue(w.toLong() / sample * (h.toLong() / sample) <= MAX_BITMAP_PIXELS)
            assertTrue(maxOf(w.toLong() / sample, h.toLong() / sample) <= 8192)
        }
    }

    @Test fun invalidViewportDoesNotOverflowOrLoopForever() {
        assertTrue(imageSampleSize(1000, 1000, 0, 0) > 0)
        assertEquals(1, imageSampleSize(-1, -1, 100, 100))
    }

    @Test fun cancellationAfterOpeningClosesResourceInsteadOfLeakingIt() = runBlocking {
        val closed = AtomicBoolean(false)
        val job = launch {
            openResourceOnIo {
                currentCoroutineContext().cancel()
                Closeable { closed.set(true) }
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(closed.get())
    }

    @Test fun successfulOpenTransfersOwnershipToCaller() = runBlocking {
        val closed = AtomicBoolean(false)
        val resource = openResourceOnIo { Closeable { closed.set(true) } }
        assertFalse(closed.get())
        resource.close()
        assertTrue(closed.get())
    }
}
