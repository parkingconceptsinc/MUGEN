package com.parkingconcepts.mortimer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Native LibVLC video player. SAF URIs are opened as file descriptors so an
 * opaque content:// document ID is never mistaken for a filesystem path.
 * The original persisted SAF permissions remain owned by the main app.
 *
 * Audio/Android Auto deliberately continue to share one Media3 service;
 * opening this screen pauses that service to avoid competing audio streams.
 */
class VlcVideoActivity : ComponentActivity() {
    companion object {
        const val EXTRA_URI = "mugen.vlc.uri"
        private const val EXTRA_TITLE = "mugen.vlc.title"

        fun intent(context: Context, item: LibraryItem): Intent =
            Intent(context, VlcVideoActivity::class.java)
                .putExtra(EXTRA_URI, item.uri)
                .putExtra(EXTRA_TITLE, item.title)
    }

    private var engine: LibVLC? = null
    private var video: MediaPlayer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var sourceId = ""
    private var resumeAtMs = 0L
    private var playing by mutableStateOf(false)
    private var status by mutableStateOf("Loading video…")
    private var failure by mutableStateOf<String?>(null)
    private var elapsedMs by mutableLongStateOf(0L)
    private var durationMs by mutableLongStateOf(0L)
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            video?.let { player ->
                elapsedMs = player.time.coerceAtLeast(0L)
                durationMs = player.length.coerceAtLeast(0L)
            }
            handler.postDelayed(this, 350L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourceId = intent.getStringExtra(EXTRA_URI).orEmpty()
        if (sourceId.isBlank()) { finish(); return }
        val source = Uri.parse(sourceId)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Video" }
        resumeAtMs = PlaybackPositions.get(this, sourceId)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.post(ticker)

        setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(
                    factory = { context ->
                        VLCVideoLayout(context).also { startVideo(it, source) }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color(0xE617171D)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(failure ?: status, color = if (failure != null) Color(0xFFFFAA88) else Color.LightGray)
                    Slider(
                        value = if (durationMs > 0) (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f,
                        onValueChange = { fraction ->
                            if (durationMs > 0) {
                                val at = (fraction * durationMs).toLong()
                                elapsedMs = at
                                runCatching { video?.time = at }
                            }
                        },
                        enabled = durationMs > 0,
                    )
                    Text("${playerTime(elapsedMs)} / ${playerTime(durationMs)}", color = Color.LightGray)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { finish() }) { Text("Back") }
                        Button(onClick = {
                            video?.let { player ->
                                if (player.isPlaying) player.pause()
                                else if (failure == null) player.play()
                            }
                        }, enabled = failure == null) { Text(if (playing) "Pause" else "Play") }
                        Button(onClick = {
                            // Safely return to the existing Media3 video dialogue.
                            setResult(Activity.RESULT_FIRST_USER, Intent().putExtra(EXTRA_URI, sourceId))
                            finish()
                        }) { Text("Use Media3") }
                    }
                }
            }
        }
    }

    private fun startVideo(surface: VLCVideoLayout, source: Uri) {
        if (video != null || failure != null) return
        runCatching {
            val libvlc = LibVLC(this, arrayListOf("--no-video-title-show"))
            engine = libvlc
            val player = MediaPlayer(libvlc)
            video = player
            descriptor = if (source.scheme == "content") contentResolver.openFileDescriptor(source, "r") else null
            val media = descriptor?.let { Media(libvlc, it.fileDescriptor) } ?: Media(libvlc, source)
            try {
                media.setHWDecoderEnabled(true, false)
                player.setMedia(media)
            } finally {
                media.release()
            }
            player.setEventListener { event ->
                runOnUiThread {
                    when (event.type) {
                        MediaPlayer.Event.Playing -> {
                            playing = true
                            status = "Playing with VLC"
                            if (resumeAtMs > 0) {
                                runCatching { player.time = resumeAtMs }
                                resumeAtMs = 0L
                            }
                        }
                        MediaPlayer.Event.Paused -> { playing = false; status = "Paused" }
                        MediaPlayer.Event.EndReached -> { playing = false; status = "Finished"; elapsedMs = 0L }
                        MediaPlayer.Event.EncounteredError -> {
                            playing = false
                            failure = "VLC could not decode or access this file. Try Media3."
                        }
                    }
                }
            }
            player.attachViews(surface, null, false, false)
            player.play()
        }.onFailure { problem ->
            playing = false
            failure = "Unable to open video in VLC: ${problem.message ?: problem.javaClass.simpleName}"
        }
    }

    override fun onPause() {
        if (video?.isPlaying == true) runCatching { video?.pause() }
        savePosition()
        super.onPause()
    }

    private fun savePosition() {
        if (sourceId.isNotBlank()) PlaybackPositions.save(this, sourceId,
            video?.time?.coerceAtLeast(0L) ?: elapsedMs, video?.length?.coerceAtLeast(0L) ?: durationMs)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        savePosition()
        video?.let { player ->
            runCatching { player.stop() }
            runCatching { player.detachViews() }
            runCatching { player.release() }
        }
        video = null
        descriptor?.close(); descriptor = null
        engine?.release(); engine = null
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}
