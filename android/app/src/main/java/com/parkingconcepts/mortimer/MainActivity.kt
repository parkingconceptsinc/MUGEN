package com.parkingconcepts.mortimer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.media3.common.util.UnstableApi
class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val themePref = mutableStateOf("system")
    private val palettePref = mutableStateOf("ember")
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        themePref.value = ThemePref.get(this)
        palettePref.value = ThemePref.getPalette(this)
        setContent {
            MortimerTheme(themePref.value, palettePref.value) {
                Surface(Modifier.fillMaxSize(), color = LocalMortimerColors.current.bg) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Starting MUGEN…", color = LocalMortimerColors.current.text)
                    }
                }
            }
        }
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { future ->
            future.addListener({
                runCatching { future.get() }.onSuccess { controller ->
                    if (!isFinishing && !isDestroyed) {
                        setContent {
                            MortimerTheme(themePref.value, palettePref.value) {
                                val dark = LocalMortimerColors.current.dark
                                SideEffect {
                                    WindowCompat.getInsetsController(window, window.decorView).apply {
                                        isAppearanceLightStatusBars = !dark
                                        isAppearanceLightNavigationBars = !dark
                                    }
                                }
                                MortimerApp(
                                    player = controller,
                                    themePref = themePref.value,
                                    onTheme = { themePref.value = it; ThemePref.set(this, it) },
                                    palettePref = palettePref.value,
                                    onPalette = { palettePref.value = it; ThemePref.setPalette(this, it) },
                                    resumeTick = resumeTick.intValue,
                                    openSpotify = { launchSpotify() },
                                    openExternal = { uri, mime -> openExternal(uri, mime) },
                                )
                            }
                        }
                    }
                }.onFailure {
                    if (!isFinishing && !isDestroyed) {
                        setContent {
                            MortimerTheme(themePref.value, palettePref.value) {
                                Surface(Modifier.fillMaxSize(), color = LocalMortimerColors.current.bg) {
                                    Text("The player could not start. Close and reopen MUGEN.", color = LocalMortimerColors.current.text, modifier = Modifier.padding(24.dp))
                                }
                            }
                        }
                    }
                }
            }, MoreExecutors.directExecutor())
        }
    }

    override fun onResume() {
        super.onResume()
        // Readers save progress while this activity is paused; reload the library when coming back.
        resumeTick.intValue++
    }

    private fun launchSpotify() {
        val spotify = packageManager.getLaunchIntentForPackage("com.spotify.music")
        if (spotify != null) startActivity(spotify)
        else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/")))
    }

    private fun openExternal(uri: Uri, mime: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onDestroy()
    }
}

private data class Section(val id: String, val label: String, val icon: String)

private val SECTIONS = listOf(
    Section("home", "Home", "⌂"),
    Section("music", "Music", "♫"),
    Section("playing", "Now Playing", "◉"),
    Section("videos", "Videos", "▶"),
    Section("books", "Books", "▤"),
    Section("comics", "Comics", "▧"),
    Section("photos", "Photos", "▦"),
    Section("eq", "Equalizer", "≋"),
    Section("services", "Services", "♪"),
    Section("settings", "Settings", "⚙"),
)
private val TABS = listOf("home", "music", "books", "comics", "photos")

/**
 * SAF content URIs often end in an opaque document ID instead of a filename.
 * Explicitly set the container MIME type for video files so Media3 does not
 * have to guess the container from a URI that has no extension.
 */
private fun videoMimeType(name: String): String? = when (extensionOf(name)) {
    "mp4" -> "video/mp4"
    "m4v" -> "video/x-m4v"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "mov" -> "video/quicktime"
    "avi" -> "video/avi"
    "3gp", "3g2" -> "video/3gpp"
    "ts", "m2ts", "mts" -> "video/mp2t"
    "mpg", "mpeg" -> "video/mpeg"
    "wmv" -> "video/x-ms-wmv"
    "flv" -> "video/x-flv"
    "ogv" -> "video/ogg"
    else -> null
}

private fun mediaItemFor(item: LibraryItem): MediaItem = MediaItem.Builder()
    .setUri(item.androidUri)
    .setMediaId(item.uri)
    .apply {
        if (item.kind == "videos" || item.format == "video") {
            videoMimeType(item.title)?.let { setMimeType(it) }
        }
    }
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(item.artist.ifBlank { null })
            .setAlbumTitle(item.album.ifBlank { null })
            .build()
    )
    .build()

/** Reads title, artist and album tags so the music list shows real names. Runs on a background thread. */
private fun withAudioTags(context: Context, item: LibraryItem): LibraryItem {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, item.androidUri)
        val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()
        val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()
            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.trim()
        val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()
        item.copy(
            title = title?.takeIf { it.isNotBlank() } ?: item.title.substringBeforeLast('.'),
            artist = artist.orEmpty(),
            album = album.orEmpty(),
        )
    } catch (_: Exception) {
        item.copy(title = item.title.substringBeforeLast('.'))
    } finally {
        runCatching { retriever.release() }
    }
}

private fun mimeForItem(item: LibraryItem) = when (item.format) {
    "pdf" -> "application/pdf"
    "epub" -> "application/epub+zip"
    "txt" -> "text/plain"
    "audio" -> "audio/*"
    "video" -> "video/*"
    else -> "*/*"
}

