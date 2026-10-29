package com.parkingconcepts.mortimer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Narrow external entry point; the reader itself stays private. No automatic library writes. */
class OpenEpubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val input = intent
        if (input.action != Intent.ACTION_VIEW || input.data?.scheme != "content") return finish()
        lifecycleScope.launch {
            val title = withContext(Dispatchers.IO) { displayName(this@OpenEpubActivity, input.data!!, "Book.epub") }
            if (!isFinishing) {
                try { startActivity(epubReaderIntent(this@OpenEpubActivity, input, title)) }
                finally { finish() }
            }
        }
    }
}

internal fun epubReaderIntent(context: Context, input: Intent, title: String): Intent {
    require(input.action == Intent.ACTION_VIEW && input.data?.scheme == "content") { "Open an EPUB document from Files." }
    return Intent(context, EpubReaderActivity::class.java).apply {
        data = input.data
        clipData = android.content.ClipData.newRawUri("EPUB", input.data)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putExtra(EpubReaderActivity.EXTRA_TITLE, title)
        putExtra(EpubReaderActivity.EXTRA_FORMAT, "epub")
    }
}
