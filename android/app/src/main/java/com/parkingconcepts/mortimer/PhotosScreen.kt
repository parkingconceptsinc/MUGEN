package com.parkingconcepts.mortimer

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PhBg = Color(0xFF07070A)
private val PhPanel = Color(0xFF121218)
private val PhPanel2 = Color(0xFF1A1A22)
private val PhAccent = Color(0xFFFF7849)
private val PhText = Color(0xFFF4F4F6)
private val PhMuted = Color(0xFF8F8F9C)

data class DevicePhoto(
    val id: Long,
    val uri: Uri,
    val name: String,
    val bucket: String,
    val takenAt: Long,
    val width: Int,
    val height: Int,
    val size: Long,
    val mime: String,
)

private fun queryPhotos(context: Context): List<DevicePhoto> {
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.DATE_MODIFIED,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.MIME_TYPE,
    )
    val out = ArrayList<DevicePhoto>()
    context.contentResolver.query(collection, projection, null, null, "${MediaStore.Images.Media.DATE_TAKEN} DESC")?.use { c ->
        val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val name = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val bucket = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        val taken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
        val modified = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
        val width = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
        val height = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
        val size = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
        val mime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
        while (c.moveToNext()) {
            val photoId = c.getLong(id)
            val takenAt = c.getLong(taken).takeIf { it > 0 } ?: (c.getLong(modified) * 1000)
            out.add(
                DevicePhoto(
                    id = photoId,
                    uri = ContentUris.withAppendedId(collection, photoId),
                    name = c.getString(name) ?: "Foto",
                    bucket = c.getString(bucket) ?: "Sin carpeta",
                    takenAt = takenAt,
                    width = c.getInt(width),
                    height = c.getInt(height),
                    size = c.getLong(size),
                    mime = c.getString(mime) ?: "image/*",
                )
            )
        }
    }
    return out.sortedByDescending { it.takenAt }
}

private fun photoPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** "full", "partial" (Android 14 selected photos only) or "none". */
private fun photoAccess(context: Context): String = when {
    Build.VERSION.SDK_INT >= 33 && granted(context, Manifest.permission.READ_MEDIA_IMAGES) -> "full"
    Build.VERSION.SDK_INT >= 34 && granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> "partial"
    Build.VERSION.SDK_INT < 33 && granted(context, Manifest.permission.READ_EXTERNAL_STORAGE) -> "full"
    else -> "none"
}

private fun sharePhotos(context: Context, photos: List<DevicePhoto>) {
    if (photos.isEmpty()) return
    val intent = if (photos.size == 1) {
        Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, photos[0].uri) }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(photos.map { it.uri })) }
    }
    intent.type = "image/*"
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Compartir fotos"))
}

