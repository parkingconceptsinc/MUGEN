package com.parkingconcepts.mortimer

import android.content.Context
import android.util.AtomicFile
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

internal data class ReadingCheckpoint(val uri: String, val position: String, val progress: Float, val pages: Int)
internal object ReaderCheckpoints {
    private val versions = ConcurrentHashMap<String, Long>()
    private val sequence = AtomicLong()
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "MUGEN-reading-progress").apply { isDaemon = true } }
    fun awaitPendingWrites() { writer.submit {}.get() }
    fun save(context: Context, checkpoint: ReadingCheckpoint): Future<*> {
        val app = context.applicationContext
        val version = sequence.incrementAndGet()
        versions[checkpoint.uri] = version
        return writer.submit {
            if (versions[checkpoint.uri] == version) LibraryStore.saveProgress(app, checkpoint.uri, checkpoint.position, checkpoint.progress, checkpoint.pages)
        }
    }
}

@Composable
internal fun PersistReaderProgress(checkpoint: ReadingCheckpoint): String? {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(checkpoint)
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(checkpoint) {
        delay(350)
        try {
            val future = ReaderCheckpoints.save(context, checkpoint)
            withContext(Dispatchers.IO) { future.get() }
            error = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = "Could not save progress: ${failure.cause?.message ?: failure.message}" }
    }
    DisposableEffect(owner, checkpoint.uri) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) ReaderCheckpoints.save(context, latest)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); ReaderCheckpoints.save(context, latest) }
    }
    return error
}

internal data class ReaderBookmark(val position: String, val label: String)
internal object ReaderBookmarks {
    private fun file(context: Context, uri: String) = File(File(context.filesDir, "bookmarks").apply { mkdirs() }, mediaKey(uri) + ".json")
    @Synchronized fun load(context: Context, uri: String): List<ReaderBookmark> {
        val f = file(context, uri)
        if (!f.exists() && !File(f.path + ".bak").exists()) return emptyList()
        val values = JSONArray(AtomicFile(f).readFully().toString(Charsets.UTF_8))
        require(values.length() <= 500) { "Too many bookmarks." }
        return (0 until values.length()).map { index -> values.getJSONObject(index).let { ReaderBookmark(it.getString("position"), it.getString("label")) } }
    }
    @Synchronized fun update(context: Context, uri: String, transform: (List<ReaderBookmark>) -> List<ReaderBookmark>): List<ReaderBookmark> {
        val values = transform(load(context, uri)).distinctBy { it.position }
        require(values.size <= 500) { "This document already has 500 bookmarks." }
        val json = JSONArray().also { array -> values.forEach { array.put(JSONObject().put("position", it.position).put("label", it.label.take(160))) } }
        val atomic = AtomicFile(file(context, uri))
        val out = atomic.startWrite()
        try { out.write(json.toString().toByteArray()); atomic.finishWrite(out) }
        catch (failure: Throwable) { atomic.failWrite(out); throw failure }
        return values
    }
}

@Composable
internal fun BookmarkDialog(uri: String, position: String, label: String, onChoose: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(emptyList<ReaderBookmark>()) }
    var error by remember { mutableStateOf<String?>(null) }
    fun update(transform: (List<ReaderBookmark>) -> List<ReaderBookmark>) {
        scope.launch {
            try { entries = withContext(Dispatchers.IO) { ReaderBookmarks.update(context, uri, transform) }; error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not save bookmarks." }
        }
    }
    LaunchedEffect(uri) {
        try { entries = withContext(Dispatchers.IO) { ReaderBookmarks.load(context, uri) } }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message }
    }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Bookmarks", style = MaterialTheme.typography.titleLarge)
                Button(onClick = { update { it + ReaderBookmark(position, label) } }) { Text("Bookmark current position") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(entries, key = { it.position }) { entry -> Row(Modifier.fillMaxWidth()) {
                        Text(entry.label, Modifier.weight(1f).clickable { onChoose(entry.position) }.padding(12.dp))
                        TextButton(onClick = { update { list -> list.filter { it.position != entry.position } } }) { Text("Remove") }
                    } }
                }
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}
