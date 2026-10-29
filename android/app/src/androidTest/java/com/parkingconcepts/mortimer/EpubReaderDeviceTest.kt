package com.parkingconcepts.mortimer

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EpubReaderDeviceTest {
    class Bridge {
        @JavascriptInterface fun position(f: Double, p: Int, n: Int) { }
        @JavascriptInterface fun tap(zone: Int) { }
        @JavascriptInterface fun edge(direction: Int) { }
    }
    @androidx.test.filters.SdkSuppress(minSdkVersion = 34)
    @Test fun fullReaderLoadsOnSecondaryDisplayWithoutSavingProgress() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probeExistingEpub") == "yes")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val before = LibraryStore.load(context)
        val args = InstrumentationRegistry.getArguments()
        val supplied = args.getString("epubUrisB64")?.let { org.json.JSONArray(String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8)) }
        val uris = if (supplied == null) before.filter { it.format == "epub" }.map { it.androidUri } else (0 until supplied.length()).map { android.net.Uri.parse(supplied.getString(it)) }
        fullReaderChecks((uris.take(1) + uris.takeLast(2)).distinct())
        assertEquals(before, LibraryStore.load(context))
    }
    @androidx.test.filters.SdkSuppress(minSdkVersion = 34)
    @Test fun utf16AndMalformedChaptersRecoverInTheActualReader() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtures = ArrayList<java.io.File>()
        fun fixture(utf16: Boolean, broken: Boolean, cover: Boolean = false, svg: Boolean = false): android.net.Uri {
            val file = java.io.File.createTempFile("reader-qa-", ".epub", context.cacheDir); fixtures.add(file)
            val charset = if (utf16) Charsets.UTF_16 else Charsets.UTF_8
            val encoding = if (utf16) "UTF-16" else "UTF-8"
            val text = if (broken) "Recovered chapter &nbsp; test" else "Ilusión y café"
            val chapterPath = if (svg) "chapter.html" else "chapter.xhtml"
            val body = if (svg) """<svg:svg xmlns:svg="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 160 240"><svg:image width="160" height="240" xlink:href="cover.png"/></svg:svg>""" else if (cover) """<img src="cover.png" alt="Synthetic cover" style="height:100%"/>""" else "<p>$text</p>"
            val bitmap = android.graphics.Bitmap.createBitmap(160, 240, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
            val image = java.io.ByteArrayOutputStream().use { output -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
            bitmap.recycle()
            java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
                mapOf("META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OPS/package.opf"/></rootfiles></container>""".toByteArray(),
                    "OPS/package.opf" to """<?xml version="1.0" encoding="$encoding"?><package><manifest><item id="c" href="$chapterPath" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>""".toByteArray(charset),
                    "OPS/cover.png" to image,
                    "OPS/$chapterPath" to """<?xml version="1.0" encoding="$encoding"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Test</title></head><body>$body</body></html>""".toByteArray(charset)).forEach { (path, bytes) ->
                    zip.putNextEntry(java.util.zip.ZipEntry(path)); zip.write(bytes); zip.closeEntry()
                }
            }
            return android.net.Uri.fromFile(file)
        }
        try { fullReaderChecks(listOf(fixture(true, false), fixture(false, true), fixture(false, false, cover = true), fixture(false, false, svg = true)), setOf(1), setOf(2, 3)) }
        finally { fixtures.forEach { it.delete() } }
    }
    private fun fullReaderChecks(uris: List<android.net.Uri>, compatibilityExpected: Set<Int> = emptySet(), visibleImageExpected: Set<Int> = emptySet()) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val before = LibraryStore.load(context)
        val thread = android.os.HandlerThread("book-offscreen").apply { start() }
        val images = android.media.ImageReader.newInstance(1280, 800, android.graphics.PixelFormat.RGBA_8888, 3)
        images.setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage() }.getOrNull()?.close() }, android.os.Handler(thread.looper))
        val manager = context.getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val display = manager.createVirtualDisplay("MUGEN Book QA", 1280, 800, 160, images.surface,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION)
        fun close() {
            instrumentation.runOnMainSync { BookQaActivity.current.get()?.finish() }
            check(BookQaActivity.destroyed.await(10, TimeUnit.SECONDS))
        }
        fun findWeb(view: android.view.View): WebView? {
            if (view is WebView) return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) findWeb(view.getChildAt(i))?.let { return it }
            return null
        }
        val automation = instrumentation.uiAutomation
        val oldInfo = automation.serviceInfo
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun compatibilityButton(): android.view.accessibility.AccessibilityNodeInfo? {
            automation.clearCache()
            fun find(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                if (node.text?.toString() == "Compatibility mode") return generateSequence(node) { it.parent }.take(6).firstOrNull { it.isClickable } ?: node
                for (i in 0 until node.childCount) node.getChild(i)?.let { find(it) }?.let { return it }
                return null
            }
            return automation.windowsOnAllDisplays.get(display.display.displayId).orEmpty().mapNotNull { it.root }.firstNotNullOfOrNull(::find)
        }
        try {
            uris.forEachIndexed { index, uri ->
                val options = android.app.ActivityOptions.makeBasic().apply { launchDisplayId = display.display.displayId }
                instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.ACTIVITY_EMBEDDING")
                try { context.startActivity(android.content.Intent(context, BookQaActivity::class.java).setData(uri)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    .putExtra("qa_display", display.display.displayId), options.toBundle()) }
                finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
                if (index in compatibilityExpected) {
                    val deadline = System.currentTimeMillis() + 15_000
                    var button: android.view.accessibility.AccessibilityNodeInfo? = null
                    while (button == null && System.currentTimeMillis() < deadline) { button = compatibilityButton(); if (button == null) Thread.sleep(100) }
                    assertNotNull("Malformed chapter must offer compatibility recovery", button)
                    assertTrue("Compatibility recovery button must be actionable", button!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                }
                val deadline = System.currentTimeMillis() + 20_000
                var ready = false
                var last = "no WebView"
                while (!ready && System.currentTimeMillis() < deadline) {
                    val done = CountDownLatch(1)
                    instrumentation.runOnMainSync {
                        val web = BookQaActivity.current.get()?.window?.decorView?.let(::findWeb)
                        if (web == null) done.countDown() else web.evaluateJavascript("JSON.stringify({setup:typeof window.mortimerSetup, body:!!document.body, errors:document.getElementsByTagName('parsererror').length, imageVisible:(function(){var im=document.querySelector('img')||document.getElementsByTagNameNS('http://www.w3.org/2000/svg','image')[0];if(!im)return false;var r=im.getBoundingClientRect();return (im.namespaceURI==='http://www.w3.org/2000/svg'||(im.complete && im.naturalWidth>0)) && r.width>0 && r.height>0 && r.left<innerWidth && r.top<innerHeight && r.right>0 && r.bottom>0;})(), bodyHeight:document.body ? document.body.getBoundingClientRect().height : 0})") { value ->
                            last = value
                            val details = JSONObject(org.json.JSONArray("[$value]").getString(0))
                            ready = details.optString("setup") == "function" && details.optBoolean("body") && details.optInt("errors", -1) == 0 &&
                                (index !in visibleImageExpected || details.optBoolean("imageVisible"))
                            done.countDown()
                        }
                    }
                    done.await(2, TimeUnit.SECONDS)
                    if (!ready) Thread.sleep(100)
                }
                assertTrue("Reader $index failed: $last", ready)
                close()
            }
            assertEquals(before, LibraryStore.load(context))
        } finally {
            try { close() } finally { automation.serviceInfo = oldInfo; display.release(); images.close(); thread.quitSafely() }
        }
    }
    @Test fun rendersExistingEpubChaptersWithoutChangingLibrary() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probeExistingEpub") == "yes")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val before = LibraryStore.load(context)
        val args = InstrumentationRegistry.getArguments()
        val supplied = args.getString("epubUrisB64")?.let { org.json.JSONArray(String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8)) }
        val epubs = if (supplied == null) before.filter { it.format == "epub" } else (0 until supplied.length()).map { LibraryItem(supplied.getString(it), "Book", "books", "epub") }
        assertTrue(epubs.isNotEmpty())
        // Small books first; never expose document text, titles or screenshots.
        val failures = ArrayList<String>()
        epubs.forEachIndexed { index, item ->
            try {
            openBook(context, item.androidUri, "Book", "epub").use { book ->
                assertTrue(book.chapters.isNotEmpty())
                val chapter = book.chapters.firstOrNull { path -> book.read(path)?.let { bookSearchText(String(it, Charsets.UTF_8)).trim().length > 100 } == true } ?: book.chapters.first()
                val bytes = prepareBookHtml(requireNotNull(book.read(chapter)), true)
                val done = CountDownLatch(1)
                var result: String? = null
                var web: WebView? = null
                instrumentation.runOnMainSync {
                    web = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        addJavascriptInterface(Bridge(), "Mortimer")
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = WebResourceResponse("application/xhtml+xml", "utf-8", ByteArrayInputStream(bytes))
                            override fun onPageFinished(view: WebView, url: String) {
                                view.evaluateJavascript("JSON.stringify({setup:typeof window.mortimerSetup, parserErrors:document.getElementsByTagName('parsererror').length, body:!!document.body, chars:document.body ? document.body.innerText.length : 0})") { value -> result = value; done.countDown() }
                            }
                        }
                        loadUrl("https://book.local/chapter.xhtml")
                    }
                }
                try {
                    assertTrue("WebView did not complete the chapter", done.await(20, TimeUnit.SECONDS))
                    val value = JSONObject(org.json.JSONArray("[" + result + "]").getString(0))
                    assertEquals("EPUB $index render diagnostics: $value", 0, value.getInt("parserErrors"))
                    assertTrue(value.getBoolean("body"))
                    assertEquals("EPUB $index reading controls did not initialize: $value", "function", value.getString("setup"))
                    println("EPUB $index rendered; chapters=${book.chapters.size}; controls initialized")
                } finally { instrumentation.runOnMainSync { web?.destroy() } }
            }
            } catch (failure: Exception) { failures.add("EPUB $index: ${failure.javaClass.simpleName}: ${failure.message}") }
            catch (failure: AssertionError) { failures.add("EPUB $index: ${failure.message}") }
        }
        assertEquals(before, LibraryStore.load(context))
        assertTrue("EPUB failures: $failures", failures.isEmpty())
    }
}
