package com.parkingconcepts.mortimer

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@UnstableApi
@Composable
internal fun VideoPlayerDialog(
    item: LibraryItem, player: Player, error: String?, onErrorDismiss: () -> Unit,
    canPrevious: Boolean, canNext: Boolean,
    onRetry: () -> Unit, onClose: () -> Unit, onNavigate: (Int) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("video_settings", Context.MODE_PRIVATE) }
    val key = remember(item.uri) { mediaKey(item.uri) }
    var pinned by rememberSaveable { mutableStateOf(true) }
    var fit by rememberSaveable { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var view by remember { mutableStateOf<PlayerView?>(null) }
    var playing by remember { mutableStateOf(player.isPlaying) }
    var position by remember { mutableLongStateOf(player.currentPosition.coerceAtLeast(0)) }
    var duration by remember { mutableLongStateOf(player.duration.coerceAtLeast(0)) }
    var captionPanel by rememberSaveable { mutableStateOf(false) }
    var editor by rememberSaveable { mutableStateOf(false) }
    var captionUri by remember(key) { mutableStateOf(prefs.getString("${key}_subtitle", null)) }
    var captionEnabled by remember(key) { mutableStateOf(prefs.getBoolean("${key}_enabled", true)) }
    var sync by remember(key) { mutableStateOf(runCatching { SubtitleSync(prefs.getLong("${key}_offset", 0), prefs.getString("${key}_scale", "1")!!.toDouble()) }.getOrDefault(SubtitleSync())) }
    var timeline by remember(key) { mutableStateOf<SubtitleTimeline?>(null) }
    var reference by remember(key, captionUri) { mutableStateOf<SubtitleReference?>(null) }
    var subtitleError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val captionPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { persistReadPermission(context, uri); captionUri = uri.toString(); captionEnabled = true; sync = SubtitleSync(); refresh++ }
    }
    LaunchedEffect(captionUri, refresh) {
        timeline = null
        val raw = captionUri ?: return@LaunchedEffect
        try {
            timeline = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(Uri.parse(raw))?.use {
                    val bytes = it.readBytesLimited(MAX_METADATA_BYTES)
                    parseSubtitles(decodeSubtitleBytes(bytes))
                } ?: error("Could not read the subtitle file. Select it again.")
            }
            subtitleError = "${timeline?.cues?.size} phrases loaded."
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { subtitleError = failure.message ?: "Could not load subtitles." }
    }
    LaunchedEffect(key, captionUri, sync, captionEnabled, timeline) {
        prefs.edit().putString("${key}_subtitle", captionUri).putBoolean("${key}_enabled", captionEnabled)
            .putLong("${key}_offset", sync.offsetMs).putString("${key}_scale", sync.scale.toString()).apply()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, timeline != null && captionEnabled).build()
    }
    LaunchedEffect(player, item.uri) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.takeIf { it > 0 } ?: 0L
            playing = player.isPlaying
            delay(150)
        }
    }
    DisposableEffect(player, item.uri) {
        onDispose {
            if (player.currentMediaItem?.mediaId == item.uri) PlaybackPositions.save(context, item.uri, player.currentPosition, player.duration)
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.let { WindowCompat.setDecorFitsSystemWindows(it, false); WindowInsetsControllerCompat(it, it.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            } }
            onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        Column(Modifier.fillMaxSize().background(Color.Black).displayCutoutPadding()) {
            Row(Modifier.fillMaxWidth().background(Color(0xFF141419)).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("Close") }
                TextButton(onClick = { view?.showController() }) { Text("Show controls") }
                TextButton(onClick = { pinned = !pinned; view?.controllerShowTimeoutMs = if (pinned) 0 else 4000; view?.showController() }) { Text(if (pinned) "Controls pinned" else "Auto-hide") }
                TextButton(onClick = { player.pause(); captionPanel = true }) { Text("Subtitles") }
                TextButton(onClick = { player.pause(); editor = true }) { Text("Edit video") }
                TextButton(enabled = canPrevious, onClick = { onNavigate(-1) }) { Text("Previous") }
                TextButton(enabled = canNext, onClick = { onNavigate(1) }) { Text("Next") }
                TextButton(onClick = { fit = when (fit) { AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM; AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL; else -> AspectRatioFrameLayout.RESIZE_MODE_FIT } }) { Text(when(fit) { AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Crop"; AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"; else -> "Fit" }) }
                TextButton(onClick = { player.repeatMode = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE }) { Text("Repeat") }
                listOf(0.5f, 1f, 1.25f, 1.5f, 2f).forEach { rate -> TextButton(onClick = { player.setPlaybackSpeed(rate) }) { Text("$rate×") } }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { ctx -> (LayoutInflater.from(ctx).inflate(R.layout.video_surface, null) as PlayerView).apply {
                        this.player = player; useController = true; controllerAutoShow = true
                        controllerShowTimeoutMs = if (pinned) 0 else 4000; controllerHideOnTouch = !pinned
                        setShowSubtitleButton(true); setShowRewindButton(true); setShowFastForwardButton(true)
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        resizeMode = fit; keepScreenOn = true; view = this; showController()
                    } },
                    update = { it.player = player; it.resizeMode = fit; it.controllerShowTimeoutMs = if(pinned) 0 else 4000; it.controllerHideOnTouch = !pinned; view = it },
                    onRelease = { it.player = null; it.keepScreenOn = false },
                    modifier = Modifier.fillMaxSize(),
                )
                val caption = if (captionEnabled) timeline?.textAt(position, sync).orEmpty() else ""
                if (caption.isNotBlank()) Text(caption, color = Color.White, fontSize = 22.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 18.dp, vertical = if (pinned) 132.dp else 74.dp)
                        .background(Color(0xCC000000)).padding(6.dp), maxLines = 8)
                if (error != null) Surface(Modifier.align(Alignment.Center).padding(24.dp), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(18.dp)) { Text(error); Button(onClick = onRetry) { Text("Retry from current position") }; TextButton(onClick = onErrorDismiss) { Text("Dismiss error") } }
                }
            }
            Text("${item.title} · ${playerTime(position)} / ${playerTime(duration)} · ${if(playing) "Playing" else "Paused"}", color = Color.White,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), fontSize = 12.sp, maxLines = 1)
        }
        if (captionPanel) SubtitleControls(timeline, sync, captionEnabled, position, subtitleError,
            onLoad = { captionPicker.launch(arrayOf("text/*", "application/x-subrip", "application/octet-stream", "*/*")) },
            reference = reference, onReference = { reference = it }, playing = playing,
            onTogglePlayback = { if (player.isPlaying) player.pause() else player.play() },
            onSeek = { player.seekTo((player.currentPosition + it).coerceIn(0, duration.coerceAtLeast(0))) },
            onEnabled = { captionEnabled = it }, onSync = { sync = it }, onClose = { captionPanel = false })
        if (editor) VideoEditorDialog(item, duration, position, onPreview = { player.seekTo(it) }, onClose = { editor = false; view?.showController() })
    }
}
