package com.parkingconcepts.mortimer

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PageSourceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun closedSourceDoesNotDecodeAndRemovesOnlyItsOwnedCacheFile() {
        val file = temporary.newFile("comic.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("page1.png"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        val unrelated = temporary.newFile("unrelated.txt")
        val source = ZipPageSource(file, true)
        assertEquals(1, source.count)
        source.close()
        source.close()
        assertNull(source.decode(0, 1080, 1920))
        assertFalse(file.exists())
        assertTrue(unrelated.exists())
    }

    @Test fun inflatedOversizedPageIsRejectedBeforeBitmapDecode() {
        val file = temporary.newFile("large.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("large.png"))
            val chunk = ByteArray(1024 * 1024)
            repeat(MAX_IMAGE_BYTES / chunk.size + 1) { zip.write(chunk) }
            zip.closeEntry()
        }
        ZipPageSource(file, false).use { source ->
            assertThrows(IOException::class.java) { source.decode(0, 1080, 1920) }
        }
    }

    @Test fun hiddenAndMetadataFilesAreExcludedFromPageCount() {
        val file = temporary.newFile("pages.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            listOf("page2.jpg", "page10.jpg", ".hidden.jpg", "__MACOSX/page1.jpg", "notes.txt").forEach {
                zip.putNextEntry(ZipEntry(it)); zip.write(1); zip.closeEntry()
            }
        }
        ZipPageSource(file, false).use { assertEquals(2, it.count) }
    }
}
