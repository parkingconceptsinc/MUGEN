package com.parkingconcepts.mortimer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.*
import androidx.media3.transformer.Composition
import kotlinx.coroutines.*
import java.io.File

/** Owned by the whole workspace, so changing inspector tabs never cancels an export. */
@UnstableApi
@Composable
internal fun rememberStudioExport(project: StudioProject?, pausePreview: () -> Unit): StudioExportState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { StudioExportState() }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val file = state.output
        if (uri != null && file != null) {
            state.saving = true
            scope.launch {
                try {
                    require(uri.toString() !in state.snapshot?.clips.orEmpty().map { it.source.uri }) { "Elige un archivo nuevo para conservar los originales." }
                    withContext(Dispatchers.IO) {
                        val job = currentCoroutineContext()
                        context.contentResolver.openOutputStream(uri, "w")?.use { out ->
                            file.inputStream().use { it.copyLimitedTo(out, Long.MAX_VALUE, { job.ensureActive() }) }
                        } ?: error("No se pudo abrir el destino.")
                    }
                    state.message = "MP4 guardado. Los originales se conservan."; file.delete(); state.output = null
                } catch (failure: Exception) {
                    withContext(NonCancellable + Dispatchers.IO) { if (uri.toString() !in state.snapshot?.clips.orEmpty().map { it.source.uri }) runCatching { context.contentResolver.delete(uri, null, null) } }
                    if (failure is CancellationException) throw failure
                    state.message = failure.message ?: "No se pudo guardar el MP4."
                } finally { state.saving = false }
            }
        }
    }
    state.start = {
        val p = project
        if (p != null && !state.busy && !state.saving) {
            pausePreview()
            try {
                p.validate(); state.output?.delete()
                val target = File.createTempFile("studio_export_", ".mp4", context.cacheDir).apply { delete() }
                state.output = target; state.snapshot = p; state.message = null; state.progress = 0
                val engine = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(DefaultEncoderFactory.Builder(context).setEnableFallback(true).build())
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            state.busy = false; state.transformer = null
                            if (target.length() == 0L) { target.delete(); state.output = null; state.message = "El codificador no produjo vídeo." }
                            else { state.progress = 100; state.message = "Montaje preparado. Guarda el MP4 en la ubicación que prefieras." }
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            state.busy = false; state.transformer = null; target.delete(); state.output = null
                            state.message = "Error ${exportException.errorCodeName}. Prueba 720p o un montaje más corto."
                        }
                    }).build()
                state.transformer = engine; state.busy = true
                engine.start(studioComposition(p), target.path)
            } catch (failure: Exception) { state.cancel(); state.message = failure.message ?: "No se pudo exportar." }
        }
    }
    state.save = { save.launch((state.snapshot?.name ?: "MUGEN") + "-montaje.mp4") }
    DisposableEffect(state) { onDispose { state.cancel() } }
    LaunchedEffect(state.busy) {
        val progress = ProgressHolder()
        while (state.busy) { if (state.transformer?.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) state.progress = progress.progress; delay(250) }
    }
    return state
}
@UnstableApi
internal class StudioExportState {
    var busy by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
    var message by mutableStateOf<String?>(null)
    var output by mutableStateOf<File?>(null)
    var snapshot: StudioProject? = null
    var transformer: Transformer? = null
    var start: () -> Unit = {}
    var save: () -> Unit = {}
    fun cancel() { transformer?.cancel(); transformer = null; busy = false; output?.delete(); output = null }
}

@UnstableApi
@Composable
internal fun StudioExportPanel(project: StudioProject, state: StudioExportState, onChange: (StudioProject) -> Unit) {
    Text("Exportar montaje", style = MaterialTheme.typography.titleMedium)
    Text("${project.clips.size} clips · ${studioTime(project.duration)} · MP4 H.264 / AAC")
    Text("Lienzo común · las barras preservan la imagen completa")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("source" to "Fuente", "16:9" to "16:9", "9:16" to "9:16", "1:1" to "1:1").forEach { (id, label) ->
            FilterChip(selected = project.aspect == id, enabled = !state.busy && !state.saving, onClick = { onChange(project.copy(aspect = id)) }, label = { Text(label) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(720, 1080).forEach { height -> FilterChip(selected = project.height == height, enabled = !state.busy && !state.saving,
            onClick = { onChange(project.copy(height = height)) }, label = { Text("${height}p") }) }
    }
    Button(enabled = !state.busy && !state.saving, onClick = state.start) { Text("Crear MP4") }
    if (state.busy) { LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth()); Text("Procesando ${state.progress}%"); TextButton(onClick = { state.cancel(); state.message = "Exportación cancelada." }) { Text("Cancelar exportación") } }
    state.message?.let { Text(it) }
    if (state.output?.isFile == true && !state.busy) {
        if (state.snapshot != project) Text("El proyecto cambió. Crea el MP4 de nuevo para aplicar los cambios.")
        Button(enabled = !state.saving && state.snapshot == project, onClick = state.save) { Text(if (state.saving) "Guardando…" else "Guardar MP4…") }
    }
    Text("Mantén Editor abierto durante el procesamiento. Al salir se cancela la exportación; el proyecto se conserva.", style = MaterialTheme.typography.bodySmall)
}
