package com.parkingconcepts.mortimer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SaveSubtitleButton(timeline: SubtitleTimeline?, sync: SubtitleSync, name: String, originals: Set<Uri>,
    clipStartMs: Long = 0, clipEndMs: Long = Long.MAX_VALUE) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        val text = pending
        pending = null
        if (uri != null && text != null) {
            saving = true
            scope.launch {
                try {
                    require(uri !in originals) { "Choose a new subtitle file." }
                    withContext(Dispatchers.IO) {
                        val job = currentCoroutineContext()
                        context.contentResolver.openOutputStream(uri, "w")?.use { out ->
                            text.byteInputStream(Charsets.UTF_8).use { it.copyLimitedTo(out, Long.MAX_VALUE, { job.ensureActive() }) }
                        } ?: error("Could not open the destination.")
                    }
                    message = "Corrected subtitles saved."
                } catch (failure: Exception) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        if (uri !in originals) runCatching { context.contentResolver.delete(uri, null, null) }
                    }
                    if (failure is CancellationException) throw failure
                    message = failure.message ?: "Could not save subtitles."
                } finally { saving = false }
            }
        }
    }
    TextButton(enabled = timeline != null && !saving && pending == null && clipEndMs > clipStartMs, onClick = {
        val text = timeline?.toSrt(sync, clipStartMs, clipEndMs).orEmpty()
        if (text.isBlank()) message = "No subtitles overlap this clip."
        else { pending = text; picker.launch(name.substringBeforeLast('.') + "-corrected.srt") }
    }) { Text(if (saving) "Saving subtitles…" else "Save corrected SRT") }
    message?.let { Text(it) }
}
