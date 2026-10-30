package com.parkingconcepts.mortimer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import java.io.File
import java.io.OutputStream

/** Copy a validated render without consuming it: failed saves can always be retried. */
internal suspend fun copyWaveExport(source: File, output: OutputStream): Long {
    inspectWave(source)
    val job = currentCoroutineContext()
    return source.inputStream().use { it.copyLimitedTo(output, MAX_WAVE_BYTES + 65_536, checkCancelled = { job.ensureActive() }) }
}

internal class WaveExportState {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var pending by mutableStateOf<File?>(null)
    fun reserve(file: File): Boolean {
        if (busy) return false
        if (!file.isFile) { error = "Render this WAV again before saving."; return false }
        pending = file; busy = true; error = null
        return true
    }
    fun retain(message: String) { busy = false; error = message }
    fun complete(file: File): Boolean {
        if (pending?.absolutePath != file.absolutePath) return false
        pending = null; busy = false; error = null
        return true
    }
    lateinit var save: (File, String) -> Unit
    lateinit var retry: () -> Unit
    lateinit var discard: () -> Unit
}

@Composable internal fun rememberWaveExporter(): WaveExportState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { WaveExportState() }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingName by rememberSaveable { mutableStateOf("MUGEN-audio.wav") }
    // A configuration change can recreate the picker callback. Restore the same
    // source rather than silently losing the completed render.
    if (state.pending == null && pendingPath != null) state.pending = File(pendingPath!!).takeIf { it.isFile }
    fun cleanTemporary(file: File?) {
        if (file != null && file.parentFile?.canonicalFile == context.cacheDir.canonicalFile &&
            (file.name.startsWith("voice-render-") || file.name.startsWith("synth-render-"))) file.delete()
    }
    fun clearPending() { state.pending = null; pendingPath = null }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val file = state.pending ?: pendingPath?.let { File(it) }
        if (uri == null) {
            state.retain("Save cancelled. Your WAV is ready to save again.")
        } else if (file == null || !file.isFile) {
            state.busy = false
            state.error = "The temporary WAV is no longer available. Render it again."
            clearPending()
        } else {
            state.busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { output -> copyWaveExport(file, output) }
                            ?: error("Could not open the destination.")
                    }
                    if(state.complete(file)) { pendingPath = null; cleanTemporary(file) }
                } catch (cancelled: CancellationException) {
                    state.retain("Save interrupted. Your WAV is retained; save it again.")
                    throw cancelled
                } catch (failure: Exception) {
                    state.retain((failure.message ?: "Could not save WAV.") + " Your WAV is retained. A partial destination file may need removing.")
                } finally { state.busy = false }
            }
        }
    }
    state.save = { file, name ->
        val previous = state.pending
        if (state.reserve(file)) {
            if (previous != file) cleanTemporary(previous)
            pendingPath = file.absolutePath; pendingName = name
            runCatching { launcher.launch(name) }.onFailure {
                state.retain("No document picker is available. Your WAV is retained for another save attempt.")
            }
        }
    }
    state.retry = { state.pending?.let { state.save(it, pendingName) } }
    state.discard = {
        if (!state.busy) { cleanTemporary(state.pending); clearPending(); state.error = null }
    }
    return state
}

@Composable internal fun WaveExportStatus(state: WaveExportState) {
    if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Choose a destination, then wait for saving to finish.") }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (!state.busy && state.pending?.isFile == true) Row {
        TextButton(onClick = state.retry) { Text("Save WAV again") }
        TextButton(onClick = state.discard) { Text("Discard export") }
    }
}
