package com.parkingconcepts.mortimer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipFile

/** Reading themes, the same four the PWA's book reader offers. */
private data class BookTheme(val label: String, val bg: Color, val fg: Color, val link: String, val css: String, val cssFg: String)

private val BOOK_THEMES = linkedMapOf(
    "dark" to BookTheme("Oscuro", Color(0xFF121218), Color(0xFFE8E6E3), "#ff9a76", "#121218", "#e8e6e3"),
    "black" to BookTheme("Negro", Color(0xFF000000), Color(0xFFD6D6D6), "#ff9a76", "#000000", "#d6d6d6"),
    "sepia" to BookTheme("Sepia", Color(0xFFF4ECD8), Color(0xFF5B4636), "#a0522d", "#f4ecd8", "#5b4636"),
    "light" to BookTheme("Claro", Color(0xFFFFFFFF), Color(0xFF1A1A1A), "#c94f2d", "#ffffff", "#1a1a1a"),
)

private val BOOK_FONTS = linkedMapOf(
    "publisher" to "Original",
    "serif" to "Serif",
    "sans" to "Sans",
)

private data class BookSettings(
    val fontSize: Int = 110,
    val font: String = "publisher",
    val lineHeight: Float = 1.6f,
    val theme: String = "dark",
    val paged: Boolean = true,
) {
    fun save(context: Context) {
        context.getSharedPreferences("mortimer_prefs", Context.MODE_PRIVATE).edit()
            .putInt("book_font_size", fontSize).putString("book_font", font).putFloat("book_line_height", lineHeight)
            .putString("book_theme", theme).putBoolean("book_paged", paged).apply()
    }

    companion object {
        fun load(context: Context): BookSettings {
            val p = context.getSharedPreferences("mortimer_prefs", Context.MODE_PRIVATE)
            return BookSettings(
                fontSize = p.getInt("book_font_size", 110).coerceIn(70, 220),
                font = p.getString("book_font", "publisher")?.takeIf { it in BOOK_FONTS } ?: "publisher",
                lineHeight = p.getFloat("book_line_height", 1.6f).coerceIn(1.1f, 2.2f),
                theme = p.getString("book_theme", "dark")?.takeIf { it in BOOK_THEMES } ?: "dark",
                paged = p.getBoolean("book_paged", true),
            )
        }
    }
}

private fun readerCss(s: BookSettings): String {
    val t = BOOK_THEMES[s.theme] ?: BOOK_THEMES.getValue("dark")
    val family = when (s.font) {
        "serif" -> "font-family: Georgia, 'Noto Serif', serif !important;"
        "sans" -> "font-family: Roboto, 'Noto Sans', sans-serif !important;"
        else -> ""
    }
    val base = """
        html { background: ${t.css} !important; -webkit-text-size-adjust: 100%; }
        body { background: ${t.css} !important; color: ${t.cssFg} !important; font-size: ${s.fontSize}% !important; line-height: ${s.lineHeight} !important; $family
               margin: 0 !important; padding: 28px 22px 36px !important; box-sizing: border-box !important; overflow-wrap: break-word; }
        p, div, span, li, h1, h2, h3, h4, h5, h6, blockquote, td, th, em, strong, i, b, small, section, article { color: inherit !important; background-color: transparent !important; $family }
        p, li { line-height: inherit !important; }
        a { color: ${t.link} !important; }
        img, svg, video, image { max-width: 100% !important; height: auto !important; }
        pre { white-space: pre-wrap !important; }
    """.trimIndent()
    val paged = """
        html { height: 100vh !important; overflow: hidden !important; }
        body { height: 100vh !important; column-width: calc(100vw - 44px) !important; column-gap: 44px !important; column-fill: auto !important; }
        img, svg, video { max-height: calc(100vh - 72px) !important; object-fit: contain; break-inside: avoid; }
    """.trimIndent()
    return if (s.paged) base + "\n" + paged else base
}

