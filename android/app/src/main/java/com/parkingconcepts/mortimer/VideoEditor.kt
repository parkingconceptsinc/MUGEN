package com.parkingconcepts.mortimer

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.*
import androidx.media3.transformer.Composition as MediaComposition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@UnstableApi
internal fun videoEditEffects(spec: VideoEditSpec): List<Effect> = buildList {
    if (spec.rotation != 0) add(ScaleAndRotateTransformation.Builder().setRotationDegrees(spec.rotation.toFloat()).build())
    if (spec.height != 0) add(Presentation.createForHeight(spec.height))
}

@UnstableApi
internal fun editedVideoItem(uri: Uri, spec: VideoEditSpec, duration: Long): EditedMediaItem {
    spec.validate(duration)
    val media = MediaItem.Builder().setUri(uri).setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder().setStartPositionMs(spec.startMs).setEndPositionMs(spec.endMs).build()
    ).build()
    return EditedMediaItem.Builder(media).setRemoveAudio(spec.mute)
        .setEffects(Effects(emptyList(), videoEditEffects(spec))).build()
}

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoEditorDialog(item: LibraryItem, duration: Long, position: Long, subtitles: SubtitleTimeline?, sync: SubtitleSync,
    subtitleUri: Uri?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft = remember(item.uri, duration) { VideoEditDrafts.load(context, item.uri, duration) }
    var range by remember { mutableStateOf((draft?.startMs ?: 0).toFloat()..(draft?.endMs ?: duration.coerceAtLeast(1)).toFloat()) }
    var rotation by remember { mutableIntStateOf(draft?.rotation ?: 0) }
    var mute by remember { mutableStateOf(draft?.mute ?: false) }
    var height by remember { mutableIntStateOf(draft?.height ?: 0) }
    val spec = VideoEditSpec(range.start.toLong(), range.endInclusive.toLong().coerceAtMost(duration), rotation, mute, height)
    val validSpec = runCatching { spec.validate(duration) }.isSuccess
    val latestSpec by rememberUpdatedState(spec)
    val preview = remember(item.uri) { ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
        setHandleAudioBecomingNoisy(true)
        repeatMode = Player.REPEAT_MODE_ONE
    } }
    var appliedPreview by remember { mutableStateOf<VideoEditSpec?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var transformer by remember { mutableStateOf<Transformer?>(null) }
    var output by remember { mutableStateOf<File?>(null) }
    var exportedSpec by remember { mutableStateOf<VideoEditSpec?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(spec) {
        delay(500)
        if (runCatching { spec.validate(duration) }.isSuccess) VideoEditDrafts.save(context, item.uri, duration, spec)
    }
    DisposableEffect(preview, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) preview.pause() }
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { message = "Preview failed: ${error.errorCodeName}. You can retry or export another format." }
        }
        lifecycle.addObserver(observer); preview.addListener(listener)
        onDispose {
            runCatching { VideoEditDrafts.save(context, item.uri, duration, latestSpec) }
            lifecycle.removeObserver(observer); preview.release()
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { destination ->
        val file = output
        if (destination != null && file != null) {
            saving = true
            scope.launch {
                try {
                    require(destination != item.androidUri) { "Choose a new file; the original video is preserved." }
                    withContext(Dispatchers.IO) {
                        val job = currentCoroutineContext()
                        context.contentResolver.openOutputStream(destination, "w")?.use { out ->
                            file.inputStream().use { it.copyLimitedTo(out, Long.MAX_VALUE, { job.ensureActive() }) }
                        } ?: error("Could not open the selected destination.")
                    }
                    message = "Export saved. Your original video was preserved."
                    file.delete(); output = null; exportedSpec = null
                } catch (failure: Exception) {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        if (destination != item.androidUri) runCatching { context.contentResolver.delete(destination, null, null) }
                    }
                    if (failure is CancellationException) throw failure
                    message = failure.message ?: "Could not save the export. Choose another destination."
                } finally { saving = false }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { transformer?.cancel(); output?.delete() }
    }
    LaunchedEffect(busy) {
        val holder = ProgressHolder()
        while (busy) {
            if (transformer?.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress = holder.progress
            delay(300)
        }
    }
    Dialog(onDismissRequest = { if (!saving) { transformer?.cancel(); busy = false; onClose() } },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp).heightIn(max = 650.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Video editor", style = MaterialTheme.typography.titleLarge)
                Text("Trim, rotate, mute and resize. Exports a new MP4; the original stays intact.")
                AndroidView(factory = { ctx -> (android.view.LayoutInflater.from(ctx).inflate(R.layout.video_surface, android.widget.FrameLayout(ctx), false) as PlayerView).apply {
                    player = preview; controllerShowTimeoutMs = 0; controllerHideOnTouch = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                } }, onRelease = { it.player = null }, modifier = Modifier.fillMaxWidth().height(200.dp))
                Button(enabled = !busy && !saving && validSpec, onClick = {
                    try {
                        val edited = editedVideoItem(item.androidUri, spec, duration)
                        preview.pause(); preview.setVideoEffects(edited.effects.videoEffects)
                        preview.volume = if (spec.mute) 0f else 1f
                        preview.setMediaItem(edited.mediaItem); preview.prepare(); preview.play()
                        appliedPreview = spec; message = null
                    } catch (failure: Exception) { message = failure.message }
                }) { Text(if (appliedPreview == spec) "Restart preview" else "Preview these edits") }
                Text(if (validSpec) "Draft saved automatically · Preview loops the selected range" else "Select at least half a second inside the video.", style = MaterialTheme.typography.bodySmall)
                Text("${playerTime(range.start.toLong())} → ${playerTime(range.endInclusive.toLong())}")
                RangeSlider(value = range, onValueChange = { range = it }, valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = !busy && !saving && duration > 0)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(enabled = !busy && !saving, onClick = { val head = appliedPreview?.let { it.startMs + preview.currentPosition } ?: position; range = head.coerceIn(0, range.endInclusive.toLong()).toFloat()..range.endInclusive }) { Text("Start at playhead") }
                    TextButton(enabled = !busy && !saving, onClick = { val head = appliedPreview?.let { it.startMs + preview.currentPosition } ?: position; range = range.start..head.coerceIn(range.start.toLong(), duration).toFloat() }) { Text("End at playhead") }
                }
                TextButton(enabled = !busy && !saving, onClick = { range = 0f..duration.coerceAtLeast(1).toFloat(); rotation = 0; mute = false; height = 0; preview.pause(); appliedPreview = null }) { Text("Reset edits") }
                TextButton(enabled = !busy && !saving, onClick = { rotation = (rotation + 90) % 360 }) { Text("Rotation: $rotation°") }
                Row { Checkbox(checked = mute, onCheckedChange = { mute = it }, enabled = !busy && !saving); Text("Remove audio", Modifier.padding(top = 12.dp)) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "Original", 720 to "720p", 1080 to "1080p").forEach { (size, label) ->
                        FilterChip(selected = size == height, onClick = { height = size }, label = { Text(label) }, enabled = !busy && !saving)
                    }
                }
                message?.let { Text(it) }
                if (subtitles != null) {
                    Text("Clip subtitles include your timing correction and start at zero with the trimmed video.", style = MaterialTheme.typography.bodySmall)
                    SaveSubtitleButton(subtitles, sync, item.title.substringBeforeLast('.') + "-clip.srt", setOfNotNull(item.androidUri, subtitleUri), spec.startMs, spec.endMs)
                }
                if (busy) { LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth()); Text("Exporting · $progress%") }
                Button(enabled = !busy && !saving && validSpec, onClick = {
                    try {
                        spec.validate(duration)
                        preview.pause()
                        exportedSpec = null
                        output?.delete()
                        val target = File.createTempFile("video_export_", ".mp4", context.cacheDir)
                        target.delete(); output = target
                        val listener = object : Transformer.Listener {
                            override fun onCompleted(composition: MediaComposition, exportResult: ExportResult) {
                                busy = false; transformer = null
                                if (!target.isFile || target.length() == 0L) { message = "The encoder produced no video."; target.delete(); output = null }
                                else { exportedSpec = spec; progress = 100; message = "Export ready. Tap Save MP4 to choose its location." }
                            }
                            override fun onError(composition: MediaComposition, exportResult: ExportResult, exportException: ExportException) {
                                busy = false; transformer = null; target.delete(); output = null
                                message = "Export failed: ${exportException.errorCodeName}. Try Original size or a shorter clip."
                            }
                        }
                        val engine = Transformer.Builder(context)
                            .setEncoderFactory(DefaultEncoderFactory.Builder(context).setEnableFallback(true).build())
                            .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                            .addListener(listener).build()
                        transformer = engine; progress = 0; busy = true; message = null
                        engine.start(editedVideoItem(item.androidUri, spec, duration), target.path)
                    } catch (failure: Exception) { transformer?.cancel(); transformer = null; busy = false; output?.delete(); output = null; message = failure.message }
                }) { Text("Create MP4") }
                if (output?.isFile == true && !busy) {
                    if (exportedSpec != spec) Text("Edits changed. Create MP4 again to include them.")
                    Button(enabled = !saving && exportedSpec == spec, onClick = { exporter.launch(item.title.substringBeforeLast('.') + "-edited.mp4") }) { Text(if (saving) "Saving…" else "Save MP4") }
                }
                TextButton(enabled = !saving, onClick = { transformer?.cancel(); transformer = null; busy = false; onClose() }) { Text(if (busy) "Cancel export and close" else "Close editor") }
                Text("Keep this screen open during export. Closing it cancels the operation.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
