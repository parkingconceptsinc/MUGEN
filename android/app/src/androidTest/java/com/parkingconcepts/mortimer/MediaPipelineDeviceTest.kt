package com.parkingconcepts.mortimer

import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.view.SurfaceHolder
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.Composition as MediaComposition
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** No Activity, audio focus or user library mutations: safe background checks on a physical device. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class MediaPipelineDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun source(): File = File.createTempFile("studio_test_", ".mp4", context.cacheDir).also { file ->
        instrumentation.context.assets.open("media-studio.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
    }

    @Test fun previewDecodesClippedRotatedResizedVideoWithoutAnActivity() {
        val file = source()
        val decoded = CountDownLatch(3)
        val failure = AtomicReference<PlaybackException?>()
        val duration = java.util.concurrent.atomic.AtomicLong()
        val imageThread = HandlerThread("studio-frames").apply { start() }
        val images = ImageReader.newInstance(320, 180, PixelFormat.RGBA_8888, 3)
        // Effects need an explicit output size, as TextureView supplies in the editor.
        // setVideoSurface alone reports LENGTH_UNSET and cannot exercise that pipeline.
        val holder = java.lang.reflect.Proxy.newProxyInstance(SurfaceHolder::class.java.classLoader,
            arrayOf(SurfaceHolder::class.java)) { _, method, _ ->
            when (method.name) {
                "getSurface" -> images.surface
                "getSurfaceFrame" -> Rect(0, 0, 320, 180)
                "addCallback", "removeCallback" -> null
                else -> error("Unexpected SurfaceHolder call: ${method.name}")
            }
        } as SurfaceHolder
        images.setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.use { decoded.countDown() } }, Handler(imageThread.looper))
        var player: ExoPlayer? = null
        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).build().apply {
                    volume = 0f
                    setVideoSurfaceHolder(holder)
                    val edited = editedVideoItem(Uri.fromFile(file), VideoEditSpec(1000, 3000, 90, true, 720), 4000)
                    setVideoEffects(edited.effects.videoEffects)
                    setMediaItem(edited.mediaItem)
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) { failure.set(error); repeat(3) { decoded.countDown() } }
                        override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) duration.set(this@apply.duration) }
                    })
                    prepare(); play()
                }
            }
            assertTrue("Preview produced no frames", decoded.await(30, TimeUnit.SECONDS))
            assertNull("Preview error: ${failure.get()}", failure.get())
            assertTrue("Preview must expose the 2-second clip", duration.get() in 1900..2100)
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            images.close(); imageThread.quitSafely(); file.delete()
        }
    }

    @Test fun transformerExportsTheSameTrimAndEffectsWithNoAudio() {
        val file = source()
        val output = File(context.cacheDir, "studio_device_export_${System.nanoTime()}.mp4")
        val completed = CountDownLatch(1)
        val failure = AtomicReference<ExportException?>()
        var transformer: Transformer? = null
        try {
            instrumentation.runOnMainSync {
                transformer = Transformer.Builder(context).addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: MediaComposition, exportResult: ExportResult) { completed.countDown() }
                    override fun onError(composition: MediaComposition, exportResult: ExportResult, exportException: ExportException) {
                        failure.set(exportException); completed.countDown()
                    }
                }).build().also { it.start(editedVideoItem(Uri.fromFile(file), VideoEditSpec(1000, 3000, 90, true, 720), 4000), output.path) }
            }
            assertTrue("Export timed out", completed.await(60, TimeUnit.SECONDS))
            assertNull("Export error: ${failure.get()}", failure.get())
            assertTrue(output.length() > 0)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.path)
                assertEquals("Muted export must have only a video track", 1, extractor.trackCount)
                val format = extractor.getTrackFormat(0)
                assertTrue(format.getString(MediaFormat.KEY_MIME)!!.startsWith("video/"))
                assertTrue(format.getLong(MediaFormat.KEY_DURATION) in 1_800_000..2_200_000)
                assertEquals(720, maxOf(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT)))
            } finally { extractor.release() }
        } finally {
            instrumentation.runOnMainSync { transformer?.cancel() }
            output.delete(); file.delete()
        }
    }
}