/** Injected into every chapter: paging, taps, swipes and position reports. */
private const val READER_JS = """
(function(){
  var M = window.Mortimer; if (!M || window.__mortimer) return; window.__mortimer = true;
  var paged = false;
  function W(){ return window.innerWidth; }
  function sw(){ return document.documentElement.scrollWidth; }
  function maxX(){ return Math.max(0, sw() - W()); }
  function maxY(){ return Math.max(0, document.documentElement.scrollHeight - window.innerHeight); }
  function report(){
    if (paged) { var m = maxX(); var pages = Math.max(1, Math.round(sw() / W())); M.position(m > 0 ? window.scrollX / m : 1, Math.round(window.scrollX / W()), pages); }
    else { var h = maxY(); M.position(h > 0 ? window.scrollY / h : 1, 0, 0); }
  }
  window.mortimerCss = function(css){ var s = document.getElementById('mortimer-style'); if (!s) { s = document.createElement('style'); s.id = 'mortimer-style'; (document.head || document.documentElement).appendChild(s); } s.textContent = css; };
  window.mortimerSetup = function(isPaged, f, anchor){
    paged = isPaged;
    setTimeout(function(){
      var el = anchor ? document.getElementById(anchor) : null;
      if (el) {
        var r = el.getBoundingClientRect();
        if (paged) window.scrollTo(Math.floor((r.left + window.scrollX) / W()) * W(), 0); else window.scrollTo(0, r.top + window.scrollY);
        report(); return;
      }
      window.mortimerGo(f);
    }, 60);
  };
  window.mortimerGo = function(f){
    if (paged) { var pages = Math.max(1, Math.round(sw() / W())); var p = f >= 0.999 ? pages - 1 : Math.min(pages - 1, Math.floor(f * pages + 0.0001)); window.scrollTo(p * W(), 0); }
    else window.scrollTo(0, f * maxY());
    report();
  };
  window.mortimerPage = function(dir){
    if (!paged) {
      var y = window.scrollY;
      if (dir > 0 && y >= maxY() - 2) { M.edge(1); return; }
      if (dir < 0 && y <= 2) { M.edge(-1); return; }
      window.scrollTo(0, Math.max(0, Math.min(maxY(), y + dir * window.innerHeight * 0.9))); report(); return;
    }
    var x = Math.round(window.scrollX / W()) * W() + dir * W();
    if (x < 0) { M.edge(-1); return; }
    if (x > maxX() + 2) { M.edge(1); return; }
    window.scrollTo(x, 0); report();
  };
  var sx = 0, sy = 0, st = 0;
  document.addEventListener('touchstart', function(e){ var t = e.touches[0]; sx = t.clientX; sy = t.clientY; st = Date.now(); }, { passive: true });
  document.addEventListener('touchend', function(e){
    var t = e.changedTouches[0]; var dx = t.clientX - sx, dy = t.clientY - sy;
    if (paged && Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy)) { window.mortimerPage(dx < 0 ? 1 : -1); return; }
    if (Math.abs(dx) < 12 && Math.abs(dy) < 12 && Date.now() - st < 450) {
      if (e.target && e.target.closest && e.target.closest('a[href]')) return;
      var r = t.clientX / window.innerWidth; M.tap(r < 0.28 ? -1 : (r > 0.72 ? 1 : 0));
    }
  }, { passive: true });
  var timer = null;
  window.addEventListener('scroll', function(){ if (timer) clearTimeout(timer); timer = setTimeout(report, 160); });
  window.addEventListener('resize', function(){ if (paged) { var p = Math.round(window.scrollX / W()); window.scrollTo(p * W(), 0); } report(); });
})();
"""

/** An opened book: chapters in reading order and a way to fetch any file inside it. */
private class OpenedBook(
    val title: String,
    val chapters: List<String>,
    val toc: List<EpubPackage.TocEntry>,
    private val zip: ZipFile?,
    private val file: File?,
    private val inline: Map<String, ByteArray>,
) : Closeable {
    private var closed = false
    @Synchronized
    fun read(path: String): ByteArray? {
        if (closed) return null
        inline[path]?.let { return it }
        val z = zip ?: return null
        val entry = z.getEntry(path) ?: return null
        return z.getInputStream(entry).use { it.readBytesLimited(MAX_BOOK_RESOURCE_BYTES) }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { zip?.close() }
        file?.delete()
    }
}

private fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun decodeText(bytes: ByteArray): String {
    val utf = String(bytes, Charsets.UTF_8)
    if (!utf.contains('�')) return utf.removePrefix(0xFEFF.toChar().toString())
    return String(bytes, Charset.forName("windows-1252"))
}

