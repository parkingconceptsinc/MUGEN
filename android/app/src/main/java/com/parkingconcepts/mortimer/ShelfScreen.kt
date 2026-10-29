package com.parkingconcepts.mortimer

import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.text.DateFormat
import java.util.Date

/** Opens a book or comic in the right reader, or in another app when MUGEN cannot read it. */
fun openReader(context: Context, item: LibraryItem, fromStart: Boolean = false, openExternal: (LibraryItem) -> Unit) {
    val intent = when (item.format) {
        "epub", "txt" -> Intent(context, EpubReaderActivity::class.java).apply {
            putExtra(EpubReaderActivity.EXTRA_TITLE, item.title)
            putExtra(EpubReaderActivity.EXTRA_FORMAT, item.format)
            putExtra(EpubReaderActivity.EXTRA_FROM_START, fromStart)
        }
        "cbz", "cbr", "pdf" -> Intent(context, ComicReaderActivity::class.java).apply {
            putExtra(ComicReaderActivity.EXTRA_TITLE, item.title)
            putExtra(ComicReaderActivity.EXTRA_FORMAT, item.format)
            putExtra(ComicReaderActivity.EXTRA_FROM_START, fromStart)
        }
        else -> null
    }
    if (intent == null) { openExternal(item); return }
    intent.data = item.androidUri
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }.onFailure { openExternal(item) }
}

@Composable
fun SearchBox(query: String, placeholder: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalMortimerColors.current
    Row(
        modifier.fillMaxWidth().background(c.panel2, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⌕", color = c.muted, fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(placeholder, color = c.muted, fontSize = 14.sp)
            BasicTextField(
                value = query, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = c.text, fontSize = 14.sp), cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) Text("✕", color = c.muted, fontSize = 14.sp, modifier = Modifier.clickable { onChange("") }.padding(start = 8.dp))
    }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalMortimerColors.current
    Box(
        Modifier.background(if (selected) c.accent else c.panel2, RoundedCornerShape(999.dp)).clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) c.onAccent else c.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Books or comics shelf: continue reading, search, sort, series and a cover grid. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfSection(
    kind: String,
    library: List<LibraryItem>,
    onAddFiles: () -> Unit,
    onAddFolder: () -> Unit,
    onChange: ((List<LibraryItem>) -> List<LibraryItem>) -> Unit,
    openExternal: (LibraryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val c = LocalMortimerColors.current
    val prefs = remember { context.getSharedPreferences("mortimer_prefs", Context.MODE_PRIVATE) }
    var query by remember(kind) { mutableStateOf("") }
    var sort by remember(kind) { mutableStateOf(prefs.getString("shelf_sort", "recent") ?: "recent") }
    var series by remember(kind) { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<LibraryItem?>(null) }
    val noun = if (kind == "books") "books" else "comics"

    val shelf = library.filter { it.kind == kind }
    val folders = remember(shelf) { shelf.map { it.folder }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() } }
    val shown = remember(shelf, query, sort, series) {
        val q = query.trim().lowercase()
        val list = shelf.filter { (series == null || it.folder == series) && (q.isEmpty() || it.title.lowercase().contains(q) || it.folder.lowercase().contains(q)) }
        when (sort) {
            "title" -> list.sortedBy { naturalKey(it.title) }
            "added" -> list.sortedByDescending { it.addedAt }
            else -> list.sortedWith(compareByDescending<LibraryItem> { it.openedAt }.thenByDescending { it.addedAt })
        }
    }
    val reading = remember(shelf) { shelf.filter { it.started }.sortedByDescending { it.openedAt }.take(12) }

    Column(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp)) {
            ActionButton("＋ Add files", primary = true, onClick = onAddFiles)
            ActionButton("▤ Add folder", primary = false, onClick = onAddFolder)
        }
        if (shelf.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp).clip(RoundedCornerShape(20.dp)).background(c.panel).padding(horizontal = 20.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Text(if (kind == "books") "▤" else "▧", color = c.accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                Text(if (kind == "books") "Your bookshelf starts here" else "Build your comic collection", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(
                    if (kind == "books") "Add EPUB, PDF, or TXT files from your phone, SD card, or USB drive. You can also scan an entire folder."
                    else "Add CBZ, CBR, or PDF files, or scan a folder to organize its subfolders as series.",
                    color = c.muted, fontSize = 13.sp, lineHeight = 19.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                ActionButton("＋ Add your first files", primary = true, onClick = onAddFiles)
            }
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(108.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.weight(1f),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SearchBox(query, "Search $noun", { query = it })
            }
            if (reading.isNotEmpty() && query.isEmpty() && series == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        SectionLabel("Continue reading")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(reading, key = { it.uri }) { item ->
                                Box(Modifier.width(112.dp)) {
                                    ShelfCard(item, onOpen = { openReader(context, item, openExternal = openExternal) }, onMenu = { menu = item })
                                }
                            }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    if (folders.size > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                            Chip("All", series == null) { series = null }
                            folders.forEach { f -> Chip(f, series == f) { series = if (series == f) null else f } }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${shown.size} $noun", color = c.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        listOf("recent" to "Recent", "title" to "Title", "added" to "Added").forEach { (value, label) ->
                            Chip(label, sort == value) { sort = value; prefs.edit().putString("shelf_sort", value).apply() }
                        }
                    }
                }
            }
            items(shown, key = { it.uri }) { item ->
                ShelfCard(item, onOpen = { openReader(context, item, openExternal = openExternal) }, onMenu = { menu = item })
            }
        }
    }

    menu?.let { item ->
        ItemMenu(
            item = item,
            onDismiss = { menu = null },
            onOpen = { fromStart -> menu = null; openReader(context, item, fromStart, openExternal) },
            onMark = { read ->
                menu = null
                onChange { list -> list.map { if (it.uri == item.uri) it.copy(progress = if (read) 1f else 0f, position = if (read) (if (it.format == "epub" || it.format == "txt") "${(it.pages - 1).coerceAtLeast(0)}:1" else (it.pages - 1).coerceAtLeast(0).toString()) else "", openedAt = if (read) System.currentTimeMillis() else 0) else it } }
            },
            onMove = {
                menu = null
                onChange { list -> list.map { if (it.uri == item.uri) it.copy(kind = if (it.kind == "books") "comics" else "books") else it } }
            },
            onRemove = {
                menu = null
                Covers.forget(context, item.uri)
                onChange { list -> list.filter { it.uri != item.uri } }
            },
        )
    }
}