private fun loadFavorites(context: Context): Set<Long> =
    context.getSharedPreferences("mortimer_photos", Context.MODE_PRIVATE)
        .getStringSet("favorites", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

private fun saveFavorites(context: Context, ids: Set<Long>) {
    context.getSharedPreferences("mortimer_photos", Context.MODE_PRIVATE)
        .edit().putStringSet("favorites", ids.map { it.toString() }.toSet()).apply()
}

private val monthFormat = SimpleDateFormat("LLLL yyyy", Locale.getDefault())

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotosSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(photoAccess(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    var photos by remember { mutableStateOf<List<DevicePhoto>?>(null) }
    var favorites by remember { mutableStateOf(loadFavorites(context)) }
    var tab by remember { mutableStateOf("Fotos") }
    var bucket by remember { mutableStateOf<String?>(null) }
    var columns by remember { mutableIntStateOf(4) }
    var selected by remember { mutableStateOf<Set<Long>?>(null) }
    var viewer by remember { mutableStateOf<Pair<List<DevicePhoto>, Int>?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = photoAccess(context)
        refresh++
    }

    LaunchedEffect(access, refresh) {
        photos = if (access == "none") emptyList() else withContext(Dispatchers.IO) { runCatching { queryPhotos(context) }.getOrDefault(emptyList()) }
    }

    fun setFavorite(ids: Collection<Long>, on: Boolean) {
        favorites = if (on) favorites + ids else favorites - ids.toSet()
        saveFavorites(context, favorites)
    }

    Column(modifier.fillMaxWidth()) {
        if (access == "none") {
            Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Tus fotos, ordenadas como un álbum", color = PhText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Mortimer Player lee las fotos del teléfono sin copiarlas. Necesita permiso para verlas.", color = PhMuted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
                Button(onClick = { launcher.launch(photoPermissions()) }, colors = ButtonDefaults.buttonColors(containerColor = PhAccent, contentColor = Color(0xFF111114))) { Text("Permitir acceso a fotos") }
            }
        } else if (photos == null) {
            Text("Cargando fotos…", color = PhMuted, modifier = Modifier.padding(top = 20.dp))
        } else {
        val all = photos!!

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            if (bucket != null) {
                PhotoChip("‹ Álbumes", false) { bucket = null }
                Spacer(Modifier.width(8.dp))
                Text(bucket!!, color = PhText, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            } else {
                listOf("Fotos", "Álbumes", "Favoritos").forEach { name ->
                    PhotoChip(name, tab == name) { tab = name; selected = null }
                    Spacer(Modifier.width(6.dp))
                }
                Spacer(Modifier.weight(1f))
            }
            if (bucket != null || tab != "Álbumes") {
                PhotoChip("−", false) { columns = (columns + 1).coerceAtMost(7) }
                Spacer(Modifier.width(4.dp))
                PhotoChip("+", false) { columns = (columns - 1).coerceAtLeast(2) }
            }
        }

        if (access == "partial") {
            Row(Modifier.fillMaxWidth().background(PhPanel, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Solo ves las fotos que elegiste.", color = PhMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("Elegir más", color = PhAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.clickable { launcher.launch(photoPermissions()) }.padding(6.dp))
            }
            Spacer(Modifier.height(8.dp))
        }

        if (bucket == null && tab == "Álbumes") {
            val albums = remember(all) { all.groupBy { it.bucket }.entries.sortedByDescending { e -> e.value.first().takenAt } }
            LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(albums, key = { it.key }) { (name, list) ->
                    Column(Modifier.clickable { bucket = name }) {
                        AsyncImage(
                            model = list.first().uri, contentDescription = name, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(PhPanel2),
                        )
                        Text(name, color = PhText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        Text("${list.size} fotos", color = PhMuted, fontSize = 12.sp)
                    }
                }
            }
        } else {
        val shown = when {
            bucket != null -> all.filter { it.bucket == bucket }
            tab == "Favoritos" -> all.filter { it.id in favorites }
            else -> all
        }
        if (shown.isEmpty()) {
            Text(if (tab == "Favoritos") "Mantén pulsada una foto o toca el corazón en el visor para guardarla aquí." else "No se encontraron fotos.", color = PhMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 20.dp))
        } else {
        val groups = remember(shown) { shown.groupBy { monthFormat.format(Date(it.takenAt)).replaceFirstChar { c -> c.titlecase() } } }

        Box(Modifier.weight(1f)) {
            LazyVerticalGrid(columns = GridCells.Fixed(columns), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 90.dp)) {
                groups.forEach { (month, list) ->
                    item(key = "h-$month", span = { GridItemSpan(maxLineSpan) }) {
                        Text("$month · ${list.size}", color = PhText, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                    }
                    items(list, key = { it.id }) { photo ->
                        val isSelected = selected?.contains(photo.id) == true
                        Box(
                            Modifier.aspectRatio(1f).background(PhPanel2).combinedClickable(
                                onClick = {
                                    val sel = selected
                                    if (sel != null) selected = if (photo.id in sel) sel - photo.id else sel + photo.id
                                    else viewer = shown to shown.indexOf(photo)
                                },
                                onLongClick = { selected = (selected ?: emptySet()) + photo.id },
                            )
                        ) {
                            AsyncImage(
                                model = photo.uri, contentDescription = photo.name, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().padding(if (isSelected) 8.dp else 0.dp).clip(RoundedCornerShape(if (isSelected) 10.dp else 0.dp)),
                            )
                            if (photo.id in favorites && selected == null) Text("♥", color = Color.White, fontSize = 14.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp))
                            if (selected != null) {
                                Box(
                                    Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp)
                                        .background(if (isSelected) PhAccent else Color(0x55000000), CircleShape)
                                        .border(2.dp, if (isSelected) PhAccent else Color.White, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) { if (isSelected) Text("✓", color = Color(0xFF111114), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }

            val sel = selected
            if (sel != null) {
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).background(PhPanel, RoundedCornerShape(18.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("${sel.size}", color = PhText, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp))
                    PhotoChip("Todas", false) { selected = shown.map { it.id }.toSet() }
                    PhotoChip("♥ Favorito", false) { setFavorite(sel, !sel.all { it in favorites }); selected = null }
                    PhotoChip("Compartir", false) { sharePhotos(context, shown.filter { it.id in sel }); selected = null }
                    PhotoChip("✕", false) { selected = null }
                }
            }
        }
        }
        }
        }
    }

    viewer?.let { (list, start) ->
        PhotoViewerDialog(
            photos = list,
            start = start,
            favorites = favorites,
            onFavorite = { photo -> setFavorite(listOf(photo.id), photo.id !in favorites) },
            onShare = { photo -> sharePhotos(context, listOf(photo)) },
            onClose = { viewer = null },
        )
    }
}

@Composable
private fun PhotoChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(if (selected) PhAccent else PhPanel2, RoundedCornerShape(999.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Color(0xFF111114) else PhText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PhotoViewerDialog(
    photos: List<DevicePhoto>,
    start: Int,
    favorites: Set<Long>,
    onFavorite: (DevicePhoto) -> Unit,
    onShare: (DevicePhoto) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (photos.size - 1).coerceAtLeast(0))) { photos.size }
    var chrome by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    val current = photos.getOrNull(pager.currentPage) ?: return

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, userScrollEnabled = !zoomed, key = { photos[it].id }, modifier = Modifier.fillMaxSize()) { page ->
                ZoomableImage(photos[page], onTap = { chrome = !chrome }, onZoomChanged = { if (page == pager.currentPage) zoomed = it })
            }
            if (chrome) {
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Color(0x99000000)).statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ViewerButton("✕") { onClose() }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(current.takenAt)), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${pager.currentPage + 1} de ${photos.size} · ${current.name}", color = Color(0xAAFFFFFF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ViewerButton(if (current.id in favorites) "♥" else "♡", tint = if (current.id in favorites) PhAccent else Color.White) { onFavorite(current) }
                    ViewerButton("ⓘ", tint = if (info) PhAccent else Color.White) { info = !info }
                    ViewerButton("⤴") { onShare(current) }
                }
                LazyRow(
                    Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Color(0x99000000)).navigationBarsPadding().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(photos.size) { n ->
                        AsyncImage(
                            model = photos[n].uri, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                                .border(2.dp, if (n == pager.currentPage) PhAccent else Color.Transparent, RoundedCornerShape(8.dp))
                                .graphicsLayer { alpha = if (n == pager.currentPage) 1f else 0.6f },
                        )
                    }
                }
            }
            if (info) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 76.dp).navigationBarsPadding()
                        .background(PhBg, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).padding(18.dp),
                ) {
                    Text("Detalles", color = PhText, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.height(10.dp))
                    InfoLine("Archivo", current.name)
                    InfoLine("Fecha", DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(Date(current.takenAt)))
                    if (current.width > 0) InfoLine("Tamaño", "${current.width} × ${current.height} · ${"%.1f".format(current.width * current.height / 1_000_000f)} MP")
                    InfoLine("Peso", Formatter.formatShortFileSize(context, current.size))
                    InfoLine("Álbum", current.bucket)
                    InfoLine("Formato", current.mime)
                }
            }
        }
    }
}

@Composable
private fun ViewerButton(label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = tint, fontSize = 20.sp)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = PhMuted, fontSize = 13.sp, modifier = Modifier.width(80.dp))
        Text(value, color = PhText, fontSize = 13.sp)
    }
}

@Composable
private fun ZoomableImage(photo: DevicePhoto, onTap: () -> Unit, onZoomChanged: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(scale > 1f) { onZoomChanged(scale > 1f) }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(photo.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        // One finger at normal size belongs to the pager; pinches and zoomed pans belong here.
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
            .pointerInput(photo.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            offset = (center - tap) * 1.5f
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = photo.uri,
            contentDescription = photo.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        )
    }
}
