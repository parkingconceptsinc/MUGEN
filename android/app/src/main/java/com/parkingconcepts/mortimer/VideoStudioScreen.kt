package com.parkingconcepts.mortimer

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.Executors

/** Serial checkpoints prevent an older background save from overwriting a final lifecycle checkpoint. */
internal object StudioSaves {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "MUGEN-studio-save").apply { isDaemon = true } }
    fun save(context: android.content.Context, project: StudioProject, at: Long, result: (String?) -> Unit = {}) {
        executor.execute {
            val failure = runCatching { StudioProjectStore.save(context, project, at) }.exceptionOrNull()?.let { it.message ?: "No se pudo guardar el proyecto." }
            Handler(Looper.getMainLooper()).post { result(failure) }
        }
    }
    fun await() { executor.submit {}.get() }
}

@UnstableApi
@Composable
internal fun VideoStudioScreen(library: List<LibraryItem>, modifier: Modifier = Modifier,
    initialItem: LibraryItem? = null, initialPosition: Long = 0, onInitialConsumed: () -> Unit = {},
    initialProject: StudioProject? = null, persistProject: Boolean = true, onExit: () -> Unit = {},
    onExportState: (Boolean, Boolean) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preview = remember { StudioPreview(context, scope) }
    var project by remember { mutableStateOf<StudioProject?>(initialProject) }
    var history by remember { mutableStateOf(initialProject?.let(::StudioHistory)) }
    var revision by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(initialProject?.clips?.firstOrNull()?.id.orEmpty()) }
    var tool by remember { mutableStateOf("edit") }
    var mediaOpen by remember { mutableStateOf(false) }
    var projectsOpen by remember { mutableStateOf(false) }
    var newImport by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var saveStatus by remember { mutableStateOf(if (persistProject) "Proyecto guardado automáticamente" else "Vista de prueba") }
    var captions by remember { mutableStateOf(StudioCaptions(null, SubtitleSync(), null)) }
    var preparedSrt by remember { mutableStateOf<Pair<SubtitleTimeline, Set<Uri>>?>(null) }
    var preparingSrt by remember { mutableStateOf(false) }
    var captionRequest by remember { mutableIntStateOf(0) }
    var savingSrt by remember { mutableStateOf(false) }
    var focusPreview by remember { mutableStateOf(false) }
    val compactHeight = LocalConfiguration.current.screenHeightDp < 500
    val latestProject by rememberUpdatedState(project)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val export = rememberStudioExport(project, preview::pause)
    LaunchedEffect(export.busy, export.saving, savingSrt) { onExportState(export.busy, export.saving || savingSrt) }
    DisposableEffect(Unit) { onDispose { onExportState(false, false) } }
    val locked = importing || export.busy || export.saving || savingSrt || preparingSrt
    fun open(value: StudioProject, at: Long = 0) {
        project = value; history = StudioHistory(value); selected = value.clips.first().id
        preview.load(value, at); message = null; preparedSrt = null
    }
    fun change(next: StudioProject) {
        if (locked) return
        try {
            preview.pause(); project = history!!.commit(next); revision++
            selected = selected.takeIf { id -> next.clips.any { it.id == id } } ?: next.clips.first().id
            preview.update(next); saveStatus = if (persistProject) "Guardando proyecto…" else "Vista de prueba"; preparedSrt = null; message = null
        } catch (failure: Exception) { message = failure.message }
    }
    fun import(items: List<LibraryItem>, fresh: Boolean) {
        if (importing || items.isEmpty()) return
        preview.pause(); importing = true; mediaOpen = false
        scope.launch {
            try {
                require(items.size + (if (fresh) 0 else project?.clips?.size ?: 0) <= 64) { "Máximo 64 clips por proyecto." }
                val clips = withContext(Dispatchers.IO) { items.map { item ->
                    currentCoroutineContext().ensureActive()
                    val source = readStudioSource(context, item.androidUri, item.title)
                    val draft = VideoEditDrafts.load(context, item.uri, source.duration)
                    StudioClip(source = source, edit = (draft ?: VideoEditSpec(0, source.duration)).copy(height = 0, aspect = "source"))
                } }
                val current = project
                importing = false
                if (fresh || current == null) open(StudioProject(name = items.first().title.substringBeforeLast('.').take(80).ifBlank { "Proyecto" }, clips = clips).validate(), if (initialItem != null) initialPosition else 0)
                else { change(current.copy(clips = current.clips + clips).validate()); selected = clips.first().id; preview.seek(project!!.offset(current.clips.size)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message = failure.message ?: "No se pudo importar el vídeo." }
            finally { importing = false; onInitialConsumed() }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { persistReadPermission(context, it) }
            scope.launch {
                try { import(withContext(Dispatchers.IO) { itemsFromUris(context, uris, "videos") }, newImport) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { message = failure.message }
            }
        }
    }
    LaunchedEffect(initialItem?.uri) {
        if (initialItem != null) import(listOf(initialItem), true)
        else if (project == null) {
            try {
                val saved = withContext(Dispatchers.IO) { StudioSaves.await(); StudioProjectStore.last(context) }
                saved?.let { open(it.project, it.playhead) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message = failure.message }
        } else if (initialProject != null) preview.load(initialProject)
    }
    LaunchedEffect(project, preview.position / 5000) {
        val p = project ?: return@LaunchedEffect
        if (!persistProject) return@LaunchedEffect
        delay(500)
        StudioSaves.save(context, p, preview.position) { failure ->
            if (latestProject == p) { saveStatus = failure ?: "Proyecto guardado automáticamente"; if (failure != null) message = failure }
        }
    }
    LaunchedEffect(preview) { while (true) { preview.tick(); delay(80) } }
    DisposableEffect(preview, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { preview.pause(); if (persistProject) latestProject?.let { StudioSaves.save(context, it, preview.position) } }
        }
        lifecycle.addObserver(observer)
        onDispose { if (persistProject) latestProject?.let { StudioSaves.save(context, it, preview.position) }; lifecycle.removeObserver(observer); preview.release() }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = StudioAccent, secondary = StudioAccent, tertiary = StudioAccent,
        primaryContainer = Color(0xFF254F4B), secondaryContainer = Color(0xFF254F4B), onSecondaryContainer = Color(0xFFD9FFF8),
        surfaceTint = StudioAccent, outline = Color(0xFF596576), outlineVariant = Color(0xFF394653),
        surface = StudioPanel, background = Color(0xFF10151C))) {
        Surface(modifier, color = Color(0xFF10151C)) {
            Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onExit) { Text("‹") }
                    Column(Modifier.weight(1f)) {
                        Text("MUGEN STUDIO", color = StudioAccent, fontSize = 11.sp, letterSpacing = 2.sp)
                        Text(project?.name ?: "Editor de vídeo", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(enabled = !locked, onClick = { projectsOpen = true; preview.pause() }) { Text("Proyectos") }
                    TextButton(enabled = !locked, onClick = { newImport = project == null; mediaOpen = true; preview.pause() }) { Text("＋ Medios") }
                }
                if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                val p = project
                if (p == null) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Un espacio para montar tus vídeos", style = MaterialTheme.typography.headlineSmall)
                        Text("Importa clips, ordénalos en la línea de tiempo, corta con tiempos precisos y ajusta imagen y sonido desde el inspector.")
                        Button(enabled = !locked, onClick = { newImport = true; mediaOpen = true }) { Text("Crear proyecto") }
                        Text("Los originales se conservan. Los proyectos se guardan automáticamente en este dispositivo.")
                    }
                } else {
                    val clip = p.clips.firstOrNull { it.id == selected } ?: p.clips.first()
                    val location = p.locate(preview.position)
                    val active = p.clips[location.index]
                    StudioCaptionTools(active, location.sourceMs, preview.wantedPlaying,
                        onSeek = { preview.pause(); preview.seek(p.projectTime(active.id, it)) },
                        onPlay = { if (preview.wantedPlaying) preview.pause() else preview.play() }, pause = preview::pause,
                        onLoaded = { captions = it; preparedSrt = null }, openRequest = captionRequest, showButton = false)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled = !locked && history?.canUndo == true, onClick = { project = history!!.undo(); preview.pause(); preview.update(project!!); revision++; preparedSrt = null }) { Text("↶ Deshacer") }
                        TextButton(enabled = !locked && history?.canRedo == true, onClick = { project = history!!.redo(); preview.pause(); preview.update(project!!); revision++; preparedSrt = null }) { Text("↷ Rehacer") }
                        TextButton(enabled = !locked, onClick = { rename = true }) { Text("Renombrar") }
                        Text(saveStatus, fontSize = 10.sp, modifier = Modifier.padding(top = 16.dp))
                    }
                    @Composable fun Viewer(modifier: Modifier) {
                        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                AndroidView(factory = { ctx -> (android.view.LayoutInflater.from(ctx).inflate(R.layout.video_surface, android.widget.FrameLayout(ctx), false) as PlayerView).apply {
                                    player = preview.player; useController = false; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                                } }, onRelease = { it.player = null }, modifier = Modifier.fillMaxSize())
                                if (preview.updating) CircularProgressIndicator(Modifier.size(30.dp), color = StudioAccent)
                                TextButton(onClick = { focusPreview = !focusPreview }, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color(0xCC10151C), RoundedCornerShape(6.dp))) {
                                    Text(if (focusPreview) "Volver a editar" else "Ampliar visor")
                                }
                                preview.error?.let { Text(it, Modifier.padding(16.dp).background(Color(0xDD10151C)), textAlign = TextAlign.Center) }
                                val text = if (captions.enabled) captions.timeline?.textAt(location.sourceMs, captions.sync).orEmpty() else ""
                                if (text.isNotEmpty()) Text(text, Modifier.align(Alignment.BottomCenter).padding(12.dp).background(Color(0xBB000000)).padding(4.dp), color = Color.White, textAlign = TextAlign.Center, maxLines = 4)
                            }
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                TextButton(enabled = !locked, onClick = { preview.pause(); preview.seek(0) }) { Text("|◀") }
                                TextButton(enabled = !locked, onClick = { preview.step(-1) }) { Text("◀ 1f") }
                                Button(enabled = !locked, onClick = { if (preview.wantedPlaying) preview.pause() else preview.play() }) { Text(if (preview.wantedPlaying) "Pausa" else "Reproducir") }
                                TextButton(enabled = !locked, onClick = { preview.step(1) }) { Text("1f ▶") }
                                Text("${studioTime(preview.position)} / ${studioTime(p.duration)}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                FilterChip(selected = preview.loop, enabled = !locked, onClick = { preview.loop = !preview.loop }, label = { Text("Bucle") })
                            }
                        }
                    }
                    @Composable fun Inspector(modifier: Modifier) {
                        Column(modifier.background(StudioPanel, RoundedCornerShape(8.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                listOf("edit" to "Corte", "image" to "Imagen", "audio" to "Audio", "subs" to "CC", "export" to "Exportar").forEach { (id, label) ->
                                    Box(Modifier.weight(1f).height(44.dp).background(if (tool == id) StudioAccent.copy(alpha = .18f) else Color.Transparent, RoundedCornerShape(6.dp))
                                        .selectable(selected = tool == id, enabled = !savingSrt, role = Role.Tab, onClick = { tool = id }), contentAlignment = Alignment.Center) {
                                        Text(label, color = if (tool == id) StudioAccent else Color.LightGray, fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                            }
                            val inspectorScroll = remember(clip.id, tool) { ScrollState(0) }
                            Column(Modifier.weight(1f).studioScrollBar(inspectorScroll).verticalScroll(inspectorScroll).padding(end = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                key(clip.id, tool) {
                                Text("Clip ${p.clips.indexOf(clip) + 1} · ${clip.source.title}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                                val sourceHead = if (active.id == clip.id) location.sourceMs else null
                                when (tool) {
                                    "edit" -> {
                                        StudioTrimPanel(clip, sourceHead, !locked, onEdit = { change(p.replace(clip.id, it)) }, onSourceSeek = { preview.pause(); preview.seek(p.projectTime(clip.id, it)) }, onBeginEdit = preview::pause)
                                        StudioTimeField("Cabezal · tiempo de montaje", preview.position, !locked, action = "Ir", onFocus = preview::pause) { preview.pause(); preview.seek(it) }
                                    }
                                    "image" -> StudioImagePanel(clip, !locked) { change(p.replace(clip.id, it)) }
                                    "audio" -> StudioAudioPanel(clip, !locked) { change(p.replace(clip.id, it)) }
                                    "subs" -> {
                                        Text("Subtítulos del visor", style = MaterialTheme.typography.titleMedium)
                                        Text(active.source.title)
                                        Text("${"%.2f".format(java.util.Locale.ROOT, active.source.fps)} fps${if (active.source.estimatedFps) " estimados" else " nominales"}")
                                        TextButton(enabled = !locked, onClick = { captionRequest++ }) { Text("Cargar / sincronizar subtítulos") }
                                        Text("Las referencias de sincronización usan el tiempo original de la fuente. Exportar permite guardar el SRT del montaje completo.")
                                    }
                                    "export" -> {
                                        StudioExportPanel(p, export, ::change)
                                        HorizontalDivider()
                                        Text("Subtítulos del montaje", style = MaterialTheme.typography.titleSmall)
                                        Text("SRT separado, con recortes, orden, velocidad y sincronización aplicados.", style = MaterialTheme.typography.bodySmall)
                                        TextButton(enabled = !preparingSrt && !locked, onClick = {
                                            preview.pause()
                                            preparingSrt = true
                                            scope.launch {
                                                try { val result = withContext(Dispatchers.IO) { studioSubtitleTimeline(context, p) }; if (latestProject == p) preparedSrt = result }
                                                catch (cancelled: CancellationException) { throw cancelled }
                                                catch (failure: Exception) { message = failure.message }
                                                finally { preparingSrt = false }
                                            }
                                        }) { Text(if (preparingSrt) "Preparando SRT…" else "Preparar SRT") }
                                        preparedSrt?.let { (timeline, protected) -> SaveSubtitleButton(timeline, SubtitleSync(), p.name + ".srt", protected, onSavingChanged = { savingSrt = it }) }
                                    }
                                }
                                }
                            }
                        }
                    }
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val viewerHeight = if (maxHeight > 420.dp) 230.dp else 150.dp
                        if (focusPreview) Viewer(Modifier.fillMaxSize())
                        else if (maxWidth >= 650.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Viewer(Modifier.weight(1f).fillMaxHeight()); Inspector(Modifier.width(320.dp).fillMaxHeight())
                        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Viewer(Modifier.fillMaxWidth().height(viewerHeight))
                            Inspector(Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                    if (focusPreview) Slider(value = (preview.position.toDouble() / p.duration).toFloat().coerceIn(0f, 1f), enabled = !locked,
                        onValueChange = { preview.pause(); preview.seek((it * p.duration).toLong()) }, modifier = Modifier.height(32.dp))
                    else StudioTimeline(p, clip.id, preview.position, locked,
                        onSelect = { id, time -> selected = id; preview.pause(); preview.seek(time) }, onSeek = { preview.pause(); preview.seek(it) },
                        onSplit = { try { val split = p.split(preview.position); change(split); selected = split.clips[split.locate(preview.position).index].id } catch (failure: Exception) { message = failure.message } },
                        onMove = { change(p.move(clip.id, it)) },
                        onDuplicate = { val i = p.clips.indexOf(clip); change(p.copy(clips = p.clips.take(i + 1) + clip.copy(id = UUID.randomUUID().toString()) + p.clips.drop(i + 1))) },
                        onDelete = { change(p.copy(clips = p.clips.filter { it.id != clip.id })) }, compact = compactHeight)
                    if (export.busy || export.saving) Text(if (export.saving) "Guardando MP4…" else "Exportando montaje · ${export.progress}%", color = StudioAccent)
                }
            }
            if (mediaOpen) Dialog(onDismissRequest = { mediaOpen = false }) {
                Surface(shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 550.dp).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (newImport) "Crear proyecto · medios" else "Añadir clips", style = MaterialTheme.typography.titleLarge)
                        Button(onClick = { mediaOpen = false; picker.launch(arrayOf("video/*")) }) { Text("Seleccionar archivos…") }
                        Text("O usa un vídeo de tu biblioteca")
                        LazyColumn(Modifier.weight(1f, false)) { items(library.filter { it.kind == "videos" }, key = { it.uri }) { item -> TextButton(onClick = { import(listOf(item), newImport) }) { Text(item.title, maxLines = 2) } } }
                        TextButton(onClick = { mediaOpen = false }) { Text("Cerrar") }
                    }
                }
            }
            if (projectsOpen) StudioProjectsDialog(project?.id, onOpen = { saved -> open(saved.project, saved.playhead); projectsOpen = false },
                onNew = { projectsOpen = false; newImport = true; mediaOpen = true }, onClose = { projectsOpen = false })
            if (rename && project != null) {
                var name by remember { mutableStateOf(project!!.name) }
                AlertDialog(onDismissRequest = { rename = false }, title = { Text("Nombre del proyecto") },
                    text = { OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, singleLine = true) },
                    confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { change(project!!.copy(name = name.trim())); rename = false }) { Text("Guardar") } },
                    dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancelar") } })
            }
        }
    }
}

