package com.parkingconcepts.mortimer

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EpubReaderTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun epub(opf: String, resources: Map<String, ByteArray>, encoding: java.nio.charset.Charset = Charsets.UTF_8): File {
        val file = File.createTempFile("epub-test", ".epub", context.cacheDir)
        ZipOutputStream(file.outputStream()).use { zip ->
            val entries = mapOf("META-INF/container.xml" to """<?xml version="1.0"?><container><rootfiles><rootfile full-path="OPS/package.opf"/></rootfiles></container>""".toByteArray(), "OPS/package.opf" to opf.toByteArray(encoding)) + resources
            entries.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
        }
        return file
    }
    @Test fun opensUtf16PackageAndChapterWithoutLosingAccents() = runBlocking<Unit> {
        val opf = """<?xml version="1.0" encoding="UTF-16"?><package><metadata><dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">Lectura</dc:title></metadata><manifest><item id="c" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>"""
        val chapter = """<?xml version="1.0" encoding="UTF-16"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Test</title></head><body><p>Ilusión y café</p></body></html>""".toByteArray(Charsets.UTF_16)
        val file = epub(opf, mapOf("OPS/chapter.xhtml" to chapter), Charsets.UTF_16)
        try { openBook(context, Uri.fromFile(file), "Book", "epub").use { book ->
            assertEquals(1, book.chapters.size)
            val prepared = prepareBookHtml(requireNotNull(book.read(book.chapters.single())) , true).toString(Charsets.UTF_8)
            assertTrue(prepared.contains("Ilusión y café"))
            assertTrue(prepared.contains("encoding=\"UTF-8\""))
            assertFalse(prepared.contains("UTF-16"))
            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(prepared.byteInputStream())
        } } finally { file.delete() }
    }
    @Test fun readsEscapedManifestPathsWithoutCollapsingFilenameSpaces() = runBlocking<Unit> {
        val opf = """<package><manifest><item id="c" href="Part%201%20%20%26%20Notes.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>"""
        val file = epub(opf, mapOf("OPS/Part 1  & Notes.xhtml" to "<html><body>Chapter</body></html>".toByteArray()))
        try { openBook(context, Uri.fromFile(file), "Book", "epub").use { assertEquals(listOf("OPS/Part 1  & Notes.xhtml"), it.chapters) } }
        finally { file.delete() }
        val xmlEscaped = epub(opf.replace("Part%201%20%20%26%20Notes.xhtml", "A&amp;B.xhtml"), mapOf("OPS/A&B.xhtml" to "<html/>".toByteArray()))
        try { openBook(context, Uri.fromFile(xmlEscaped), "Book", "epub").use { assertEquals(listOf("OPS/A&B.xhtml"), it.chapters) } }
        finally { xmlEscaped.delete() }
    }
    @Test fun skipsEmptyNavigationAndNonlinearDocumentsWithoutHidingARealContentsPage() = runBlocking<Unit> {
        val opf = """<package><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="aside" href="aside.xhtml" media-type="application/xhtml+xml"/><item id="c" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="missing-cover"/><itemref idref="nav"/><itemref idref="aside" linear="no"/><itemref idref="c"/></spine></package>"""
        fun book(nav: String) = epub(opf, mapOf("OPS/nav.xhtml" to nav.toByteArray(), "OPS/aside.xhtml" to "<html><body>Aside</body></html>".toByteArray(), "OPS/chapter.xhtml" to "<html><body>Chapter</body></html>".toByteArray()))
        for ((nav, chapters) in listOf("<html><body><nav><h2></h2><ol/></nav></body></html>" to listOf("OPS/chapter.xhtml"),
            "<html><body><nav><h2>Contents</h2><ol><li><a href='chapter.xhtml'>Chapter</a></li></ol></nav></body></html>" to listOf("OPS/nav.xhtml", "OPS/chapter.xhtml"))) {
            val file = book(nav)
            try { openBook(context, Uri.fromFile(file), "Book", "epub").use { assertEquals(chapters, it.chapters) } }
            finally { file.delete() }
        }
    }
    @Test fun keepsTheSameReadingDocumentWhenLegacyChapterNumbersShift() {
        val legacy = listOf("empty-nav.xhtml", "first.xhtml", "aside.xhtml", "second.xhtml")
        val chapters = listOf("first.xhtml", "second.xhtml")
        assertEquals(1 to .4f, restoredBookPosition("3:0.4", 4, chapters, legacy))
        assertEquals(0 to 0f, restoredBookPosition("0:1.0", 4, chapters, legacy))
        assertEquals(1 to 0f, restoredBookPosition("2:0.5", 4, chapters, legacy))
        assertEquals(1 to .6f, restoredBookPosition("1:0.6", 2, chapters, legacy))
        assertEquals(0 to 0f, restoredBookPosition(null, 0, chapters, legacy))
    }
    @Test fun honorsManifestXhtmlForHtmlNamedChaptersWithNamespacedSvg() = runBlocking<Unit> {
        val opf = """<package><manifest><item id="c" href="cover.html" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>"""
        val file = epub(opf, mapOf("OPS/cover.html" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><svg:svg xmlns:svg="http://www.w3.org/2000/svg" viewBox="0 0 160 240"/></body></html>""".toByteArray()))
        try { openBook(context, Uri.fromFile(file), "Book", "epub").use { assertEquals("application/xhtml+xml", it.mimeType(it.chapters.single())) } }
        finally { file.delete() }
    }
    @Test fun resolvesAnchorOnlyLinksAndEncodedFragmentsAgainstTheCurrentDocument() {
        assertEquals("OPS/chapter.xhtml#part 1", EpubPackage.resolve("OPS/chapter.xhtml", "#part%201"))
        assertEquals("OPS/chapter.xhtml", EpubPackage.resolve("OPS/chapter.xhtml", ""))
        assertEquals("chapter.xhtml#end", EpubPackage.resolve("OPS/nav.xhtml", "../chapter.xhtml#end"))
    }
    @Test fun externalOpenOnlyAcceptsContentDocumentsAndPreservesReadGrant() {
        val uri = Uri.parse("content://documents/example")
        val output = epubReaderIntent(context, Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/epub+zip"), "Book.epub")
        assertEquals(uri, output.data)
        assertEquals(uri, output.clipData!!.getItemAt(0).uri)
        assertTrue(output.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("epub", output.getStringExtra(EpubReaderActivity.EXTRA_FORMAT))
        assertEquals(EpubReaderActivity::class.java.name, output.component!!.className)
        for (value in listOf("file:///data/private", "https://example.com/book.epub", "javascript:alert(1)")) {
            assertThrows(IllegalArgumentException::class.java) { epubReaderIntent(context, Intent(Intent.ACTION_VIEW, Uri.parse(value)), "Book") }
        }
    }
    @Test fun discoversMugenAsAnEpubViewerWithoutClaimingOtherFileTypes() {
        fun resolves(mime: String) = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://documents/book"), mime), 0)
            .any { it.activityInfo.name == OpenEpubActivity::class.java.name }
        assertTrue(resolves("application/epub+zip"))
        assertTrue(resolves("application/x-epub+zip"))
        assertFalse(resolves("application/pdf"))
    }
    @Test fun decodesDeclaredLegacyEncodingsAndBomWithoutReplacementCharacters() {
        val markup = """<?xml version="1.0" encoding="windows-1252"?><html><body>café</body></html>"""
        assertEquals(markup, decodeBookMarkup(markup.toByteArray(java.nio.charset.Charset.forName("windows-1252"))))
        assertEquals("<html/>", decodeBookMarkup(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "<html/>".toByteArray()))
    }
    @Test fun invalidEpubCleansItsTemporaryCacheFile() {
        val file = epub("<package/>", emptyMap())
        val before = context.cacheDir.listFiles()!!.map { it.name }.toSet()
        try { assertThrows(IllegalStateException::class.java) { runBlocking { openBook(context, Uri.fromFile(file), "Book", "epub") } }
            assertEquals(before, context.cacheDir.listFiles()!!.map { it.name }.toSet())
        } finally { file.delete() }
    }
}