@Composable
fun SectionLabel(text: String) {
    val c = LocalMortimerColors.current
    Text(text.uppercase(), color = c.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
}

@Composable
fun ActionButton(label: String, primary: Boolean, onClick: () -> Unit) {
    val c = LocalMortimerColors.current
    Box(
        Modifier.background(if (primary) c.accent else c.panel2, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, color = if (primary) c.onAccent else c.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfCard(item: LibraryItem, onOpen: () -> Unit, onMenu: () -> Unit) {
    val c = LocalMortimerColors.current
    Column(Modifier.combinedClickable(onClick = onOpen, onLongClick = onMenu)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.68f).clip(RoundedCornerShape(10.dp))) {
            CoverImage(item, Modifier.fillMaxSize())
            if (item.finished) {
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(c.accent, CircleShape), contentAlignment = Alignment.Center) {
                    Text("✓", color = c.onAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            } else if (item.progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66000000)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(item.progress.coerceIn(0.02f, 1f)).height(4.dp).background(c.accent))
            }
        }
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(item.title.substringBeforeLast('.'), color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.statusLabel, color = c.muted, fontSize = 11.sp, maxLines = 1)
            }
            Text("⋮", color = c.muted, fontSize = 18.sp, modifier = Modifier.clickable(onClick = onMenu).padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun ItemMenu(
    item: LibraryItem,
    onDismiss: () -> Unit,
    onOpen: (Boolean) -> Unit,
    onMark: (Boolean) -> Unit,
    onMove: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val c = LocalMortimerColors.current
    var details by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().background(c.panel, RoundedCornerShape(20.dp)).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverImage(item, Modifier.width(56.dp).aspectRatio(0.68f).clip(RoundedCornerShape(8.dp)))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(item.title, color = c.text, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(listOf(item.folder, item.statusLabel).filter { it.isNotBlank() }.joinToString(" · "), color = c.muted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (details) {
                DetailLine("Formato", item.format.uppercase())
                if (item.size > 0) DetailLine("Size", Formatter.formatShortFileSize(context, item.size))
                if (item.pages > 0) DetailLine(if (item.format == "epub" || item.format == "txt") "Chapters" else "Pages", item.pages.toString())
                DetailLine("Added", DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.addedAt)))
                if (item.openedAt > 0) DetailLine("Last read", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.openedAt)))
                if (item.folder.isNotBlank()) DetailLine("Series", item.folder)
                Spacer(Modifier.height(8.dp))
                MenuItem("‹ Volver") { details = false }
            } else {
                MenuItem(if (item.started) "▶ Seguir leyendo" else "▶ Leer") { onOpen(false) }
                if (item.openedAt > 0) MenuItem("↺ Leer desde el principio") { onOpen(true) }
                if (item.finished) MenuItem("↶ Mark as unread") { onMark(false) } else MenuItem("✓ Mark as read") { onMark(true) }
                MenuItem(if (item.kind == "books") "⇄ Move to Comics" else "⇄ Move to Books") { onMove() }
                MenuItem("ⓘ Detalles") { details = true }
                MenuItem("🗑 Quitar de la biblioteca", danger = true) { onRemove() }
            }
        }
    }
}

@Composable
fun MenuItem(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = LocalMortimerColors.current
    Text(
        label, color = if (danger) Color(0xFFE5484D) else c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    val c = LocalMortimerColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = c.muted, fontSize = 13.sp, modifier = Modifier.width(110.dp))
        Text(value, color = c.text, fontSize = 13.sp)
    }
}