private fun Modifier.studioScrollBar(state: ScrollState) = drawWithContent {
    drawContent()
    if (state.maxValue > 0) {
        val h = maxOf(24.dp.toPx(), size.height * size.height / (size.height + state.maxValue)).coerceAtMost(size.height)
        val y = state.value.toFloat() / state.maxValue * (size.height - h)
        drawRect(StudioAccent.copy(alpha = .55f), Offset(size.width - 2.dp.toPx(), y), Size(2.dp.toPx(), h))
    }
}

@Composable
internal fun StudioProjectsDialog(currentId: String?, onOpen: (StudioSavedProject) -> Unit, onNew: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<Pair<String, StudioSavedProject?>>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        try { entries = withContext(Dispatchers.IO) { StudioSaves.await(); StudioProjectStore.list(context).map { it to runCatching { StudioProjectStore.load(context, it) }.getOrNull() } } }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message }
    }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(18.dp)) {
                Text("Proyectos de vídeo", style = MaterialTheme.typography.titleLarge)
                Button(onClick = onNew) { Text("Nuevo proyecto") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.weight(1f, false)) {
                    items(entries, key = { it.first }) { (id, saved) ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(saved?.project?.name ?: "Proyecto ilegible · ${id.take(8)}")
                            saved?.let { Text("${it.project.clips.size} clips · ${studioTime(it.project.duration)}", style = MaterialTheme.typography.bodySmall) }
                            Row {
                                TextButton(enabled = saved != null, onClick = { saved?.let(onOpen) }) { Text("Abrir") }
                                TextButton(enabled = id != currentId, onClick = { removing = id }) { Text("Eliminar") }
                            }
                        }
                    }
                }
                TextButton(onClick = onClose) { Text("Cerrar") }
            }
        }
    }
    removing?.let { id -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("¿Eliminar este proyecto?") },
        text = { Text("Se borrarán sus ajustes y montaje. Los vídeos originales se conservan.") },
        confirmButton = { TextButton(onClick = { scope.launch { try { withContext(Dispatchers.IO) { StudioSaves.await(); StudioProjectStore.delete(context, id) }; refresh++ }
            catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message }; removing = null } }) { Text("Eliminar") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancelar") } }) }
}
