package com.parkingconcepts.mortimer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** Read-only reader fixture restricted to its own secondary display. Never persists progress/settings. */
class BookQaActivity : ComponentActivity() {
    companion object {
        @Volatile internal var current = java.lang.ref.WeakReference<BookQaActivity>(null)
        @Volatile internal var destroyed = java.util.concurrent.CountDownLatch(0)
    }
    override fun onConfigurationChanged(configuration: android.content.res.Configuration) {
        super.onConfigurationChanged(configuration)
        if (android.os.Build.VERSION.SDK_INT >= 30 && display?.displayId != intent.getIntExtra("qa_display", -1)) finish()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT < 30 || display?.displayId != intent.getIntExtra("qa_display", -1)) return finish()
        val uri = intent.data ?: return finish()
        destroyed = java.util.concurrent.CountDownLatch(1); current = java.lang.ref.WeakReference(this)
        setContent { MortimerTheme("dark") {
            BookReader(uri, "Book", "epub", false, onClose = { finish() }, onChrome = {}, onLightTheme = {}, persistProgress = false)
        } }
    }
    override fun onDestroy() {
        super.onDestroy()
        if (current.get() === this) { current.clear(); destroyed.countDown() }
    }
}
