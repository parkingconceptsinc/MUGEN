package com.parkingconcepts.mortimer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reader options, the same ones the PWA comic reader has. */
private data class ComicSettings(
    /** "paged", "double" or "vertical". */
    val mode: String = "paged",
    val rtl: Boolean = false,
    /** "screen" or "width". */
    val fit: String = "screen",
    val keepAwake: Boolean = true,
) {
    fun save(context: Context) {
        context.getSharedPreferences("mortimer_prefs", Context.MODE_PRIVATE).edit()
            .putString("comic_mode", mode).putBoolean("comic_rtl", rtl).putString("comic_fit", fit).putBoolean("comic_keep_awake", keepAwake).apply()
    }

    companion object {
        fun load(context: Context): ComicSettings {
            val p = context.getSharedPreferences("mortimer_prefs", Context.MODE_PRIVATE)
            return ComicSettings(
                mode = p.getString("comic_mode", "paged")?.takeIf { it in setOf("paged", "double", "vertical") } ?: "paged",
                rtl = p.getBoolean("comic_rtl", false),
                fit = p.getString("comic_fit", "screen")?.takeIf { it in setOf("screen", "width") } ?: "screen",
                keepAwake = p.getBoolean("comic_keep_awake", true),
            )
        }
    }
}

class ComicReaderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: displayName(this, uri, "Comic")
        val format = intent.getStringExtra(EXTRA_FORMAT) ?: formatOf(title, "comics")
        val fromStart = intent.getBooleanExtra(EXTRA_FROM_START, false)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        val insets = WindowInsetsControllerCompat(window, window.decorView)
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.isAppearanceLightStatusBars = false
        insets.isAppearanceLightNavigationBars = false
        setContent {
            MortimerTheme("dark") {
                ComicReader(
                    uri = uri, title = title, format = format, fromStart = fromStart,
                    onClose = { finish() },
                    onChrome = { show -> if (show) insets.show(WindowInsetsCompat.Type.systemBars()) else insets.hide(WindowInsetsCompat.Type.systemBars()) },
                    onKeepAwake = { on ->
                        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    },
                )
            }
        }
    }

    companion object {
        const val EXTRA_TITLE = "comic_title"
        const val EXTRA_FORMAT = "comic_format"
        const val EXTRA_FROM_START = "comic_from_start"
    }
}

/** Groups pages into what is shown at once: single pages, or the cover alone then pairs. */
private fun spreadsFor(count: Int, mode: String): List<List<Int>> =
    if (mode != "double") (0 until count).map { listOf(it) }
    else buildList {
        if (count > 0) add(listOf(0))
        var i = 1
        while (i < count) {
            add(if (i + 1 < count) listOf(i, i + 1) else listOf(i))
            i += 2
        }
    }

