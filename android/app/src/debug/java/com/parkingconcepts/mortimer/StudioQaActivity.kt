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
    override fun onConfigurationChanged(configuration: android.content.res.Configuration) {
        super.onConfigurationChanged(configuration)
        if (android.os.Build.VERSION.SDK_INT >= 30 && display?.displayId != intent.getIntExtra("qa_display", -1)) finish()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT < 30 || display?.displayId != intent.getIntExtra("qa_display", -1)) return finish()
        val path = intent.getStringExtra("fixture") ?: return finish()
        val source = readStudioSource(this, android.net.Uri.fromFile(java.io.File(path)), "Plano 01")
        val project = StudioProject(name = "Montaje de prueba", clips = listOf(
            StudioClip(source = source, edit = VideoEditSpec(0, 2000)),
            StudioClip(source = source.copy(title = "Plano 02"), edit = VideoEditSpec(2000, 4000))))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        setContent { VideoStudioScreen(emptyList(), initialProject = project, persistProject = false, onExit = { finish() }) }
    }
}