private suspend fun openBook(context: Context, uri: Uri, title: String, format: String): OpenedBook {
    val jobContext = currentCoroutineContext()
    if (format == "txt") {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytesLimited(12 * 1024 * 1024) { jobContext.ensureActive() }
        } ?: error("Could not read the file.")
        val text = decodeText(bytes).replace("\r\n", "\n")
        val body = text.split(Regex("\n\\s*\n")).joinToString("\n") { para ->
            "<p>" + escapeHtml(para.trim()).replace("\n", "<br>") + "</p>"
        }
        val html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>${escapeHtml(title)}</title></head><body>$body</body></html>"
        return OpenedBook(title, listOf("texto.html"), emptyList(), null, null, mapOf("texto.html" to html.toByteArray()))
    }
    val file = File.createTempFile("book_", ".epub", context.cacheDir)
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyLimitedTo(output, MAX_CACHE_BYTES, { jobContext.ensureActive() }) }
        } ?: error("Could not read the EPUB file.")
    } catch (failure: Throwable) { file.delete(); throw failure }
    val zip = try { ZipFile(file) } catch (e: Exception) { file.delete(); error("This is not a valid EPUB file.") }
    try {
        val pkg = EpubPackage.read(zip)
        check(pkg.spine.isNotEmpty()) { "This EPUB contains no chapters." }
        return OpenedBook(pkg.title ?: title, pkg.spine, pkg.toc, zip, file, emptyMap())
    } catch (e: Exception) {
        zip.close(); file.delete()
        throw e
    }
}

private fun mimeFor(path: String): String = when (extensionOf(path)) {
    "xhtml", "xht", "xml" -> "application/xhtml+xml"
    "html", "htm" -> "text/html"
    "css" -> "text/css"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "ttf" -> "font/ttf"
    "otf" -> "font/otf"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    else -> "application/octet-stream"
}

private const val BOOK_HOST = "book.local"

class EpubReaderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: displayName(this, uri, "Book")
        val format = intent.getStringExtra(EXTRA_FORMAT) ?: formatOf(title, "books")
        val fromStart = intent.getBooleanExtra(EXTRA_FROM_START, false)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        val insets = WindowInsetsControllerCompat(window, window.decorView)
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        setContent {
            MortimerTheme("dark") {
                BookReader(uri, title, format, fromStart, onClose = { finish() }, onChrome = { show ->
                    if (show) insets.show(WindowInsetsCompat.Type.systemBars()) else insets.hide(WindowInsetsCompat.Type.systemBars())
                }, onLightTheme = { light ->
                    insets.isAppearanceLightStatusBars = light
                    insets.isAppearanceLightNavigationBars = light
                })
            }
        }
    }

    companion object {
        const val EXTRA_TITLE = "book_title"
        const val EXTRA_FORMAT = "book_format"
        const val EXTRA_FROM_START = "book_from_start"
    }
}

