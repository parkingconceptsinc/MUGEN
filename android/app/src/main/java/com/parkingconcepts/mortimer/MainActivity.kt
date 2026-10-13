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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val themePref = mutableStateOf("system")
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        themePref.value = ThemePref.get(this)
        setContent {
            MortimerTheme(themePref.value) {
                Surface(Modifier.fillMaxSize(), color = LocalMortimerColors.current.bg) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Iniciando Mortimer Player…", color = LocalMortimerColors.current.text)
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
                            MortimerTheme(themePref.value) {
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
                            MortimerTheme(themePref.value) {
                                Surface(Modifier.fillMaxSize(), color = LocalMortimerColors.current.bg) {
                                    Text("No se pudo iniciar el reproductor. Cierra y vuelve a abrir Mortimer Player.", color = LocalMortimerColors.current.text, modifier = Modifier.padding(24.dp))
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
    Section("home", "Inicio", "⌂"),
    Section("music", "Música", "♫"),
    Section("playing", "Reproduciendo", "◉"),
    Section("videos", "Vídeos", "▶"),
    Section("books", "Libros", "▤"),
    Section("comics", "Cómics", "▧"),
    Section("photos", "Fotos", "▦"),
    Section("eq", "Ecualizador", "≋"),
    Section("services", "Servicios", "♪"),
    Section("settings", "Ajustes", "⚙"),
)
private val TABS = listOf("home", "music", "books", "comics", "photos")

private fun mediaItemFor(item: LibraryItem): MediaItem = MediaItem.Builder()
    .setUri(item.androidUri)
    .setMediaId(item.uri)
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
            importing = "Añadiendo ${found.size} archivos…"
            val known = library.map { it.uri }.toSet()
            val fresh = found.filter { it.uri !in known }
            val tagged = withContext(Dispatchers.IO) { if (kind == "audio") fresh.map { withAudioTags(context, it) } else fresh }
            val next = withContext(Dispatchers.IO) { LibraryStore.update(context) { list -> list + tagged.filter { t -> list.none { it.uri == t.uri } } } }
            library = next
            importing = if (fresh.isEmpty() && found.isNotEmpty()) "Esos archivos ya estaban en la biblioteca." else if (found.isEmpty()) "No se encontraron archivos compatibles." else null
            if (importing != null) { delay(2500); importing = null }
        }
    }

    var pickKind by remember { mutableStateOf("audio") }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val kind = pickKind
            scope.launch {
                importing = "Leyendo archivos…"
                val found = withContext(Dispatchers.IO) { itemsFromUris(context, uris, kind) }
                addItems(found, kind)
            }
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            val kind = pickKind
            scope.launch {
                importing = "Buscando en la carpeta…"
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
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "Formato o códec no compatible con este dispositivo."
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "No se puede acceder al archivo. Vuelve a importarlo."
                    else -> "No se pudo reproducir este archivo. Código: " + error.errorCodeName
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
    fun Body(modifier: Modifier, wide: Boolean) {
        Box(modifier.background(c.bg)) {
            if (c.dark && (section == "home" || section == "music")) {
                Image(
                    painter = painterResource(R.drawable.music_home_bg), contentDescription = null,
                    contentScale = ContentScale.Crop, alpha = 0.40f, modifier = Modifier.fillMaxSize(),
                )
            }
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 18.dp)) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!wide) {
                        Box(Modifier.size(40.dp).clip(CircleShape).clickable { scope.launch { drawerState.open() } }, contentAlignment = Alignment.Center) {
                            Text("☰", color = c.text, fontSize = 20.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("MORTIMER PLAYER", color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
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
                        HomeCard("♫", "Spotify", "Abrir la aplicación oficial de Spotify", "Abrir") { openSpotify() }
                        Text("Spotify se reproduce en su aplicación oficial. Mortimer Player utiliza su reproductor nativo para los archivos locales.", color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                    }
                    "settings" -> SettingsSection(library, themePref, onTheme, player, content, onEqualizer = { section = "eq" },
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
        if (maxWidth >= 840.dp) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(section, Modifier.width(260.dp).fillMaxHeight()) { section = it }
                Body(Modifier.weight(1f).fillMaxHeight(), wide = true)
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
                Body(Modifier.fillMaxSize(), wide = false)
            }
        }
    }

    videoOpen?.let { item ->
        Dialog(onDismissRequest = { videoOpen = null; player.pause() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(
                    factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true; setShowNextButton(false); setShowPreviousButton(false) } },
                    update = { it.player = player },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize(),
                )
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0x66000000)).clickable { videoOpen = null; player.pause() }, contentAlignment = Alignment.Center) {
                        Text("✕", color = Color.White, fontSize = 18.sp)
                    }
                    Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
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
                "MORTIMER\nPLAYER", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp, lineHeight = 24.sp,
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
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { onPick(id) }.padding(vertical = 6.dp),
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
        if (reading.isNotEmpty()) {
            SectionLabel("Seguir leyendo")
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
        SectionLabel("Tu contenido")
        HomeCard("♫", "Mi música", "Archivos del teléfono, SD o USB", "${count("audio")} canciones") { onGo("music") }
        HomeCard("▶", "Vídeos", "Tus vídeos locales", "${count("videos")} vídeos") { onGo("videos") }
        HomeCard("▤", "Libros", "EPUB, PDF y texto con tu progreso", "${count("books")} libros") { onGo("books") }
        HomeCard("▧", "Cómics", "CBZ, CBR y PDF, modo manga y vertical", "${count("comics")} cómics") { onGo("comics") }
        HomeCard("▦", "Fotos", "Álbum de fotos del teléfono", "Abrir") { onGo("photos") }
        HomeCard("♪", "Servicios de música", "Spotify y servicios compatibles", "Conectar") { onGo("services") }
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
        "artists" -> item.artist.ifBlank { "Artista desconocido" }
        "albums" -> item.album.ifBlank { "Álbum desconocido" }
        else -> item.folder.ifBlank { "Sin carpeta" }
    }
    val q = query.trim().lowercase()
    val filtered = audio.filter { q.isEmpty() || it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q) }
    BackHandler(enabled = group != null) { group = null }

    Column(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
            ActionButton("＋ Añadir música", primary = true, onClick = onAddFiles)
            ActionButton("▤ Carpeta", primary = false, onClick = onAddFolder)
            if (filtered.isNotEmpty()) ActionButton("⤮ Aleatorio", primary = false) { onPlay(filtered.shuffled(), 0) }
        }
        if (audio.isEmpty()) {
            Text("Elige archivos de audio o una carpeta entera del teléfono, una tarjeta SD o una memoria USB.", color = c.muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 18.dp))
            return@Column
        }
        SearchBox(query, "Buscar canciones, artistas o álbumes", { query = it })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 10.dp).horizontalScroll(rememberScrollState())) {
            listOf("songs" to "Canciones", "artists" to "Artistas", "albums" to "Álbumes", "folders" to "Carpetas").forEach { (id, label) ->
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
                            Text("${tracks.size} canciones", color = c.muted, fontSize = 12.sp)
                        }
                        Text("›", color = c.muted, fontSize = 20.sp)
                    }
                }
            }
        } else {
            val tracks = if (g != null) filtered.filter { groupKey(it) == g } else filtered
            if (g != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    Chip("‹ Volver", false) { group = null }
                    Text(g, color = c.text, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                    Chip("▶ Todo", true) { onPlay(tracks, 0) }
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
                            Text(listOf(item.artist, item.album).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Audio local" }, color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                MenuItem("▶ Reproducir") { menu = null; onPlay(listOf(item), 0) }
                MenuItem("⤵ Reproducir a continuación") { menu = null; onQueue(item, true) }
                MenuItem("＋ Añadir a la cola") { menu = null; onQueue(item, false) }
                MenuItem("🗑 Quitar de la biblioteca", danger = true) { menu = null; onRemove(item) }
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
            ActionButton("＋ Añadir vídeos", primary = true, onClick = onAddFiles)
            ActionButton("▤ Carpeta", primary = false, onClick = onAddFolder)
        }
        if (videos.isEmpty()) {
            Text("Selecciona vídeos del dispositivo, SD o USB.", color = c.muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 18.dp))
            return@Column
        }
        SearchBox(query, "Buscar vídeos", { query = it }, Modifier.padding(bottom = 10.dp))
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
                        Text(item.folder.ifBlank { "Vídeo local" }, color = c.muted, fontSize = 12.sp, maxLines = 1)
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
                MenuItem("▶ Reproducir") { menu = null; onPlay(item) }
                MenuItem("🗑 Quitar de la biblioteca", danger = true) { menu = null; onRemove(item) }
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
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(c.panel).clickable(onClick = onOpen)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(current, Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(title ?: current.title, color = c.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(error ?: current.artist.ifBlank { "Reproduciendo" }, color = if (error != null) Color(0xFFFF7A66) else c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Text("Nada se está reproduciendo. Elige una canción en Música.", color = c.muted, fontSize = 14.sp, modifier = modifier.padding(top = 20.dp))
        return
    }
    val queue = remember(queueVersion, player.mediaItemCount) { (0 until player.mediaItemCount).map { player.getMediaItemAt(it) } }
    LazyColumn(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (current != null) {
                    CoverImage(current, Modifier.fillMaxWidth(0.72f).aspectRatio(1f).clip(RoundedCornerShape(22.dp)))
                } else {
                    Box(Modifier.fillMaxWidth(0.72f).aspectRatio(1f).clip(RoundedCornerShape(22.dp)).background(c.panel2), contentAlignment = Alignment.Center) {
                        Text("♫", color = c.accent, fontSize = 64.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 10.dp)) {
                    key(queueVersion) {
                        RoundButton("⤮", primary = player.shuffleModeEnabled) { player.shuffleModeEnabled = !player.shuffleModeEnabled }
                    }
                    RoundButton("⏮", primary = false) { player.seekToPrevious() }
                    RoundButton("↺", primary = false) { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }
                    RoundButton(if (isPlaying) "Ⅱ" else "▶", primary = true, size = 64) { if (player.isPlaying) player.pause() else player.play() }
                    RoundButton("↻", primary = false) { player.seekTo(player.currentPosition + 10_000) }
                    RoundButton("⏭", primary = false) { if (player.hasNextMediaItem()) player.seekToNextMediaItem() }
                    key(queueVersion) {
                        val mode = player.repeatMode
                        RoundButton(if (mode == Player.REPEAT_MODE_ONE) "①" else "⟳", primary = mode != Player.REPEAT_MODE_OFF) {
                            player.repeatMode = when (mode) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                else -> Player.REPEAT_MODE_OFF
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                        Chip("${if (s % 1f == 0f) s.toInt().toString() else s.toString()}×", speed == s) { speed = s; player.setPlaybackSpeed(s) }
                    }
                }
                Box(Modifier.padding(top = 10.dp)) { Chip("≋ Ecualizador", AudioEffects.currentSettings().enabled) { onEqualizer() } }
                if (queue.size > 1) Box(Modifier.fillMaxWidth().padding(top = 16.dp)) { SectionLabel("Cola · ${queue.size}") }
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
private fun SettingsSection(library: List<LibraryItem>, themePref: String, onTheme: (String) -> Unit, player: Player, modifier: Modifier, onEqualizer: () -> Unit, onResetProgress: () -> Unit) {
    val context = LocalContext.current
    val c = LocalMortimerColors.current
    var confirm by remember { mutableStateOf(false) }
    var coversCleared by remember { mutableStateOf(false) }
    Column(modifier.verticalScroll(rememberScrollState())) {
        SettingsCard("Apariencia") {
            Text("Tema", color = c.text, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                listOf("system" to "Sistema", "light" to "Claro", "dark" to "Oscuro").forEach { (id, label) -> Chip(label, themePref == id) { onTheme(id) } }
            }
            Text("La barra lateral conserva su imagen y sus letras en los dos modos, como en la web.", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        SettingsCard("Reproducción") {
            var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
            Text("Velocidad", color = c.text, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState())) {
                listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                    Chip("${if (s % 1f == 0f) s.toInt().toString() else s.toString()}×", speed == s) { speed = s; player.setPlaybackSpeed(s) }
                }
            }
            Spacer(Modifier.height(6.dp))
            MenuItem("≋ Ecualizador, balance y refuerzo de volumen") { onEqualizer() }
        }
        SettingsCard("Biblioteca") {
            Text(
                "${library.count { it.kind == "audio" }} canciones · ${library.count { it.kind == "videos" }} vídeos · ${library.count { it.kind == "books" }} libros · ${library.count { it.kind == "comics" }} cómics",
                color = c.text, fontSize = 14.sp,
            )
            Text("Los archivos se quedan donde están; Mortimer solo guarda dónde encontrarlos y tu progreso.", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            MenuItem(if (coversCleared) "✓ Portadas borradas" else "Volver a generar portadas") { Covers.clear(context); coversCleared = true }
            MenuItem("Borrar el progreso de lectura", danger = true) { confirm = true }
        }
        AppUpdateCard()
        SettingsCard("Acerca de") {
            Text("Mortimer Player ${BuildConfig.VERSION_NAME}", color = c.text, fontWeight = FontWeight.SemiBold)
            Text("Música, vídeos, libros, cómics y fotos en una sola app.", color = c.muted, fontSize = 12.sp)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("¿Borrar el progreso?") },
            text = { Text("Todos los libros y cómics volverán a empezar desde la primera página.") },
            confirmButton = { TextButton(onClick = { confirm = false; onResetProgress() }) { Text("Borrar") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalMortimerColors.current
    SectionLabel(title)
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).background(c.panel, RoundedCornerShape(16.dp)).padding(14.dp), content = content)
}

@Composable
private fun HomeCard(icon: String, title: String, subtitle: String, trailing: String, onClick: () -> Unit) {
    val c = LocalMortimerColors.current
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = c.panel), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(c.panel2, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Text(icon, color = c.accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, color = c.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(subtitle, color = c.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(trailing, color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