private fun formatTime(ms: Long): String {
    if (ms < 0) return "0:00"
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@androidx.media3.common.util.UnstableApi
@Composable
private fun MortimerApp(
    player: Player,
    themePref: String,
    onTheme: (String) -> Unit,
    palettePref: String,
    onPalette: (String) -> Unit,
    resumeTick: Int,
    openSpotify: () -> Unit,
    openExternal: (Uri, String) -> Unit,
) {
    val context = LocalContext.current
    val c = LocalMortimerColors.current
    val scope = rememberCoroutineScope()
    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) audioPermissionLauncher.launch(permission)
    }

    var library by remember { mutableStateOf(LibraryStore.load(context)) }
    LaunchedEffect(resumeTick) { library = withContext(Dispatchers.IO) { LibraryStore.load(context) } }
    var importing by remember { mutableStateOf<String?>(null) }

    fun commit(next: List<LibraryItem>) {
        library = next
        scope.launch(Dispatchers.IO) { runCatching { LibraryStore.save(context, next) } }
    }

    fun addItems(found: List<LibraryItem>, kind: String) {
        scope.launch {
            importing = "Adding ${found.size} files…"
            val known = library.map { it.uri }.toSet()
            val fresh = found.filter { it.uri !in known }
            val tagged = withContext(Dispatchers.IO) { if (kind == "audio") fresh.map { withAudioTags(context, it) } else fresh }
            val next = withContext(Dispatchers.IO) { LibraryStore.update(context) { list -> list + tagged.filter { t -> list.none { it.uri == t.uri } } } }
            library = next
            importing = if (fresh.isEmpty() && found.isNotEmpty()) "Those files are already in your library." else if (found.isEmpty()) "No compatible files found." else null
            if (importing != null) { delay(2500); importing = null }
        }
    }

    var pickKind by remember { mutableStateOf("audio") }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val kind = pickKind
            scope.launch {
                importing = "Reading files…"
                val found = withContext(Dispatchers.IO) { itemsFromUris(context, uris, kind) }
                addItems(found, kind)
            }
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            val kind = pickKind
            scope.launch {
                importing = "Scanning folder…"
                val found = withContext(Dispatchers.IO) { runCatching { itemsFromTree(context, tree, kind) }.getOrDefault(emptyList()) }
                addItems(found, kind)
            }
        }
    }
    fun addFiles(kind: String) {
        pickKind = kind
        filePicker.launch(
            when (kind) {
                "audio" -> arrayOf("audio/*")
                "videos" -> arrayOf("video/*")
                "comics" -> arrayOf("application/zip", "application/x-cbz", "application/vnd.comicbook+zip", "application/x-cbr", "application/vnd.comicbook-rar", "application/x-rar-compressed", "application/pdf", "application/octet-stream", "*/*")
                else -> arrayOf("application/epub+zip", "application/pdf", "text/plain", "application/octet-stream", "*/*")
            }
        )
    }
    fun addFolder(kind: String) { pickKind = kind; folderPicker.launch(null) }

    var section by rememberSaveable { mutableStateOf("home") }
    var currentUri by remember { mutableStateOf(player.currentMediaItem?.mediaId) }
    var currentTitle by remember { mutableStateOf(player.currentMediaItem?.mediaMetadata?.title?.toString()) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var queueVersion by remember { mutableIntStateOf(0) }
    var videoOpen by remember { mutableStateOf<LibraryItem?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlayerError(error: PlaybackException) {
                playbackError = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "This device does not support this format or codec."
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "Cannot access this file. Import it again."
                    else -> "Could not play this file. Code: " + error.errorCodeName
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                playbackError = null
                currentUri = mediaItem?.mediaId ?: mediaItem?.localConfiguration?.uri?.toString()
                currentTitle = mediaItem?.mediaMetadata?.title?.toString()
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { queueVersion++ }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { queueVersion++ }
            override fun onRepeatModeChanged(repeatMode: Int) { queueVersion++ }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    fun playList(list: List<LibraryItem>, index: Int) {
        if (list.isEmpty()) return
        player.setMediaItems(list.map { mediaItemFor(it) }, index.coerceIn(0, list.lastIndex), 0L)
        player.prepare()
        player.play()
    }

    val current = library.firstOrNull { it.uri == currentUri }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    BackHandler(enabled = section != "home") { section = "home" }

    @Composable
    fun Body(modifier: Modifier, wide: Boolean, compact: Boolean) {
        Box(modifier.background(c.bg), contentAlignment = Alignment.TopCenter) {
            if (c.dark && (section == "home" || section == "music")) {
                Image(
                    painter = painterResource(R.drawable.music_home_bg), contentDescription = null,
                    contentScale = ContentScale.Crop, alpha = 0.40f, modifier = Modifier.fillMaxSize(),
                )
            }
            // Keep the same visual hierarchy while preventing tablet layouts from stretching
            // edge-to-edge. Very narrow phones get smaller gutters; landscape phones stay compact.
            Column(
                Modifier
                    .fillMaxSize()
                    .widthIn(max = if (wide) 1120.dp else 760.dp)
                    .statusBarsPadding()
                    .padding(horizontal = if (compact) 12.dp else 18.dp)
            ) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!wide) {
                        Box(Modifier.size(40.dp).clip(CircleShape).clickable { scope.launch { drawerState.open() } }, contentAlignment = Alignment.Center) {
                            Text("☰", color = c.text, fontSize = 20.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("MUGEN PLAYER", color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Text(SECTIONS.first { it.id == section }.label, color = c.text, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
                importing?.let {
                    Text(it, color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(12.dp))
                val content = Modifier.weight(1f)
                when (section) {
                    "home" -> HomeSection(library, content, onGo = { section = it }, openExternal = { openExternal(it.androidUri, mimeForItem(it)) })
                    "music" -> MusicSection(library, currentUri, content, onAddFiles = { addFiles("audio") }, onAddFolder = { addFolder("audio") },
                        onPlay = { list, i -> playList(list, i) },
                        onQueue = { item, next ->
                            if (player.mediaItemCount == 0) playList(listOf(item), 0)
                            else if (next) player.addMediaItem((player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount), mediaItemFor(item))
                            else player.addMediaItem(mediaItemFor(item))
                        },
                        onRemove = { item -> Covers.forget(context, item.uri); commit(library.filter { it.uri != item.uri }) })
                    "playing" -> NowPlayingSection(player, current, currentTitle, isPlaying, queueVersion, playbackError, content, onEqualizer = { section = "eq" })
                    "videos" -> VideosSection(library, content, onAddFiles = { addFiles("videos") }, onAddFolder = { addFolder("videos") },
                        onPlay = { item -> videoOpen = item; player.setMediaItem(mediaItemFor(item)); player.prepare(); player.play() },
                        onRemove = { item -> Covers.forget(context, item.uri); commit(library.filter { it.uri != item.uri }) })
                    "books", "comics" -> ShelfSection(
                        kind = section, library = library, modifier = content,
                        onAddFiles = { addFiles(section) }, onAddFolder = { addFolder(section) },
                        onChange = { commit(it) }, openExternal = { openExternal(it.androidUri, mimeForItem(it)) },
                    )
                    "photos" -> PhotosSection(content)
                    "eq" -> EqualizerSection(content)
                    "services" -> Column(content) {
                        HomeCard("♫", "Spotify", "Open the official Spotify app", "Open") { openSpotify() }
                        Text("Spotify plays in its official app. MUGEN uses its native player for local files.", color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                    }
                    "settings" -> SettingsSection(library, themePref, onTheme, palettePref, onPalette, player, content, onEqualizer = { section = "eq" },
                        onResetProgress = { commit(library.map { it.copy(progress = 0f, position = "", openedAt = 0) }) })
                }
                if (current != null && section != "playing" && section != "photos") {
                    MiniPlayer(player, current, currentTitle, isPlaying, playbackError) { section = "playing" }
                }
                if (!wide) {
                    TabBar(section) { section = it }
                } else Spacer(Modifier.navigationBarsPadding().height(8.dp))
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        // Width alone misclassifies many phones in landscape as tablets. Require enough
        // vertical room before switching to the permanent tablet sidebar.
        val wideLayout = maxWidth >= 840.dp && maxHeight >= 520.dp
        val compactLayout = maxWidth < 360.dp
        if (wideLayout) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(section, Modifier.width(248.dp).fillMaxHeight()) { section = it }
                Body(Modifier.weight(1f).fillMaxHeight(), wide = true, compact = compactLayout)
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(drawerContainerColor = Color(0xFF0A0A0E), modifier = Modifier.width(290.dp)) {
                        Sidebar(section, Modifier.fillMaxSize()) { section = it; scope.launch { drawerState.close() } }
                    }
                },
            ) {
                Body(Modifier.fillMaxSize(), wide = false, compact = compactLayout)
            }
        }
    }

    videoOpen?.let { item ->
        VideoPlayerDialog(
            item = item,
            player = player,
            error = playbackError,
            onErrorDismiss = { playbackError = null },
            onRetry = {
                playbackError = null
                player.seekTo(0)
                player.prepare()
                player.play()
            },
            onClose = {
                videoOpen = null
                player.pause()
                playbackError = null
            },
        )
    }
}


/**
 * Full-screen, touch-first video player. Controls are deliberately designed for phones:
 * high-contrast cinematic chrome, precise seeking, ±10-second jumps, speed and fit controls.
 */
@androidx.media3.common.util.UnstableApi
@Composable
private fun VideoPlayerDialog(
    item: LibraryItem,
    player: Player,
    error: String?,
    onErrorDismiss: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalMortimerColors.current
    var controlsVisible by remember(item.uri) { mutableStateOf(true) }
    var isPlaying by remember(item.uri) { mutableStateOf(player.isPlaying) }
    var interactionTick by remember(item.uri) { mutableIntStateOf(0) }
    var seekFeedback by remember(item.uri) { mutableStateOf<String?>(null) }
    var seeking by remember(item.uri) { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    var fitMenu by remember { mutableStateOf(false) }
    var speed by remember(item.uri) { mutableFloatStateOf(player.playbackParameters.speed) }
    var fitMode by remember(item.uri) { mutableIntStateOf(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val (position, duration) = rememberPosition(player, true)

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(playbackState: Int) { isPlaying = player.isPlaying }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(controlsVisible, isPlaying, error, interactionTick, speedMenu, fitMenu, seeking) {
        if (controlsVisible && isPlaying && error == null && !speedMenu && !fitMenu && seeking == null) {
            delay(4200)
            controlsVisible = false
        }
    }
    LaunchedEffect(seekFeedback) {
        if (seekFeedback != null) {
            delay(750)
            seekFeedback = null
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                        resizeMode = fitMode
                        setKeepContentOnPlayerReset(true)
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        keepScreenOn = true
                        playerView = this
                    }
                },
                update = {
                    it.player = player
                    it.resizeMode = fitMode
                    it.keepScreenOn = true
                    playerView = it
                },
                onRelease = { it.player = null; it.keepScreenOn = false },
                modifier = Modifier.fillMaxSize(),
            )

            // Tap toggles chrome; double-tap left/right skips 10 seconds like a modern mobile player.
            Box(
                Modifier.fillMaxSize().pointerInput(item.uri, controlsVisible) {
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                            interactionTick++
                        },
                        onDoubleTap = { offset ->
                            val fraction = offset.x / size.width.coerceAtLeast(1)
                            if (fraction < 0.35f) {
                                player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                                seekFeedback = "−10 sec"
                            } else if (fraction > 0.65f) {
                                val end = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                                player.seekTo((player.currentPosition + 10_000L).coerceAtMost(end))
                                seekFeedback = "+10 sec"
                            } else {
                                if (player.isPlaying) player.pause() else player.play()
                            }
                            controlsVisible = true
                            interactionTick++
                        },
                    )
                }
            )
            seekFeedback?.let { feedback ->
                Box(
                    Modifier.align(Alignment.Center).clip(RoundedCornerShape(24.dp))
                        .background(Color(0xB8000000)).padding(horizontal = 22.dp, vertical = 14.dp)
                ) {
                    Text(feedback, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (controlsVisible || error != null) {
                Box(
                    Modifier.fillMaxWidth().height(132.dp).align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(Color(0xE6000000), Color.Transparent)))
                )
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter).statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(Color(0x66000000))
                            .clickable(onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) { Text("✕", color = Color.White, fontSize = 20.sp) }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(item.title.substringBeforeLast('.'), color = Color.White, fontSize = 15.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("MUGEN VIDEO", color = Color.White.copy(alpha = 0.68f), fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp)
                    }
                    Box {
                        Text(
                            "FIT ▾", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x66000000))
                                .clickable { fitMenu = true }.padding(horizontal = 13.dp, vertical = 10.dp),
                        )
                        DropdownMenu(expanded = fitMenu, onDismissRequest = { fitMenu = false }) {
                            listOf(
                                "Fit screen" to androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,
                                "Fill screen" to androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL,
                                "Crop / zoom" to androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                            ).forEach { (label, mode) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = { fitMode = mode; playerView?.resizeMode = mode; fitMenu = false },
                                )
                            }
                        }
                    }
                }

                BoxWithConstraints(
                    Modifier.fillMaxWidth().align(Alignment.BottomCenter).navigationBarsPadding()
                ) {
                    val compact = maxHeight < 430.dp || maxWidth < 380.dp
                    Box(
                        Modifier.fillMaxWidth().height(if (compact) 146.dp else 184.dp)
                            .align(Alignment.BottomCenter)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF0000000))))
                    )
                    Column(
                        Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                            .padding(horizontal = if (compact) 10.dp else 18.dp, vertical = if (compact) 4.dp else 12.dp),
                    ) {
                        Slider(
                            value = (seeking ?: if (duration > 0) position.toFloat() / duration else 0f).coerceIn(0f, 1f),
                            onValueChange = { seeking = it },
                            onValueChangeFinished = {
                                seeking?.let { fraction ->
                                    if (duration > 0) player.seekTo((fraction * duration).toLong())
                                }
                                seeking = null
                            },
                            modifier = Modifier.height(if (compact) 30.dp else 40.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = c.accent,
                                activeTrackColor = c.accent,
                                inactiveTrackColor = Color.White.copy(alpha = 0.35f),
                            ),
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(formatTime(seeking?.let { (it * duration).toLong() } ?: position),
                                color = Color.White, fontSize = if (compact) 10.sp else 12.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.weight(1f))
                            Text("-" + formatTime((duration - (seeking?.let { (it * duration).toLong() } ?: position)).coerceAtLeast(0L)),
                                color = Color.White.copy(alpha = 0.72f), fontSize = if (compact) 10.sp else 12.sp)
                        }
                        Spacer(Modifier.height(if (compact) 2.dp else 8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            VideoControlButton("−10", "Back 10 seconds", size = if (compact) 38 else 44) {
                                player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                                controlsVisible = true
                                interactionTick++
                            }
                            VideoControlButton(if (isPlaying) "Ⅱ" else "▶", if (isPlaying) "Pause" else "Play", primary = true, size = if (compact) 48 else 58) {
                                if (player.isPlaying) player.pause() else player.play()
                                controlsVisible = true
                                interactionTick++
                            }
                            VideoControlButton("+10", "Forward 10 seconds", size = if (compact) 38 else 44) {
                                val end = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                                player.seekTo((player.currentPosition + 10_000L).coerceAtMost(end))
                                controlsVisible = true
                                interactionTick++
                            }
                            if (!compact) SpeedControl(speed, speedMenu, onOpen = { speedMenu = true }, onDismiss = { speedMenu = false }, onSelect = { rate ->
                                speed = rate
                                player.playbackParameters = androidx.media3.common.PlaybackParameters(rate, player.playbackParameters.pitch)
                                speedMenu = false
                                controlsVisible = true
                            })
                        }
                        if (compact) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                SpeedControl(speed, speedMenu, onOpen = { speedMenu = true }, onDismiss = { speedMenu = false }, onSelect = { rate ->
                                    speed = rate
                                    player.playbackParameters = androidx.media3.common.PlaybackParameters(rate, player.playbackParameters.pitch)
                                    speedMenu = false
                                    controlsVisible = true
                                })
                            }
                        }
                    }
                }
            }

            if (error != null) {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp)).background(Color(0xF01B1B22)).padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("CAN’T PLAY THIS VIDEO", color = Color.White, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = Color.White.copy(alpha = 0.82f), textAlign = TextAlign.Center, fontSize = 14.sp)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = c.accent)) {
                            Text("Try again", color = c.onAccent)
                        }
                        OutlinedButton(onClick = { onErrorDismiss(); onClose() }) { Text("Close", color = Color.White) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedControl(
    speed: Float,
    expanded: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (Float) -> Unit,
) {
    Box {
        Text(
            "${"%.2f".format(speed)}×", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x66000000))
                .clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { rate ->
                DropdownMenuItem(
                    text = { Text(if (rate == 1f) "Normal" else "${rate}×") },
                    onClick = { onSelect(rate) },
                )
            }
        }
    }
}