@Composable
private fun ComicReader(
    uri: Uri,
    title: String,
    format: String,
    fromStart: Boolean,
    onClose: () -> Unit,
    onChrome: (Boolean) -> Unit,
    onKeepAwake: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val key = uri.toString()
    var source by remember { mutableStateOf<PageSource?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(key) {
        runCatching { openResourceOnIo { openPageSource(context, uri, format) { pct -> loading = pct } } }
            .onSuccess { source = it }
            .onFailure { if (it is CancellationException) throw it; error = it.message ?: "Could not open this file." }
    }
    DisposableEffect(source) {
        val s = source
        onDispose { s?.close() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val src = source
        when {
            error != null -> Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error!!, color = Color.White, fontSize = 16.sp)
                Spacer(Modifier.height(16.dp))
                ChromeButton("Volver") { onClose() }
            }
            src == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Abriendo $title…", color = Color.White)
                loading?.let { Text("Preparing pages · $it", color = Color(0xAAFFFFFF), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
            else -> ComicPages(src, key, title, fromStart, onClose, onChrome, onKeepAwake)
        }
    }
}

@Composable
private fun BoxScope.ComicPages(
    src: PageSource,
    key: String,
    title: String,
    fromStart: Boolean,
    onClose: () -> Unit,
    onChrome: (Boolean) -> Unit,
    onKeepAwake: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val count = src.count
    var settings by remember { mutableStateOf(ComicSettings.load(context)) }
    val startPage = remember {
        if (fromStart) 0 else LibraryStore.find(context, key)?.position?.toIntOrNull()?.coerceIn(0, count - 1) ?: 0
    }
    var page by remember { mutableIntStateOf(startPage) }
    var chrome by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val screenW = with(density) { config.screenWidthDp.dp.roundToPx() }
    val screenH = with(density) { config.screenHeightDp.dp.roundToPx() }
    val cache = remember(src, screenW, screenH, settings.fit) {
        object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 5).toInt()) {
            override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
        }
    }

    LaunchedEffect(chrome) { onChrome(chrome) }
    LaunchedEffect(settings) { settings.save(context); onKeepAwake(settings.keepAwake) }
    val progressError = PersistReaderProgress(ReadingCheckpoint(key, page.toString(), if (page >= count - 1) 1f else (page + 1f) / count, count))
    DisposableEffect(cache) { onDispose { cache.evictAll() } }
    BackHandler(enabled = sheet != null) { sheet = null }

    val spreads = remember(count, settings.mode) { spreadsFor(count, settings.mode) }
    fun spreadOf(p: Int) = spreads.indexOfFirst { p in it }.coerceAtLeast(0)
    val maxW = (screenW * 1.6f).toInt()
    val maxH = (screenH * 1.6f).toInt()

    // A tap on the left/right edge turns the page (mirrored for manga), the middle shows the controls.
    var jump by remember { mutableStateOf<Int?>(null) }
    fun onTapAt(x: Float, width: Int) {
        val zone = x / width
        val dir = when {
            zone < 0.3f -> -1
            zone > 0.7f -> 1
            else -> 0
        }
        if (dir == 0) { chrome = !chrome; return }
        val step = if (settings.rtl && settings.mode != "vertical") -dir else dir
        val current = spreadOf(page)
        val target = current + step
        if (target >= spreads.size) { finished = true; return }
        if (target < 0) return
        jump = spreads[target].first()
        if (chrome) chrome = false
    }

    key(settings.mode) {
    if (settings.mode == "vertical") {
        val list = rememberLazyListState(initialFirstVisibleItemIndex = page)
        LaunchedEffect(list) {
            snapshotFlow { list.firstVisibleItemIndex }.distinctUntilChanged().collect { page = it }
        }
        LaunchedEffect(jump) { jump?.let { list.scrollToItem(it); jump = null } }
        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.size > 1 || scale > 1f) {
                                scale = (scale * event.calculateZoom()).coerceIn(1f, 4f)
                                val pan = event.calculatePan()
                                val maxX = size.width * (scale - 1) / 2
                                offsetX = if (scale > 1f) (offsetX + pan.x).coerceIn(-maxX, maxX) else 0f
                                if (scale > 1f) list.dispatchRawDelta(-pan.y / scale)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { chrome = !chrome },
                        onDoubleTap = { if (scale > 1f) { scale = 1f; offsetX = 0f } else scale = 2f },
                    )
                },
        ) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offsetX },
            ) {
                items(count) { index ->
                    PageImage(src, cache, index, screenW, (screenW * 3), ContentScale.FillWidth, Modifier.fillMaxWidth(), tall = true)
                }
            }
        }
    } else {
        val pager = rememberPagerState(initialPage = spreadOf(page)) { spreads.size }
        LaunchedEffect(pager, spreads) {
            snapshotFlow { pager.currentPage }.distinctUntilChanged().collect { s -> spreads.getOrNull(s)?.let { page = it.first() } }
        }
        LaunchedEffect(jump) {
            jump?.let { target ->
                val s = spreadOf(target)
                if (kotlin.math.abs(s - pager.currentPage) == 1) pager.animateScrollToPage(s) else pager.scrollToPage(s)
                jump = null
            }
        }
        HorizontalPager(
            state = pager,
            reverseLayout = settings.rtl,
            beyondViewportPageCount = 1,
            userScrollEnabled = !zoomed,
            modifier = Modifier.fillMaxSize(),
        ) { s ->
            ZoomPage(
                onTap = { x, w -> onTapAt(x, w) },
                onZoomChanged = { if (s == pager.currentPage) zoomed = it },
                scrollable = settings.fit == "width",
            ) {
                val pages = spreads[s]
                if (pages.size == 2) {
                    val (left, right) = if (settings.rtl) pages[1] to pages[0] else pages[0] to pages[1]
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        PageImage(src, cache, left, maxW / 2, maxH, ContentScale.Fit, Modifier.weight(1f).fillMaxHeight(), alignment = Alignment.CenterEnd)
                        PageImage(src, cache, right, maxW / 2, maxH, ContentScale.Fit, Modifier.weight(1f).fillMaxHeight(), alignment = Alignment.CenterStart)
                    }
                } else if (settings.fit == "width") {
                    PageImage(src, cache, pages[0], maxW, maxH * 2, ContentScale.FillWidth, Modifier.fillMaxWidth(), tall = true)
                } else {
                    PageImage(src, cache, pages[0], maxW, maxH, ContentScale.Fit, Modifier.fillMaxSize())
                }
            }
        }
    }
    }

    if (chrome) {
        val bar = Color(0xE60B0B0F)
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).background(bar).statusBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChromeButton("‹", big = true) { onClose() }
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(title.substringBeforeLast('.'), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Page ${page + 1} of $count", color = Color(0xAAFFFFFF), fontSize = 12.sp)
            }
            ChromeButton("▦") { sheet = "pages" }
            ChromeButton("☆") { sheet = "bookmarks" }
            ChromeButton("#") { sheet = "jump" }
            ChromeButton("⚙") { sheet = "settings" }
        }
        Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(bar).navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp)) {
            var dragging by remember { mutableStateOf<Float?>(null) }
            if (count > 1) {
                Slider(
                    value = dragging ?: page.toFloat(),
                    valueRange = 0f..(count - 1).toFloat(),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { jump = it.toInt().coerceIn(0, count - 1) }; dragging = null },
                    colors = SliderDefaults.colors(thumbColor = DarkColors.accent, activeTrackColor = DarkColors.accent, inactiveTrackColor = Color(0x33FFFFFF)),
                    modifier = Modifier.graphicsLayer { scaleX = if (settings.rtl && settings.mode != "vertical") -1f else 1f },
                )
            }
            Text(
                "${((dragging ?: page.toFloat()).toInt() + 1)} / $count",
                color = Color(0xCCFFFFFF), fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }

    if (finished) {
        Column(
            Modifier.align(Alignment.Center).padding(24.dp).background(Color(0xFF16161D), RoundedCornerShape(18.dp)).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Has llegado al final", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChromeButton("Continue reading") { finished = false }
                ChromeButton("Volver a la biblioteca", accent = true) { onClose() }
            }
        }
    }

    when (sheet) {
        "bookmarks" -> BookmarkDialog(key, page.toString(), "Page ${page + 1}", onChoose = { saved ->
            saved.toIntOrNull()?.let { jump = it.coerceIn(0, count - 1) }; sheet = null
        }, onClose = { sheet = null })
        "jump" -> ComicSheet("Go to page", onClose = { sheet = null }) {
            var number by remember { mutableStateOf((page + 1).toString()) }
            OutlinedTextField(value = number, onValueChange = { number = it }, label = { Text("Page 1–$count") }, singleLine = true)
            TextButton(enabled = number.toIntOrNull()?.let { it in 1..count } == true, onClick = { number.toIntOrNull()?.let { jump = it - 1 }; sheet = null }) { Text("Go") }
        }
        "settings" -> ComicSheet("Reading", onClose = { sheet = null }) {
            OptionRow("Layout", listOf("paged" to "Single page", "double" to "Double page", "vertical" to "Vertical scroll"), settings.mode) { settings = settings.copy(mode = it) }
            OptionRow("Reading direction", listOf(false to "Left → right", true to "Manga (right → left)"), settings.rtl) { settings = settings.copy(rtl = it) }
            OptionRow("Fit", listOf("screen" to "Fit screen", "width" to "Fit width"), settings.fit) { settings = settings.copy(fit = it) }
            OptionRow("Keep screen awake", listOf(true to "On", false to "Off"), settings.keepAwake) { settings = settings.copy(keepAwake = it) }
        }
        "pages" -> ComicSheet("Pages", onClose = { sheet = null }) {
            val grid = rememberLazyGridState(initialFirstVisibleItemIndex = page)
            LazyVerticalGrid(columns = GridCells.Adaptive(84.dp), state = grid, modifier = Modifier.heightIn(max = 480.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(count) { index ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { jump = index; sheet = null }) {
                        PageImage(
                            src, cache, index, 240, 360, ContentScale.Crop,
                            Modifier.fillMaxWidth().aspectRatio(0.68f).clip(RoundedCornerShape(8.dp))
                                .border(2.dp, if (index == page) DarkColors.accent else Color.Transparent, RoundedCornerShape(8.dp)),
                            thumbnail = true,
                        )
                        Text("${index + 1}", color = if (index == page) DarkColors.accent else Color(0xCCFFFFFF), fontSize = 12.sp)
                    }
                }
            }
        }
    }
    progressError?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color(0xDD600000)).padding(12.dp)) }
}

