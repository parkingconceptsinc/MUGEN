package com.parkingconcepts.mortimer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Manual end-to-end audit of every saved EPUB through the real Books shelf and real reader activity. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class EpubBooksAuditDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var auditDisplayId: Int? = null
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        val roots = auditDisplayId?.let { id -> instrumentation.uiAutomation.windowsOnAllDisplays.get(id).orEmpty().mapNotNull { it.root }.filter { it.packageName?.toString() == context.packageName } }
            ?: listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow).also { list -> check(list.all { it.packageName?.toString() == context.packageName }) { "MUGEN is no longer the active app; audit stopped" } }
        val result = ArrayList<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || result.size > 5000) return
            result.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        roots.forEach { visit(it, 0) }; return result
    }
    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            nodes().firstOrNull(predicate)?.let { return it }
            Thread.sleep(100)
        }
        error("Expected Books control was not found")
    }
    private fun click(node: AccessibilityNodeInfo) {
        val target = generateSequence(node) { it.parent }.take(8).firstOrNull { it.isClickable } ?: node
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.ACTIVITY_EMBEDDING")
        try { check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Books control did not accept a click" } }
        finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
    }
    private fun resumed(): Activity? = main {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { auditDisplayId == null || it.display?.displayId == auditDisplayId }
    }
    private fun web(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) web(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun metrics(activity: Activity): JSONObject? {
        val done = CountDownLatch(1)
        var value: String? = null
        main {
            val view = web(activity.window.decorView)
            if (view == null) done.countDown() else view.evaluateJavascript(METRICS) { value = it; done.countDown() }
        }
        check(done.await(5, TimeUnit.SECONDS)) { "Reader JavaScript did not respond" }
        return value?.takeIf { it != "null" }?.let { JSONObject(JSONArray("[$it]").getString(0)) }
    }
    private fun awaitLoaded(activity: Activity): JSONObject {
        val deadline = System.currentTimeMillis() + 20_000
        var last: JSONObject? = null
        while (System.currentTimeMillis() < deadline) {
            last = metrics(activity)
            if (last?.optString("setup") == "function" && last.optInt("errors") == 0 && last.optInt("width") > 0) {
                Thread.sleep(350)
                return metrics(activity) ?: last
            }
            Thread.sleep(100)
        }
        error("Reader did not load: $last")
    }
    private fun finish(activity: Activity) {
        main { if (!activity.isFinishing) activity.finish() }
        val deadline = System.currentTimeMillis() + 10_000
        while (!main { activity.isDestroyed } && System.currentTimeMillis() < deadline) Thread.sleep(50)
        ReaderCheckpoints.awaitPendingWrites()
    }
    @androidx.test.filters.SdkSuppress(minSdkVersion = 34)
    @Test fun auditEveryEpubFromTheRealBooksShelf() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("auditEveryEpubFromBooks") == "yes")
        val beforeBytes = File(context.filesDir, "library.json").readBytes()
        val before = LibraryStore.load(context)
        val beforeBackup = File(context.filesDir, "library.backup.json").takeIf { it.isFile }?.readBytes()
        val allBooks = before.filter { it.kind == "books" && it.format == "epub" }
        val indices = InstrumentationRegistry.getArguments().getString("epubAuditIndices")?.split(',')?.map { it.toInt() }
        val books = if (indices == null) allBooks else indices.map { allBooks[it] }
        assertTrue(books.isNotEmpty())
        val report = JSONArray()
        var reader: Activity? = null
        val thread = android.os.HandlerThread("books-audit-display").apply { start() }
        val images = android.media.ImageReader.newInstance(1280, 800, android.graphics.PixelFormat.RGBA_8888, 3)
        images.setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage() }.getOrNull()?.close() }, android.os.Handler(thread.looper))
        val manager = context.getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val display = manager.createVirtualDisplay("MUGEN Books audit", 1280, 800, 160, images.surface,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION)
        auditDisplayId = display.display.displayId
        val automation = instrumentation.uiAutomation
        val oldInfo = automation.serviceInfo
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        var mainActivity: Activity? = null
        try {
            automation.adoptShellPermissionIdentity("android.permission.ACTIVITY_EMBEDDING")
            try { mainActivity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK), android.app.ActivityOptions.makeBasic().setLaunchDisplayId(auditDisplayId!!).toBundle()) }
            finally { automation.dropShellPermissionIdentity() }
            check(main { mainActivity!!.display?.displayId } == auditDisplayId) { "Audit activity must stay on its secondary display" }
            click(awaitNode { it.text?.toString() == "Books" })
            awaitNode { it.isEditable }
            books.forEachIndexed { index, item ->
                val result = JSONObject().put("index", index).put("title", item.title)
                val issues = JSONArray()
                result.put("issues", issues)
                report.put(result)
                try {
                    openBook(context, item.androidUri, "Book", "epub").use { book ->
                        var resources = 0
                        val missing = LinkedHashSet<String>()
                        book.chapters.forEach { chapter ->
                            val bytes = book.read(chapter) ?: error("A chapter is missing")
                            val html = decodeBookMarkup(bytes)
                            RESOURCE.findAll(html).forEach { match ->
                                val href = android.text.Html.fromHtml(match.groupValues[1], android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                                if (href.isNotBlank() && !href.startsWith('#') && !href.contains(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:"))) {
                                    val path = EpubPackage.resolve(chapter, href).substringBefore('#')
                                    resources++
                                    if (runCatching { book.read(path) }.getOrNull() == null) missing.add(path)
                                }
                            }
                        }
                        if (missing.isNotEmpty()) issues.put("Some local chapter resources are missing or unreadable")
                        result.put("chapters_checked", book.chapters.size).put("local_resource_references_checked", resources).put("missing_resources", JSONArray(missing.toList()))
                    }
                    val search = awaitNode { it.isEditable }
                    assertTrue(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, item.title) }))
                    Thread.sleep(250)
                    click(awaitNode { it.text?.toString() == item.title.substringBeforeLast('.') })
                    val deadline = System.currentTimeMillis() + 10_000
                    while (resumed() !is EpubReaderActivity && System.currentTimeMillis() < deadline) Thread.sleep(100)
                    reader = resumed()?.takeIf { it is EpubReaderActivity } ?: error("Books did not open the internal EPUB activity")
                    val initial = awaitLoaded(reader!!)
                    result.put("initial", initial)
                    if (initial.optInt("visibleText") == 0 && initial.optInt("visibleImages") == 0) issues.put("Chapter initialized but has no visible text or images")
                    if (initial.optInt("failedImages") > 0) issues.put("Some chapter images failed to load")
                    click(awaitNode { it.text?.toString() == "›" })
                    Thread.sleep(600)
                    val advanced = awaitLoaded(reader!!)
                    result.put("after_next_page", advanced)
                    val moved = initial.optString("path") != advanced.optString("path") || initial.optInt("x") != advanced.optInt("x") || initial.optInt("y") != advanced.optInt("y")
                    val completed = nodes().any { it.text?.toString() == "You have finished this book" }
                    result.put("navigation_changed", moved || completed)
                    if (!moved && !completed) issues.put("Next-page action did not change chapter or position")
                } catch (failure: Throwable) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    issues.put(failure.javaClass.simpleName + ": " + failure.message)
                    if (failure.message?.startsWith("MUGEN is no longer") == true) throw failure
                } finally {
                    reader?.let(::finish); reader = null
                    resumed()?.takeIf { it is EpubReaderActivity }?.let(::finish)
                    File(context.cacheDir, "epub-books-audit.json").writeText(report.toString(2))
                }
            }
        } finally {
            reader?.let(::finish)
            resumed()?.takeIf { it is EpubReaderActivity }?.let(::finish)
            mainActivity?.let(::finish)
            ReaderCheckpoints.awaitPendingWrites()
            val originals = books.associateBy { it.uri }
            val latest = LibraryStore.load(context)
            // Restore only the checkpoint fields touched by this audit; preserve unrelated edits.
            val restored = latest.map { item -> originals[item.uri]?.let { old -> item.copy(position = old.position, progress = old.progress, pages = old.pages, openedAt = old.openedAt) } ?: item }
            if (restored == before) {
                fun restore(name: String, bytes: ByteArray) {
                    val target = android.util.AtomicFile(File(context.filesDir, name)); val stream = target.startWrite()
                    try { stream.write(bytes); target.finishWrite(stream) } catch (failure: Throwable) { target.failWrite(stream); throw failure }
                }
                restore("library.json", beforeBytes)
                beforeBackup?.let { restore("library.backup.json", it) }
            } else LibraryStore.update(context) { current -> current.map { item -> originals[item.uri]?.let { old -> item.copy(position = old.position, progress = old.progress, pages = old.pages, openedAt = old.openedAt) } ?: item } }
            File(context.cacheDir, "epub-books-audit.json").writeText(report.toString(2))
            automation.serviceInfo = oldInfo; display.release(); images.close(); thread.quitSafely(); auditDisplayId = null
        }
        val failed = (0 until report.length()).count { report.getJSONObject(it).getJSONArray("issues").length() > 0 }
        println("Reviewed ${report.length()} EPUBs from Books; $failed with issues; original checkpoints restored")
        assertEquals("Books audit found $failed EPUBs with issues. See the local report.", 0, failed)
    }
    companion object {
        private val RESOURCE = Regex("""(?is)<(?:\w+:)?(?:img|image|link|source)\b[^>]*?\b(?:src|(?:xlink:)?href)\s*=\s*["']([^"']+)["']""")
        private const val METRICS = """(function(){
          var w=innerWidth,h=innerHeight,visible=0;
          if(document.body){ var t=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT),n,count=0;
            while((n=t.nextNode()) && count++<5000){ if(!n.textContent.trim())continue; var p=n.parentElement,s=getComputedStyle(p); if(s.display==='none'||s.visibility==='hidden'||s.opacity==='0')continue;
              var r=document.createRange();r.selectNodeContents(n);var a=r.getClientRects();for(var i=0;i<a.length;i++){var b=a[i];if(b.width>0&&b.height>0&&b.right>0&&b.left<w&&b.bottom>0&&b.top<h){visible++;break;}} }
          }
          var imgs=Array.from(document.images||[]),good=0,bad=0;imgs.forEach(function(im){if(im.complete&&!im.naturalWidth)bad++;var r=im.getBoundingClientRect();if(im.naturalWidth&&r.width>0&&r.height>0&&r.right>0&&r.left<w&&r.bottom>0&&r.top<h)good++;});
          Array.from(document.getElementsByTagNameNS('http://www.w3.org/2000/svg','svg')).forEach(function(svg){var r=svg.getBoundingClientRect();if(r.width>0&&r.height>0&&r.right>0&&r.left<w&&r.bottom>0&&r.top<h)good++;});
          return JSON.stringify({setup:typeof window.mortimerSetup,errors:document.getElementsByTagName('parsererror').length,width:w,height:h,x:Math.round(scrollX),y:Math.round(scrollY),scrollWidth:document.documentElement.scrollWidth,scrollHeight:document.documentElement.scrollHeight,visibleText:visible,visibleImages:good,failedImages:bad,path:location.pathname,svgDetails:Array.from(document.getElementsByTagNameNS('http://www.w3.org/2000/svg','svg')).map(function(svg){var r=svg.getBoundingClientRect(),s=getComputedStyle(svg);return {x:r.x,y:r.y,w:r.width,h:r.height,cssWidth:s.width,cssHeight:s.height,display:s.display,children:Array.from(svg.getElementsByTagNameNS('http://www.w3.org/2000/svg','image')).map(function(im){var r=im.getBoundingClientRect(),s=getComputedStyle(im);return{x:r.x,y:r.y,w:r.width,h:r.height,cssWidth:s.width,cssHeight:s.height};})};}),bodyPresent:!!document.body,imgElements:document.getElementsByTagName('img').length,
            imageDetails:Array.from(document.getElementsByTagName('img')).slice(0,3).map(function(im){var r=im.getBoundingClientRect(),s=getComputedStyle(im);return {complete:im.complete,naturalWidth:im.naturalWidth,naturalHeight:im.naturalHeight,x:r.x,y:r.y,w:r.width,h:r.height,display:s.display,visibility:s.visibility};}),
            bodyDetails:document.body?(function(){var r=document.body.getBoundingClientRect(),s=getComputedStyle(document.body);return{x:r.x,y:r.y,w:r.width,h:r.height,display:s.display,columns:s.columnWidth,overflow:s.overflow,namespace:document.body.namespaceURI};})():null});
        })()"""
    }
}