@Composable
private fun VideoControlButton(
    label: String,
    description: String,
    primary: Boolean = false,
    size: Int = 44,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(size.dp).clip(CircleShape)
            .background(if (primary) LocalMortimerColors.current.accent else Color(0x66000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) LocalMortimerColors.current.onAccent else Color.White,
            fontSize = if (label.length > 2) 12.sp else 21.sp, fontWeight = FontWeight.Bold)
    }
}

/** The PWA's desktop sidebar: bright artwork with glowing uppercase labels, the same in light and dark mode. */
@Composable
private fun Sidebar(section: String, modifier: Modifier, onPick: (String) -> Unit) {
    val accent = DarkColors.accent
    Box(modifier.background(Color(0xFF0A0A0E))) {
        Image(painterResource(R.drawable.sidebar_bg), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x33000000), Color(0x99000000)))))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 20.dp)) {
            Text(
                "MUGEN\nPLAYER", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp, lineHeight = 24.sp,
                style = TextStyle(shadow = Shadow(color = accent, blurRadius = 24f)),
                modifier = Modifier.padding(start = 10.dp, bottom = 22.dp),
            )
            SECTIONS.forEach { s ->
                val active = s.id == section
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (active) Color(0x66000000) else Color.Transparent)
                        .clickable { onPick(s.id) }.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(s.icon, color = if (active) accent else Color.White, fontSize = 18.sp, style = TextStyle(shadow = Shadow(color = accent, blurRadius = 14f)), modifier = Modifier.width(30.dp))
                    Text(
                        s.label.uppercase(), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                        style = TextStyle(shadow = Shadow(color = if (active) accent else Color(0xCC000000), blurRadius = if (active) 18f else 8f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun TabBar(section: String, onPick: (String) -> Unit) {
    val c = LocalMortimerColors.current
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).background(c.panel, RoundedCornerShape(18.dp)).navigationBarsPadding().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        TABS.forEach { id ->
            val s = SECTIONS.first { it.id == id }
            val active = section == id
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                    .background(if (active) c.accent.copy(alpha = if (c.dark) 0.16f else 0.10f) else Color.Transparent)
                    .clickable { onPick(id) }.padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(s.icon, color = if (active) c.accent else c.muted, fontSize = 18.sp)
                Text(s.label, color = if (active) c.text else c.muted, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun HomeSection(library: List<LibraryItem>, modifier: Modifier, onGo: (String) -> Unit, openExternal: (LibraryItem) -> Unit) {
    val context = LocalContext.current
    val c = LocalMortimerColors.current
    val reading = library.filter { (it.kind == "books" || it.kind == "comics") && it.started }.sortedByDescending { it.openedAt }.take(10)
    fun count(kind: String) = library.count { it.kind == kind }
    Column(modifier.verticalScroll(rememberScrollState())) {
        // Compact cinematic hero: visual hierarchy first, library shortcuts second.
        Box(
            Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF28202A), Color(0xFF121218), Color(0xFF202029))))
        ) {
            Box(Modifier.align(Alignment.CenterEnd).size(150.dp).background(
                Brush.radialGradient(listOf(c.accent.copy(alpha = 0.28f), Color.Transparent))
            ))
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 19.dp)) {
                Text("YOUR WORLD. YOUR RHYTHM.", color = c.accent, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.1.sp)
                Spacer(Modifier.height(5.dp))
                Text("Everything that moves you.", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 27.sp)
                Text("Music, stories, and memories in one place.", color = Color(0xFFB9B5C0), fontSize = 12.sp, maxLines = 2)
                Spacer(Modifier.height(15.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("${count("audio")} tracks" to "♫", "${count("books") + count("comics")} reads" to "▤", "${count("videos")} videos" to "▶").forEach { (label, icon) ->
                        Row(
                            Modifier.clip(RoundedCornerShape(50)).background(Color(0x22FFFFFF)).padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(icon, color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(5.dp))
                            Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        if (reading.isNotEmpty()) {
            SectionLabel("Continue reading")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                items(reading, key = { it.uri }) { item ->
                    Column(Modifier.width(104.dp).clickable { openReader(context, item, openExternal = openExternal) }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(0.68f).clip(RoundedCornerShape(10.dp))) {
                            CoverImage(item, Modifier.fillMaxSize())
                            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66000000)))
                            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(item.progress.coerceIn(0.02f, 1f)).height(4.dp).background(c.accent))
                        }
                        Text(item.title.substringBeforeLast('.'), color = c.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        Text(item.statusLabel, color = c.muted, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
        SectionLabel("Your library")
        HomeCard("♫", "My Music", "Files from phone, SD card, or USB", "${count("audio")} tracks") { onGo("music") }
        HomeCard("▶", "Videos", "Your local videos", "${count("videos")} videos") { onGo("videos") }
        HomeCard("▤", "Books", "EPUB, PDF, and text with saved progress", "${count("books")} books") { onGo("books") }
        HomeCard("▧", "Comics", "CBZ, CBR, and PDF with manga and vertical reading", "${count("comics")} comics") { onGo("comics") }
        HomeCard("▦", "Photos", "Your device photo gallery", "Open") { onGo("photos") }
        HomeCard("♪", "Music services", "Spotify and supported services", "Connect") { onGo("services") }
        Spacer(Modifier.height(12.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MusicSection(
    library: List<LibraryItem>,
    currentUri: String?,
    modifier: Modifier,
    onAddFiles: () -> Unit,
    onAddFolder: () -> Unit,
    onPlay: (List<LibraryItem>, Int) -> Unit,
    onQueue: (LibraryItem, Boolean) -> Unit,
    onRemove: (LibraryItem) -> Unit,
) {
    val c = LocalMortimerColors.current
    var query by remember { mutableStateOf("") }
    var view by rememberSaveable { mutableStateOf("songs") }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<LibraryItem?>(null) }
    val audio = remember(library) { library.filter { it.kind == "audio" }.sortedBy { naturalKey(it.title) } }
    fun groupKey(item: LibraryItem) = when (view) {
        "artists" -> item.artist.ifBlank { "Unknown artist" }
        "albums" -> item.album.ifBlank { "Unknown album" }
        else -> item.folder.ifBlank { "No folder" }
    }
    val q = query.trim().lowercase()
    val filtered = audio.filter { q.isEmpty() || it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q) }
    BackHandler(enabled = group != null) { group = null }

    Column(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp)) {
            ActionButton("＋ Add music", primary = true, onClick = onAddFiles)
            ActionButton("▤ Add folder", primary = false, onClick = onAddFolder)
            if (filtered.isNotEmpty()) ActionButton("⤮ Shuffle", primary = false) { onPlay(filtered.shuffled(), 0) }
        }
        if (audio.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp).clip(RoundedCornerShape(20.dp)).background(c.panel).padding(horizontal = 20.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Text("♫", color = c.accent, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(12.dp))
                Text("Your music starts here", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text("Add tracks or scan a folder on your phone, SD card, or USB drive. Your library stays available in MUGEN.", color = c.muted, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                ActionButton("＋ Add your first tracks", primary = true, onClick = onAddFiles)
            }
            return@Column
        }
        SearchBox(query, "Search songs, artists, or albums", { query = it })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 10.dp).horizontalScroll(rememberScrollState())) {
            listOf("songs" to "Songs", "artists" to "Artists", "albums" to "Albums", "folders" to "Folders").forEach { (id, label) ->
                Chip(label, view == id) { view = id; group = null }
            }
        }
        val g = group
        if (view != "songs" && g == null) {
            val groups = filtered.groupBy { groupKey(it) }.toSortedMap(compareBy<String> { it.lowercase() })
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(groups.entries.toList(), key = { it.key }) { (name, tracks) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.panel).clickable { group = name }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverImage(tracks.first(), Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(name, color = c.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${tracks.size} tracks", color = c.muted, fontSize = 12.sp)
                        }
                        Text("›", color = c.muted, fontSize = 20.sp)
                    }
                }
            }
        } else {
            val tracks = if (g != null) filtered.filter { groupKey(it) == g } else filtered
            if (g != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    Chip("‹ Back", false) { group = null }
                    Text(g, color = c.text, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                    Chip("▶ Play all", true) { onPlay(tracks, 0) }
                }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(tracks, key = { _, it -> it.uri }) { index, item ->
                    val playing = item.uri == currentUri
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (playing) c.panel2 else c.panel)
                            .combinedClickable(onClick = { onPlay(tracks, index) }, onLongClick = { menu = item })
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverImage(item, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(item.title, color = if (playing) c.accent else c.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(item.artist, item.album).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Local audio" }, color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("⋮", color = c.muted, fontSize = 18.sp, modifier = Modifier.clickable { menu = item }.padding(horizontal = 8.dp))
                    }
                }
            }
        }
    }

    menu?.let { item ->
        Dialog(onDismissRequest = { menu = null }) {
            Column(Modifier.fillMaxWidth().background(c.panel, RoundedCornerShape(20.dp)).padding(18.dp)) {
                Text(item.title, color = c.text, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.artist.isNotBlank()) Text(item.artist, color = c.muted, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                MenuItem("▶ Play") { menu = null; onPlay(listOf(item), 0) }
                MenuItem("⤵ Play next") { menu = null; onQueue(item, true) }
                MenuItem("＋ Add to queue") { menu = null; onQueue(item, false) }
                MenuItem("🗑 Remove from library", danger = true) { menu = null; onRemove(item) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideosSection(library: List<LibraryItem>, modifier: Modifier, onAddFiles: () -> Unit, onAddFolder: () -> Unit, onPlay: (LibraryItem) -> Unit, onRemove: (LibraryItem) -> Unit) {
    val c = LocalMortimerColors.current
    val videos = remember(library) { library.filter { it.kind == "videos" }.sortedBy { naturalKey(it.title) } }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf<LibraryItem?>(null) }
    Column(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp)) {
            ActionButton("＋ Add videos", primary = true, onClick = onAddFiles)
            ActionButton("▤ Add folder", primary = false, onClick = onAddFolder)
        }
        if (videos.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp).clip(RoundedCornerShape(20.dp)).background(c.panel).padding(horizontal = 20.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Text("▶", color = c.accent, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(12.dp))
                Text("Your video library is ready", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text("Add individual videos or scan a folder on your phone, SD card, or USB drive.", color = c.muted, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                ActionButton("＋ Add your first videos", primary = true, onClick = onAddFiles)
            }
            return@Column
        }
        SearchBox(query, "Search videos", { query = it }, Modifier.padding(bottom = 10.dp))
        val shown = videos.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.uri }) { item ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.panel)
                        .combinedClickable(onClick = { onPlay(item) }, onLongClick = { menu = item }).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
                        CoverImage(item, Modifier.fillMaxSize())
                        Text("▶", color = Color.White, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(item.title.substringBeforeLast('.'), color = c.text, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(item.folder.ifBlank { "Local video" }, color = c.muted, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }
    }
    menu?.let { item ->
        Dialog(onDismissRequest = { menu = null }) {
            Column(Modifier.fillMaxWidth().background(c.panel, RoundedCornerShape(20.dp)).padding(18.dp)) {
                Text(item.title, color = c.text, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                MenuItem("▶ Play") { menu = null; onPlay(item) }
                MenuItem("🗑 Remove from library", danger = true) { menu = null; onRemove(item) }
            }
        }
    }
}

@Composable
private fun rememberPosition(player: Player, active: Boolean): Pair<Long, Long> {
    var position by remember { mutableLongStateOf(player.currentPosition) }
    var duration by remember { mutableLongStateOf(player.duration) }
    LaunchedEffect(player, active) {
        while (active) {
            position = player.currentPosition
            duration = player.duration
            delay(500)
        }
    }
    return position to duration
}

@Composable
private fun MiniPlayer(player: Player, current: LibraryItem, title: String?, isPlaying: Boolean, error: String?, onOpen: () -> Unit) {
    val c = LocalMortimerColors.current
    val (position, duration) = rememberPosition(player, isPlaying)
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(listOf(c.panel, c.panel2.copy(alpha = if (c.dark) 0.92f else 0.72f))))
            .clickable(onClick = onOpen)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(c.accent.copy(alpha = if (c.dark) 0.14f else 0.08f)), contentAlignment = Alignment.Center) {
                CoverImage(current, Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(title ?: current.title, color = c.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(error ?: current.artist.ifBlank { "Playing" }, color = if (error != null) Color(0xFFFF7A66) else c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RoundButton(if (isPlaying) "Ⅱ" else "▶", primary = true) { if (player.isPlaying) player.pause() else player.play() }
            Spacer(Modifier.width(6.dp))
            RoundButton("⏭", primary = false) { if (player.hasNextMediaItem()) player.seekToNextMediaItem() }
        }
        if (duration > 0) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(c.panel2)) {
                Box(Modifier.fillMaxWidth((position.toFloat() / duration).coerceIn(0f, 1f)).fillMaxHeight().background(c.accent))
            }
        }
    }
}

@Composable
private fun RoundButton(label: String, primary: Boolean, size: Int = 42, onClick: () -> Unit) {
    val c = LocalMortimerColors.current
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(if (primary) c.accent else c.panel2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) c.onAccent else c.text, fontSize = (size * 0.4f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NowPlayingSection(player: Player, current: LibraryItem?, title: String?, isPlaying: Boolean, queueVersion: Int, error: String?, modifier: Modifier, onEqualizer: () -> Unit) {
    val c = LocalMortimerColors.current
    val (position, duration) = rememberPosition(player, true)
    var seeking by remember { mutableStateOf<Float?>(null) }
    var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    if (current == null && player.currentMediaItem == null) {
        Text("Nothing is playing yet. Choose a track in Music.", color = c.muted, fontSize = 14.sp, modifier = modifier.padding(top = 20.dp))
        return
    }
    val queue = remember(queueVersion, player.mediaItemCount) { (0 until player.mediaItemCount).map { player.getMediaItemAt(it) } }
    LazyColumn(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.fillMaxWidth(0.82f).aspectRatio(0.92f).clip(RoundedCornerShape(34.dp))
                            .background(Brush.radialGradient(listOf(c.accent.copy(alpha = if (c.dark) 0.24f else 0.12f), Color.Transparent)))
                    )
                    if (current != null) {
                        CoverImage(current, Modifier.fillMaxWidth(0.70f).aspectRatio(1f).clip(RoundedCornerShape(24.dp)))
                    } else {
                        Box(Modifier.fillMaxWidth(0.70f).aspectRatio(1f).clip(RoundedCornerShape(24.dp))
                            .background(Brush.linearGradient(listOf(c.panel2, c.panel))), contentAlignment = Alignment.Center) {
                            Text("♫", color = c.accent, fontSize = 64.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
                Spacer(Modifier.height(13.dp))
                Box(Modifier.clip(RoundedCornerShape(50)).background(c.accent.copy(alpha = if (c.dark) 0.14f else 0.10f)).padding(horizontal = 11.dp, vertical = 6.dp)) {
                    Text("NOW PLAYING", color = c.accent, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text(title ?: current?.title ?: "", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(current?.artist?.ifBlank { null }, current?.album?.ifBlank { null }).joinToString(" · "), color = c.muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                error?.let { Text(it, color = Color(0xFFFF7A66), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
                Slider(
                    value = seeking ?: if (duration > 0) position.toFloat() / duration else 0f,
                    onValueChange = { seeking = it },
                    onValueChangeFinished = { seeking?.let { if (duration > 0) player.seekTo((it * duration).toLong()) }; seeking = null },
                    colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.panel2),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(formatTime(seeking?.let { (it * duration).toLong() } ?: position), color = c.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(formatTime(duration), color = c.muted, fontSize = 12.sp)
                }
                // Keep the primary transport controls on one balanced row; secondary seeks live below.
                Row(
                    Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    key(queueVersion) {
                        RoundButton("⤮", primary = player.shuffleModeEnabled, size = 40) { player.shuffleModeEnabled = !player.shuffleModeEnabled }
                    }
                    RoundButton("⏮", primary = false, size = 44) { player.seekToPrevious() }
                    RoundButton(if (isPlaying) "Ⅱ" else "▶", primary = true, size = 62) { if (player.isPlaying) player.pause() else player.play() }
                    RoundButton("⏭", primary = false, size = 44) { if (player.hasNextMediaItem()) player.seekToNextMediaItem() }
                    key(queueVersion) {
                        val mode = player.repeatMode
                        RoundButton(if (mode == Player.REPEAT_MODE_ONE) "①" else "⟳", primary = mode != Player.REPEAT_MODE_OFF, size = 40) {
                            player.repeatMode = when (mode) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                else -> Player.REPEAT_MODE_OFF
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundButton("↺ 10", primary = false, size = 40) { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }
                    Spacer(Modifier.width(24.dp))
                    RoundButton("10 ↻", primary = false, size = 40) { player.seekTo((player.currentPosition + 10_000).coerceAtMost(if (player.duration > 0) player.duration else Long.MAX_VALUE)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                        Chip("${if (s % 1f == 0f) s.toInt().toString() else s.toString()}×", speed == s) { speed = s; player.setPlaybackSpeed(s) }
                    }
                }
                Box(Modifier.padding(top = 10.dp)) { Chip("≋ Equalizer", AudioEffects.currentSettings().enabled) { onEqualizer() } }
                if (queue.size > 1) Box(Modifier.fillMaxWidth().padding(top = 16.dp)) { SectionLabel("QUEUE · ${queue.size}") }
            }
        }
        if (queue.size > 1) {
            itemsIndexed(queue) { index, media ->
                val on = index == player.currentMediaItemIndex
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp)).background(if (on) c.panel2 else Color.Transparent)
                        .clickable { player.seekTo(index, 0L); player.play() }.padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${index + 1}", color = if (on) c.accent else c.muted, fontSize = 12.sp, modifier = Modifier.width(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(media.mediaMetadata.title?.toString() ?: "Pista ${index + 1}", color = if (on) c.accent else c.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        media.mediaMetadata.artist?.let { Text(it.toString(), color = c.muted, fontSize = 12.sp, maxLines = 1) }
                    }
                    if (!on) Text("✕", color = c.muted, modifier = Modifier.clickable { player.removeMediaItem(index) }.padding(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(library: List<LibraryItem>, themePref: String, onTheme: (String) -> Unit, palettePref: String, onPalette: (String) -> Unit, player: Player, modifier: Modifier, onEqualizer: () -> Unit, onResetProgress: () -> Unit) {
    val context = LocalContext.current
    val c = LocalMortimerColors.current
    var confirm by remember { mutableStateOf(false) }
    var coversCleared by remember { mutableStateOf(false) }
    Column(modifier.verticalScroll(rememberScrollState())) {
        SettingsCard("Appearance") {
            Text("Theme", color = c.text, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp)) {
                listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (id, label) -> Chip(label, themePref == id) { onTheme(id) } }
            }
            Text("Choose System to follow your device, or lock MUGEN to Light or Dark mode.", color = c.muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(12.dp))
            Text("Color theme", color = c.text, fontWeight = FontWeight.SemiBold)
            Text("Choose an accent that carries through buttons, highlights, sliders, and artwork glow.", color = c.muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 3.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 4.dp),
            ) {
                listOf(
                    Triple("ember", "Ember", Color(0xFFFF7849)),
                    Triple("ocean", "Ocean", Color(0xFF45B8FF)),
                    Triple("emerald", "Emerald", Color(0xFF43D6A0)),
                    Triple("violet", "Violet", Color(0xFFB69AFF)),
                    Triple("rose", "Rose", Color(0xFFFF7EAB)),
                    Triple("gold", "Gold", Color(0xFFFFCF5A)),
                ).forEach { (id, label, swatch) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(58.dp).clip(RoundedCornerShape(12.dp)).clickable { onPalette(id) }.padding(vertical = 5.dp),
                    ) {
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(if (palettePref == id) c.text else c.panel2).padding(3.dp)
                                .clip(CircleShape).background(swatch),
                        ) {}
                        Text(label, color = if (palettePref == id) c.accent else c.muted, fontSize = 11.sp, fontWeight = if (palettePref == id) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
        }
        SettingsCard("Playback") {
            var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
            Text("Speed", color = c.text, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState())) {
                listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                    Chip("${if (s % 1f == 0f) s.toInt().toString() else s.toString()}×", speed == s) { speed = s; player.setPlaybackSpeed(s) }
                }
            }
            Spacer(Modifier.height(6.dp))
            MenuItem("≋ Equalizer, balance, and volume boost") { onEqualizer() }
        }
        SettingsCard("Library") {
            Text(
                "${library.count { it.kind == "audio" }} tracks · ${library.count { it.kind == "videos" }} videos · ${library.count { it.kind == "books" }} books · ${library.count { it.kind == "comics" }} comics",
                color = c.text, fontSize = 14.sp,
            )
            Text("Your media stays in its original location. MUGEN saves library references and reading progress, including for supported removable storage.", color = c.muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 5.dp, bottom = 10.dp))
            MenuItem(if (coversCleared) "✓ Artwork cache cleared" else "Regenerate artwork cache") { Covers.clear(context); coversCleared = true }
            MenuItem("Clear reading progress", danger = true) { confirm = true }
        }
        AppUpdateCard()
        SettingsCard("About") {
            Text("MUGEN ${BuildConfig.VERSION_NAME}", color = c.text, fontWeight = FontWeight.SemiBold)
            Text("Music, videos, books, comics, and photos in one app.", color = c.muted, fontSize = 12.sp)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Clear reading progress?") },
            text = { Text("All books and comics will return to the first page.") },
            confirmButton = { TextButton(onClick = { confirm = false; onResetProgress() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalMortimerColors.current
    SectionLabel(title)
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(18.dp)).background(c.panel).padding(horizontal = 16.dp, vertical = 15.dp), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
}

@Composable
private fun HomeCard(icon: String, title: String, subtitle: String, trailing: String, onClick: () -> Unit) {
    val c = LocalMortimerColors.current
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = c.panel),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (c.dark) 2.dp else 0.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).background(c.accent.copy(alpha = if (c.dark) 0.14f else 0.10f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Text(icon, color = c.accent, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, color = c.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(subtitle, color = c.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(trailing, color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