/** A page that zooms with pinch or double tap; one finger at normal size belongs to the pager. */
@Composable
private fun ZoomPage(
    onTap: (Float, Int) -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    scrollable: Boolean,
    content: @Composable () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(scale > 1f) { onZoomChanged(scale > 1f) }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            offset = if (scale > 1f) {
                                val p = offset + event.calculatePan()
                                val maxX = size.width * (scale - 1) / 2
                                val maxY = size.height * (scale - 1) / 2
                                Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
                            } else Offset.Zero
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tap -> onTap(tap.x, size.width) },
                    onDoubleTap = { tap ->
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero }
                        else {
                            scale = 2.5f
                            offset = (Offset(size.width / 2f, size.height / 2f) - tap) * 1.5f
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val inner = Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale; scaleY = scale
            translationX = offset.x; translationY = offset.y
        }
        if (scrollable) {
            Column(inner.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) { content() }
        } else {
            Box(inner, contentAlignment = Alignment.Center) { content() }
        }
    }
}

@Composable
private fun PageImage(
    src: PageSource,
    cache: LruCache<Int, Bitmap>,
    index: Int,
    maxW: Int,
    maxH: Int,
    contentScale: ContentScale,
    modifier: Modifier,
    alignment: Alignment = Alignment.Center,
    tall: Boolean = false,
    thumbnail: Boolean = false,
) {
    // Thumbnails use their own cache keys (negative) so they never replace full pages.
    val cacheKey = if (thumbnail) -1 - index else index
    var bitmap by remember(src, cacheKey) { mutableStateOf(cache.get(cacheKey) ?: if (thumbnail) cache.get(index) else null) }
    var failed by remember(src, cacheKey) { mutableStateOf(false) }
    LaunchedEffect(src, cacheKey) {
        if (bitmap == null) {
            val decoded = withContext(Dispatchers.IO) { runCatching { src.decode(index, maxW, maxH) }.getOrNull() }
            if (decoded != null) { cache.put(cacheKey, decoded); bitmap = decoded } else failed = true
        }
    }
    val current = bitmap
    when {
        current != null -> Image(
            current.asImageBitmap(), contentDescription = "Page ${index + 1}", contentScale = contentScale, alignment = alignment,
            modifier = if (tall) modifier.aspectRatio(current.width.toFloat() / current.height.coerceAtLeast(1)) else modifier,
        )
        failed -> Box(if (tall) modifier.aspectRatio(0.7f) else modifier, contentAlignment = Alignment.Center) {
            Text("Could not load page ${index + 1}", color = Color(0x99FFFFFF), fontSize = 13.sp)
        }
        else -> Box((if (tall) modifier.aspectRatio(0.7f) else modifier).background(Color(0xFF0E0E12)))
    }
}

@Composable
private fun ChromeButton(label: String, big: Boolean = false, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
            .background(if (accent) DarkColors.accent else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (accent) DarkColors.onAccent else Color.White, fontSize = if (big) 28.sp else 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun <T> OptionRow(label: String, options: List<Pair<T, String>>, current: T, onPick: (T) -> Unit) {
    Text(label, color = Color(0xCCFFFFFF), fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, name) ->
            val on = value == current
            Box(
                Modifier.background(if (on) DarkColors.accent else Color(0x1AFFFFFF), RoundedCornerShape(999.dp))
                    .clickable { onPick(value) }.padding(horizontal = 13.dp, vertical = 8.dp),
            ) {
                Text(name, color = if (on) DarkColors.onAccent else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun BoxScope.ComicSheet(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x88000000)).clickable(onClick = onClose))
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Color(0xFF16161D), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .pointerInput(Unit) { detectTapGestures { } }
            .navigationBarsPadding().padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            ChromeButton("✕") { onClose() }
        }
        content()
    }
}
