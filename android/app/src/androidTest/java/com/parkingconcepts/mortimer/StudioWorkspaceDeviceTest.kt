package com.parkingconcepts.mortimer

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

@UnstableApi
@RunWith(AndroidJUnit4::class)
class StudioWorkspaceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun fixture() = File.createTempFile("studio_qa_", ".mp4", context.cacheDir).also { file ->
        instrumentation.context.assets.open("media-studio.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
    }

    @Test fun multiClipExportKeepsCanvasSpeedAndAudioAfterMutedIntro() {
        val file = fixture(); val output = File(context.cacheDir, "studio_qa_export.mp4").apply { delete() }
        val source = readStudioSource(context, android.net.Uri.fromFile(file), "Synthetic")
        val p = StudioProject(name = "Speed and audio", aspect = "9:16", clips = listOf(
            StudioClip(source = source, edit = VideoEditSpec(0, 2000, rotation = 90, mute = true, speed = 2f, brightness = .1f)),
            StudioClip(source = source, edit = VideoEditSpec(2000, 4000, flip = true, speed = .5f, volume = .5f, saturation = -10f))))
        val end = CountDownLatch(1); val failure = AtomicReference<ExportException?>()
        var transformer: Transformer? = null
        try {
            instrumentation.runOnMainSync {
                transformer = Transformer.Builder(context).addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) { end.countDown() }
                    override fun onError(composition: Composition, result: ExportResult, error: ExportException) { failure.set(error); end.countDown() }
                }).build().also { it.start(studioComposition(p), output.path) }
            }
            assertTrue("Montage export timed out", end.await(60, TimeUnit.SECONDS))
            assertNull("${failure.get()}", failure.get())
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.path)
                val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                assertEquals(2, formats.size)
                val video = formats.first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
                assertTrue("Output duration must be 1s + 4s", video.getLong(MediaFormat.KEY_DURATION) in 4_700_000..5_300_000)
                assertEquals(720, maxOf(video.getInteger(MediaFormat.KEY_WIDTH), video.getInteger(MediaFormat.KEY_HEIGHT)))
                assertTrue(formats.any { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") })
            } finally { extractor.release() }
        } finally {
            instrumentation.runOnMainSync { transformer?.cancel() }
            file.delete()
            if (InstrumentationRegistry.getArguments().getString("keepStudioExport") != "true") output.delete()
        }
    }

    @Test fun rapidScrubsKeepTheLastRequestAndEditsPreserveTheSourceFrame() {
        val file = fixture()
        val images = ImageReader.newInstance(320, 180, PixelFormat.RGBA_8888, 3)
        val thread = HandlerThread("studio-seek-frames").apply { start() }
        images.setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage() }.getOrNull()?.close() }, Handler(thread.looper))
        val holder = java.lang.reflect.Proxy.newProxyInstance(android.view.SurfaceHolder::class.java.classLoader,
            arrayOf(android.view.SurfaceHolder::class.java)) { _, method, _ -> when (method.name) {
            "getSurface" -> images.surface
            "getSurfaceFrame" -> android.graphics.Rect(0, 0, 320, 180)
            "addCallback", "removeCallback" -> null
            else -> error(method.name)
        } } as android.view.SurfaceHolder
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var preview: StudioPreview? = null
        fun awaitReady() {
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                val ready = java.util.concurrent.atomic.AtomicBoolean()
                instrumentation.runOnMainSync { ready.set(preview?.updating == false) }
                if (ready.get()) return
                Thread.sleep(50)
            }
            error("Preview never finished applying the last requested edit")
        }
        try {
            val source = readStudioSource(context, android.net.Uri.fromFile(file), "Seek fixture")
            val clip = StudioClip(source = source, edit = VideoEditSpec(0, 4000, speed = 2f))
            val project = StudioProject(name = "Seek test", clips = listOf(clip))
            instrumentation.runOnMainSync {
                preview = StudioPreview(context, scope).apply { player.setVideoSurfaceHolder(holder); load(project) }
            }
            awaitReady()
            instrumentation.runOnMainSync { for (at in 100L..1500L step 100) preview!!.seek(at) }
            awaitReady()
            instrumentation.runOnMainSync {
                preview!!.tick(); assertNull(preview!!.error)
                assertTrue(preview!!.position in 1480..1520)
                assertTrue(preview!!.player.currentPosition in 2980..3020)
                preview!!.update(project.replace(clip.id, clip.edit.copy(startMs = 1000, speed = .5f, rotation = 90)))
            }
            awaitReady()
            instrumentation.runOnMainSync {
                preview!!.tick(); assertNull(preview!!.error)
                assertTrue("Source frame must stay at 3s", preview!!.player.currentPosition in 1980..2020)
                assertTrue("Project time must become 4s at half speed", preview!!.position in 3960..4040)
            }
        } finally {
            instrumentation.runOnMainSync { preview?.release() }; scope.cancel(); images.close(); thread.quitSafely(); file.delete()
        }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 34)
    @Test fun editorIsUsableOnTabletAndPhoneWithoutTouchingThePhysicalDisplay() {
        val file = fixture()
        val thread = HandlerThread("studio-offscreen").apply { start() }
        val bitmap = AtomicReference<Bitmap?>()
        fun reader(width: Int, height: Int): ImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ source -> runCatching { source.acquireLatestImage() }.getOrNull()?.use { image ->
                val plane = image.planes[0]
                val paddedWidth = plane.rowStride / plane.pixelStride
                val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val frame = Bitmap.createBitmap(padded, 0, 0, width, height)
                if (frame !== padded) padded.recycle()
                bitmap.getAndSet(frame)?.recycle()
            } }, Handler(thread.looper))
        }
        var images = reader(1280, 800)
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = manager.createVirtualDisplay("MUGEN Studio QA", 1280, 800, 160, images.surface,
            // Private displays destroy their tasks on removal instead of migrating them to the physical display.
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION)
        val automation = instrumentation.uiAutomation
        val oldInfo = automation.serviceInfo
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        fun nodes(): List<AccessibilityNodeInfo> {
            if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
            val roots = automation.windowsOnAllDisplays.get(display.display.displayId).orEmpty().mapNotNull { it.root }
            val result = ArrayList<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 40 || result.size > 5000) return
                result.add(node)
                for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, depth + 1) }
            }
            roots.forEach { visit(it, 0) }; return result
        }
        fun waitFor(label: String): AccessibilityNodeInfo {
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                nodes().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }?.let { return it }
                Thread.sleep(150)
            }
            error("Missing $label on secondary display. Nodes: ${nodes().mapNotNull { it.text }.joinToString()}")
        }
        fun click(label: String) {
            var node: AccessibilityNodeInfo? = waitFor(label)
            while (node != null && !node.isClickable) node = node.parent
            println("Secondary-display click $label: text=${node?.text}, class=${node?.className}, selected=${node?.isSelected}")
            check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Could not click $label" }
            Thread.sleep(250)
        }
        fun screenshot(name: String) {
            Thread.sleep(700)
            // Copy on the reader thread to avoid recycling a frame while compressing it.
            val done = CountDownLatch(1)
            Handler(thread.looper).post {
                File(context.cacheDir, name).outputStream().use { bitmap.get()!!.compress(Bitmap.CompressFormat.PNG, 100, it) }; done.countDown()
            }
            assertTrue(done.await(10, TimeUnit.SECONDS))
        }
        try {
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(display.display.displayId)
            automation.adoptShellPermissionIdentity("android.permission.ACTIVITY_EMBEDDING")
            context.startActivity(Intent().setComponent(ComponentName(context, StudioQaActivity::class.java))
                .putExtra("fixture", file.path).putExtra("qa_display", display.display.displayId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK), options.toBundle())
            automation.dropShellPermissionIdentity()
            waitFor("MUGEN STUDIO"); waitFor("Recorte de la fuente")
            Thread.sleep(1500)
            val scrubber = nodes().first { it.rangeInfo?.min == 0f && it.rangeInfo?.max == 1f }
            assertTrue(scrubber.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, .25f) }))
            Thread.sleep(400)
            click("✂ Dividir"); waitFor("V1 · 3 Plano 02")
            click("↶ Deshacer")
            assertFalse(nodes().any { it.text?.toString() == "V1 · 3 Plano 02" })
            click("↷ Rehacer"); waitFor("V1 · 3 Plano 02")
            screenshot("studio-tablet.png")
            click("Ampliar visor"); waitFor("Volver a editar"); screenshot("studio-focus.png"); click("Volver a editar")
            click("Imagen"); click("90°"); screenshot("studio-image.png")
            val old = images; images = reader(420, 900); display.surface = images.surface; display.resize(420, 900, 160); old.close()
            Thread.sleep(1200); waitFor("MUGEN STUDIO"); screenshot("studio-phone.png")
            val oldPhone = images; images = reader(860, 440); display.surface = images.surface; display.resize(860, 440, 160); oldPhone.close()
            Thread.sleep(1200); waitFor("MUGEN STUDIO"); screenshot("studio-landscape.png")
            click("‹")
        } catch (failure: Throwable) {
            runCatching { screenshot("studio-failed.png") }
            throw failure
        } finally {
            automation.dropShellPermissionIdentity()
            automation.serviceInfo = oldInfo
            display.release(); images.close(); thread.quitSafely(); bitmap.getAndSet(null)?.recycle(); file.delete()
        }
    }
}
