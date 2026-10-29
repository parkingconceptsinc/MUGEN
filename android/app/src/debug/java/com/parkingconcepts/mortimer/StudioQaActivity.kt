package com.parkingconcepts.mortimer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi

/** Non-exported debug fixture for secondary-display QA; it never loads or saves a user's library/projects. */
@UnstableApi
class StudioQaActivity : ComponentActivity() {
    companion object {
        @Volatile internal var current = java.lang.ref.WeakReference<StudioQaActivity>(null)
        @Volatile internal var destroyed = java.util.concurrent.CountDownLatch(0)
    }
    override fun onConfigurationChanged(configuration: android.content.res.Configuration) {
        super.onConfigurationChanged(configuration)
        if (android.os.Build.VERSION.SDK_INT >= 30 && display?.displayId != intent.getIntExtra("qa_display", -1)) finish()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT < 30 || display?.displayId != intent.getIntExtra("qa_display", -1)) return finish()
        destroyed = java.util.concurrent.CountDownLatch(1); current = java.lang.ref.WeakReference(this)
        val path = intent.getStringExtra("fixture") ?: return finish()
        val fileUri = android.net.Uri.fromFile(java.io.File(path))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        if (intent.getBooleanExtra("import_mode", false)) {
            val available = LibraryItem(fileUri.toString(), "Import fixture", "videos", "video")
            val unavailable = LibraryItem("content://com.android.externalstorage.documents/tree/primary%3AMUGEN-QA-NoGrant/document/primary%3AMUGEN-QA-NoGrant%2Fmissing.mp4", "Unavailable video", "videos", "video")
            setContent { VideoStudioScreen(listOf(unavailable, available), persistProject = false, onExit = { finish() }) }
        } else {
            val source = readStudioSource(this, fileUri, "Shot 01")
            val project = StudioProject(name = "Test project", clips = listOf(
                StudioClip(source = source, edit = VideoEditSpec(0, 2000)),
                StudioClip(source = source.copy(title = "Shot 02"), edit = VideoEditSpec(2000, 4000))))
            setContent { VideoStudioScreen(emptyList(), initialProject = project, persistProject = false, onExit = { finish() }) }
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        if (current.get() === this) { current.clear(); destroyed.countDown() }
    }
}
