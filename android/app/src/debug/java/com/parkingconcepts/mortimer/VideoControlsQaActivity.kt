package com.parkingconcepts.mortimer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/** Synthetic, muted playback on an explicitly requested secondary display only. */
@UnstableApi
class VideoControlsQaActivity : ComponentActivity() {
    companion object {
        @Volatile internal var current = java.lang.ref.WeakReference<VideoControlsQaActivity>(null)
        @Volatile internal var destroyed = java.util.concurrent.CountDownLatch(0)
    }
    internal var player: ExoPlayer? = null
        private set
    override fun onConfigurationChanged(configuration: android.content.res.Configuration) {
        super.onConfigurationChanged(configuration)
        if (android.os.Build.VERSION.SDK_INT >= 30 && display?.displayId != intent.getIntExtra("qa_display", -1)) finish()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT < 30 || display?.displayId != intent.getIntExtra("qa_display", -1)) return finish()
        val file = intent.getStringExtra("fixture")?.let { java.io.File(it) } ?: return finish()
        require(file.canonicalFile.parentFile == cacheDir.canonicalFile)
        current = java.lang.ref.WeakReference(this); destroyed = java.util.concurrent.CountDownLatch(1)
        val uri = android.net.Uri.fromFile(file)
        val item = LibraryItem(uri.toString(), "Synthetic video", "videos", "video")
        val playback = ExoPlayer.Builder(this).build().apply {
            volume = 0f
            setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, false)
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.Builder().setUri(uri).setMediaId(item.uri).build())
            prepare(); play()
        }
        player = playback
        setContent { MortimerTheme("dark") {
            VideoPlayerDialog(item, playback, null, {}, false, false, onRetry = {}, onClose = { finish() },
                onNavigate = {}, onEdit = { _, _ -> }, persistSettings = false)
        } }
    }
    override fun onDestroy() {
        super.onDestroy()
        player?.release(); player = null
        if (current.get() === this) { current.clear(); destroyed.countDown() }
    }
}
