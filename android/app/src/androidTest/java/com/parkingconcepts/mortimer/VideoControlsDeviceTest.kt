package com.parkingconcepts.mortimer

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
@RunWith(AndroidJUnit4::class)
class VideoControlsDeviceTest {
    @androidx.test.filters.SdkSuppress(minSdkVersion = 34)
    @Test fun controlsHideAfterInactivityAndRecoverFromTouchPausePinAndHeldToolbar() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File.createTempFile("video-controls-qa-", ".mp4", context.cacheDir)
        instrumentation.context.assets.open("media-studio.mp4").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        val libraryBefore = LibraryStore.load(context)
        val preferences = listOf("video_settings", "playback_positions").associateWith { context.getSharedPreferences(it, 0).all.toMap() }
        val thread = HandlerThread("video-controls-qa-frames").apply { start() }
        val bitmap = AtomicReference<Bitmap?>()
        fun reader(w: Int, h: Int) = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage() }.getOrNull()?.use { image ->
                val plane = image.planes[0]
                val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val frame = Bitmap.createBitmap(padded, 0, 0, w, h)
                if (frame !== padded) padded.recycle()
                bitmap.getAndSet(frame)?.recycle()
            } }, Handler(thread.looper))
        }
        var images = reader(1280, 800)
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = manager.createVirtualDisplay("MUGEN Video Controls QA", 1280, 800, 160, images.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION)
        val automation = instrumentation.uiAutomation
        val oldInfo = automation.serviceInfo
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun nodes(): List<AccessibilityNodeInfo> {
            automation.clearCache()
            val all = ArrayList<AccessibilityNodeInfo>()
            fun visit(n: AccessibilityNodeInfo, depth: Int) { if (depth > 40 || all.size > 5000) return; all.add(n); for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1) } }
            automation.windowsOnAllDisplays.get(display.display.displayId).orEmpty().mapNotNull { it.root }.forEach { visit(it, 0) }
            return all
        }
        fun waitUntil(message: String, timeout: Long = 10_000, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + timeout
            while (SystemClock.uptimeMillis() < deadline) { if (condition()) return; Thread.sleep(75) }
            error(message)
        }
        fun find(label: String): AccessibilityNodeInfo {
            var found: AccessibilityNodeInfo? = null
            waitUntil("Missing $label") { found = nodes().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }; found != null }
            return found!!
        }
        fun click(label: String) {
            val node = find(label)
            val button = generateSequence(node) { it.parent }.take(8).firstOrNull { it.isClickable } ?: node
            assertTrue("Cannot click $label", button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        fun nativeView(): PlayerView {
            var result: PlayerView? = null
            val search = Runnable {
                fun visit(v: View) { if (v is PlayerView) result = v else if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i)) }
                WindowInspector.getGlobalWindowViews().filter { it.display?.displayId == display.display.displayId }.forEach(::visit)
            }
            if (Looper.myLooper() == Looper.getMainLooper()) search.run() else instrumentation.runOnMainSync(search)
            return requireNotNull(result) { "No PlayerView on QA display" }
        }
        fun visible(): Boolean { var result = false; instrumentation.runOnMainSync { result = nativeView().isControllerFullyVisible }; return result }
        fun playerAction(action: (androidx.media3.exoplayer.ExoPlayer) -> Unit) { instrumentation.runOnMainSync { action(requireNotNull(VideoControlsQaActivity.current.get()?.player)) } }
        fun tapVideo() {
            instrumentation.runOnMainSync {
                val view = nativeView(); val down = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, view.width / 2f, view.height / 3f, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN; view.dispatchTouchEvent(this); recycle()
                }
            }
        }
        fun screenshot(name: String) {
            Thread.sleep(250); val done = CountDownLatch(1)
            Handler(thread.looper).post { bitmap.get()?.let { frame -> File(context.cacheDir, name).outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) } }; done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
        }
        try {
            automation.adoptShellPermissionIdentity("android.permission.ACTIVITY_EMBEDDING")
            try { context.startActivity(Intent(context, VideoControlsQaActivity::class.java).putExtra("fixture", fixture.path).putExtra("qa_display", display.display.displayId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK), ActivityOptions.makeBasic().setLaunchDisplayId(display.display.displayId).toBundle()) }
            finally { automation.dropShellPermissionIdentity() }
            find("Pin controls")
            waitUntil("Video never started") { var ready = false; playerAction { assertNull(it.playerError); ready = it.isPlaying }; ready }
            screenshot("video-controls-visible-tablet.png")
            val width = nativeView().width; val height = nativeView().height
            waitUntil("Controller did not auto-hide", 5500) { !visible() }
            waitUntil("Toolbar remained visible") { nodes().none { it.text?.toString() == "Pin controls" } }
            assertEquals(width, nativeView().width); assertEquals(height, nativeView().height)
            screenshot("video-controls-hidden-tablet.png")
            tapVideo(); find("Pin controls"); assertTrue(visible())
            tapVideo(); waitUntil("Tap did not hide controls") { !visible() }; tapVideo(); find("Pin controls")
            click("Pin controls"); find("Unpin controls"); Thread.sleep(4700); assertTrue("Pinned controls disappeared", visible())
            click("Unpin controls"); find("Pin controls"); waitUntil("Unpinned controls did not hide", 5500) { !visible() }
            playerAction { it.pause() }; find("Pin controls"); Thread.sleep(4700); assertTrue("Paused controls disappeared", visible())
            playerAction { it.play() }; waitUntil("Resumed controls did not hide", 5500) { !visible() }
            tapVideo(); val title = find("Pin controls"); val bounds = Rect(); title.getBoundsInScreen(bounds)
            val down = SystemClock.uptimeMillis()
            fun toolbarEvent(action: Int) { instrumentation.runOnMainSync {
                val root = nativeView().rootView
                MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN; root.dispatchTouchEvent(this); recycle()
                }
            } }
            toolbarEvent(MotionEvent.ACTION_DOWN)
            try { Thread.sleep(4700); assertTrue("Controls hid under a held finger", visible()) }
            finally { toolbarEvent(MotionEvent.ACTION_CANCEL) }
            waitUntil("Released toolbar did not restart auto-hide", 5500) { !visible() }
            val phone = reader(420, 900); display.resize(420, 900, 160); display.surface = phone.surface; images.close(); images = phone
            Thread.sleep(800); tapVideo(); find("Pin controls"); screenshot("video-controls-visible-phone.png")
            waitUntil("Phone controls did not auto-hide", 5500) { !visible() }; screenshot("video-controls-hidden-phone.png")
            tapVideo(); click("Close"); assertTrue(VideoControlsQaActivity.destroyed.await(10, TimeUnit.SECONDS))
            assertEquals(libraryBefore, LibraryStore.load(context))
            preferences.forEach { (name, before) -> assertEquals(name, before, context.getSharedPreferences(name, 0).all) }
        } finally {
            instrumentation.runOnMainSync { VideoControlsQaActivity.current.get()?.finish() }
            VideoControlsQaActivity.destroyed.await(10, TimeUnit.SECONDS)
            automation.serviceInfo = oldInfo; display.release(); images.close(); thread.quitSafely(); bitmap.getAndSet(null)?.recycle(); fixture.delete()
        }
    }
}