private class ReaderBridge(
    val onPosition: (Float, Int, Int) -> Unit,
    val onEdge: (Int) -> Unit,
    val onTap: (Int) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    @JavascriptInterface fun position(fraction: Double, page: Int, pages: Int) { main.post { onPosition(fraction.toFloat().coerceIn(0f, 1f), page, pages) } }
    @JavascriptInterface fun edge(dir: Int) { main.post { onEdge(dir) } }
    @JavascriptInterface fun tap(zone: Int) { main.post { onTap(zone) } }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun BookReader(
    uri: Uri,
    fallbackTitle: String,
    format: String,
    fromStart: Boolean,
    onClose: () -> Unit,
    onChrome: (Boolean) -> Unit,
    onLightTheme: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val key = uri.toString()
    var book by remember { mutableStateOf<OpenedBook?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(BookSettings.load(context)) }
    val theme = BOOK_THEMES[settings.theme] ?: BOOK_THEMES.getValue("dark")

    // Where we are: chapter index and fraction through it.
    var chapter by remember { mutableIntStateOf(0) }
    var fraction by remember { mutableFloatStateOf(0f) }
    var pageInfo by remember { mutableStateOf(0 to 0) }
    var chrome by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf(false) }
    // Fraction/anchor to apply when the next chapter finishes loading.
    val pending = remember { arrayOf<Any?>(0f, null) }

    LaunchedEffect(key) {
        val opened = runCatching { openResourceOnIo { openBook(context, uri, fallbackTitle, format) } }
        opened.onSuccess { b ->
            val saved = if (fromStart) null else LibraryStore.find(context, key)?.position
            val parts = saved?.split(':')
            chapter = parts?.getOrNull(0)?.toIntOrNull()?.coerceIn(0, b.chapters.lastIndex) ?: 0
            fraction = parts?.getOrNull(1)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
            pending[0] = fraction
            book = b
        }.onFailure { if (it is CancellationException) throw it; error = it.message ?: "Could not open this book." }
    }
    DisposableEffect(book) {
        val b = book
        onDispose { b?.close() }
    }
    LaunchedEffect(chrome) { onChrome(chrome) }
    LaunchedEffect(settings.theme) { onLightTheme(settings.theme == "sepia" || settings.theme == "light") }

    // Save progress shortly after the position settles.
    LaunchedEffect(chapter, fraction, book) {
        val b = book ?: return@LaunchedEffect
        delay(400)
        val overall = (chapter + fraction) / b.chapters.size
        withContext(Dispatchers.IO) {
            runCatching { LibraryStore.saveProgress(context, key, "$chapter:${"%.4f".format(java.util.Locale.US, fraction)}", if (chapter == b.chapters.lastIndex && fraction >= 0.999f) 1f else overall, b.chapters.size) }
        }
    }

    BackHandler(enabled = sheet != null) { sheet = null }

    Box(Modifier.fillMaxSize().background(theme.bg)) {
        val b = book
        when {
            error != null -> Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error!!, color = theme.fg, fontSize = 16.sp)
                Spacer(Modifier.height(16.dp))
                ReaderButton("Volver", theme.fg) { onClose() }
            }
            b == null -> Text("Abriendo $fallbackTitle…", color = theme.fg, modifier = Modifier.align(Alignment.Center))
            else -> {
                val webView = remember(b) { WebView(context) }
                val currentSettings by rememberUpdatedState(settings)

                fun load(index: Int, startFraction: Float, anchor: String? = null) {
                    finished = false
                    chapter = index.coerceIn(0, b.chapters.lastIndex)
                    fraction = startFraction
                    pending[0] = startFraction
                    pending[1] = anchor
                    webView.loadUrl("https://$BOOK_HOST/" + Uri.encode(b.chapters[chapter], "/") + (anchor?.let { "#$it" } ?: ""))
                }
                fun turn(dir: Int) { webView.evaluateJavascript("window.mortimerPage && window.mortimerPage($dir)", null) }

                val bridge = remember(b) {
                    ReaderBridge(
                        onPosition = { f, page, pages -> fraction = f; pageInfo = page to pages },
                        onEdge = { dir ->
                            if (dir > 0) { if (chapter < b.chapters.lastIndex) load(chapter + 1, 0f) else finished = true }
                            else if (chapter > 0) load(chapter - 1, 1f)
                        },
                        onTap = { zone -> if (zone == 0) chrome = !chrome else { turn(zone); if (chrome) chrome = false } },
                    )
                }

                DisposableEffect(webView) {
                    webView.settings.javaScriptEnabled = true
                    webView.settings.allowFileAccess = false
                    webView.settings.allowContentAccess = false
                    webView.settings.textZoom = 100
                    webView.isVerticalScrollBarEnabled = false
                    webView.isHorizontalScrollBarEnabled = false
                    webView.setBackgroundColor(AndroidColor.TRANSPARENT)
                    webView.addJavascriptInterface(bridge, "Mortimer")
                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val url = request.url
                            if (url.host != BOOK_HOST) return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                            val path = (url.path ?: "").removePrefix("/")
                            val bytes = try { b.read(path) } catch (_: Exception) {
                                return WebResourceResponse("text/plain", "utf-8", 413, "Resource too large or unreadable", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                            } ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                            val mime = mimeFor(path)
                            if (mime == "application/xhtml+xml" || mime == "text/html") {
                                var html = String(bytes, Charsets.UTF_8)
                                    .replace(Regex("(?is)<script\\b[^>]*/>"), "")
                                    .replace(Regex("(?is)<script\\b.*?</script>"), "")
                                val xml = mime == "application/xhtml+xml"
                                val css = readerCss(currentSettings)
                                val inject = if (xml) "<style id=\"mortimer-style\"><![CDATA[\n$css\n]]></style><script><![CDATA[\n$READER_JS\n]]></script>"
                                    else "<style id=\"mortimer-style\">$css</style><script>$READER_JS</script>"
                                val head = Regex("(?i)</head>").find(html)
                                html = if (head != null) html.substring(0, head.range.first) + inject + html.substring(head.range.first)
                                    else Regex("(?i)<body[^>]*>").find(html)?.let { m -> html.substring(0, m.range.last + 1) + inject + html.substring(m.range.last + 1) } ?: (inject + html)
                                return WebResourceResponse(mime, "utf-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
                            }
                            return WebResourceResponse(mime, null, ByteArrayInputStream(bytes))
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url
                            if (url.host == BOOK_HOST) {
                                val path = (url.path ?: "").removePrefix("/")
                                val index = b.chapters.indexOf(path)
                                if (index >= 0) { load(index, 0f, url.fragment); return true }
                                return false
                            }
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                            return true
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            val path = Uri.parse(url).path?.removePrefix("/") ?: ""
                            val index = b.chapters.indexOf(path)
                            if (index >= 0 && index != chapter) chapter = index
                            val anchor = pending[1] as String?
                            val f = pending[0] as Float
                            view.evaluateJavascript(
                                "window.mortimerSetup && window.mortimerSetup(${currentSettings.paged}, $f, ${if (anchor != null) JSONObject.quote(anchor) else "null"})",
                                null,
                            )
                            pending[1] = null
                        }
                    }
                    load(chapter, fraction)
                    onDispose {
                        webView.removeJavascriptInterface("Mortimer")
                        webView.destroy()
                    }
                }

                // Restyle the open chapter when settings change, keeping the reading position.
                LaunchedEffect(settings) {
                    settings.save(context)
                    webView.setBackgroundColor(AndroidColor.TRANSPARENT)
                    val f = fraction
                    pending[0] = f
                    webView.evaluateJavascript(
                        "window.mortimerCss && (window.mortimerCss(${JSONObject.quote(readerCss(settings))}), window.mortimerSetup(${settings.paged}, $f, null))",
                        null,
                    )
                }

                AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize().systemBarsPadding())

                if (chrome) {
                    val panel = theme.bg.copy(alpha = 0.96f)
                    Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).background(panel).statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ReaderButton("‹", theme.fg, big = true) { onClose() }
                            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                                Text(b.title, color = theme.fg, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val label = b.toc.lastOrNull { b.chapters.indexOf(it.href.substringBefore('#')) in 0..chapter }?.label
                                Text(label ?: "Chapter ${chapter + 1} of ${b.chapters.size}", color = theme.fg.copy(alpha = 0.65f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (b.toc.isNotEmpty()) ReaderButton("☰", theme.fg) { sheet = "toc" }
                            ReaderButton("Aa", theme.fg) { sheet = "settings" }
                        }
                    }
                    Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(panel).navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp)) {
                        val overall = ((chapter + fraction) / b.chapters.size).coerceIn(0f, 1f)
                        var dragging by remember { mutableStateOf<Float?>(null) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ReaderButton("‹", theme.fg) { turn(-1) }
                            Slider(
                                value = dragging ?: overall,
                                onValueChange = { dragging = it },
                                onValueChangeFinished = {
                                    val v = (dragging ?: overall) * b.chapters.size
                                    dragging = null
                                    val target = v.toInt().coerceIn(0, b.chapters.lastIndex)
                                    val f = (v - target).coerceIn(0f, 1f)
                                    if (target == chapter) { pending[0] = f; webView.evaluateJavascript("window.mortimerGo && window.mortimerGo($f)", null) } else load(target, f)
                                },
                                colors = SliderDefaults.colors(thumbColor = DarkColors.accent, activeTrackColor = DarkColors.accent, inactiveTrackColor = theme.fg.copy(alpha = 0.2f)),
                                modifier = Modifier.weight(1f),
                            )
                            ReaderButton("›", theme.fg) { turn(1) }
                        }
                        val pageText = if (settings.paged && pageInfo.second > 0) " · page ${pageInfo.first + 1} of ${pageInfo.second}" else ""
                        Text("${((dragging ?: overall) * 100).toInt()}%$pageText", color = theme.fg.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                }

                if (finished) {
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp).background(theme.bg, RoundedCornerShape(18.dp)).padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("You have finished this book", color = theme.fg, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ReaderButton("Continue reading", theme.fg) { finished = false }
                            ReaderButton("Volver a la biblioteca", DarkColors.accent) { onClose() }
                        }
                    }
                }

                when (sheet) {
                    "toc" -> ReaderSheet("Contenido", theme, onClose = { sheet = null }) {
                        LazyColumn(Modifier.heightIn(max = 460.dp)) {
                            itemsIndexed(b.toc) { _, entry ->
                                val index = b.chapters.indexOf(entry.href.substringBefore('#'))
                                Text(
                                    entry.label,
                                    color = if (index == chapter) DarkColors.accent else theme.fg,
                                    fontSize = 15.sp,
                                    fontWeight = if (index == chapter) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.fillMaxWidth().clickable(enabled = index >= 0) {
                                        sheet = null
                                        load(index, 0f, entry.href.substringAfter('#', "").ifEmpty { null })
                                    }.padding(start = (12 * entry.depth).dp, top = 10.dp, bottom = 10.dp),
                                )
                            }
                        }
                    }
                    "settings" -> ReaderSheet("Texto", theme, onClose = { sheet = null }) {
                        Text("Text size · ${settings.fontSize}%", color = theme.fg, fontSize = 13.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ReaderButton("A−", theme.fg) { settings = settings.copy(fontSize = (settings.fontSize - 10).coerceAtLeast(70)) }
                            Slider(
                                value = settings.fontSize.toFloat(), valueRange = 70f..220f,
                                onValueChange = { settings = settings.copy(fontSize = (it / 5).toInt() * 5) },
                                colors = SliderDefaults.colors(thumbColor = DarkColors.accent, activeTrackColor = DarkColors.accent, inactiveTrackColor = theme.fg.copy(alpha = 0.2f)),
                                modifier = Modifier.weight(1f),
                            )
                            ReaderButton("A+", theme.fg) { settings = settings.copy(fontSize = (settings.fontSize + 10).coerceAtMost(220)) }
                        }
                        ChoiceRow("Letra", BOOK_FONTS.toList(), settings.font, theme) { settings = settings.copy(font = it) }
                        ChoiceRow("Interlineado", listOf(1.3f to "1.3", 1.6f to "1.6", 1.9f to "1.9", 2.2f to "2.2"), settings.lineHeight, theme) { settings = settings.copy(lineHeight = it) }
                        ChoiceRow("Tema", BOOK_THEMES.map { it.key to it.value.label }, settings.theme, theme) { settings = settings.copy(theme = it) }
                        ChoiceRow("Reading mode", listOf(true to "Paged", false to "Continuous"), settings.paged, theme) { settings = settings.copy(paged = it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderButton(label: String, color: Color, big: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.defaultMinSize(minWidth = 42.dp, minHeight = 42.dp).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = if (big) 28.sp else 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun <T> ChoiceRow(label: String, options: List<Pair<T, String>>, current: T, theme: BookTheme, onPick: (T) -> Unit) {
    Text(label, color = theme.fg, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, name) ->
            val on = value == current
            Box(
                Modifier.background(if (on) DarkColors.accent else theme.fg.copy(alpha = 0.08f), RoundedCornerShape(999.dp))
                    .clickable { onPick(value) }.padding(horizontal = 13.dp, vertical = 8.dp),
            ) {
                Text(name, color = if (on) DarkColors.onAccent else theme.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun BoxScope.ReaderSheet(title: String, theme: BookTheme, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(onClick = onClose))
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(theme.bg, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .pointerInput(Unit) { detectTapGestures { } }
            .navigationBarsPadding().padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = theme.fg, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            ReaderButton("✕", theme.fg) { onClose() }
        }
        content()
    }
}
