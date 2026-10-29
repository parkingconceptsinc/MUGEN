package com.parkingconcepts.mortimer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
internal fun MusicPlaylistDialog(player: Player, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf<List<MusicPlaylist>>(emptyList()) }
    var savedQueue by remember { mutableStateOf<AudioQueue?>(null) }
    var name by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            lists = withContext(Dispatchers.IO) { MusicPlaylists.load(context) }
            loaded = true
            savedQueue = withContext(Dispatchers.IO) { MusicPlaylists.loadQueue(context) }
            message = context.getSharedPreferences("playback_tools", 0).getString("queue_error", null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { message = failure.message }
        finally { busy = false }
    }
    fun update(transform: (List<MusicPlaylist>) -> List<MusicPlaylist>) {
        busy = true
        scope.launch {
            try {
                lists = withContext(Dispatchers.IO) { MusicPlaylists.update(context, transform) }
                editing = null; name = ""; message = "Playlist saved."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message = failure.message ?: "Could not save the playlist." }
            finally { busy = false }
        }
    }
    fun play(queue: AudioQueue, resume: Boolean) {
        try {
            val selected = if (resume) queue.selected else 0
            player.setMediaItems(queue.tracks.map { it.mediaItem() }, selected,
                if (resume) PlaybackPositions.get(context, queue.tracks[selected].uri) else 0)
            player.prepare()
            player.setPlaybackSpeed(context.getSharedPreferences("playback_tools", 0).getFloat("audio_speed", 1f).coerceIn(0.5f, 2f))
            player.play(); onClose()
        } catch (failure: Exception) { message = failure.message ?: "Could not start the playlist. Re-add files if access has changed." }
    }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Music playlists", style = MaterialTheme.typography.titleLarge)
                Text("Save the current audio queue, including its order and repeated tracks.", style = MaterialTheme.typography.bodySmall)
                savedQueue?.let { queue -> TextButton(enabled = !busy, onClick = { play(queue, true) }) { Text("Resume last queue · ${queue.tracks.size} tracks") } }
                OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, label = { Text("Playlist name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row {
                    Button(enabled = loaded && !busy && name.isNotBlank(), onClick = {
                        val label = name.trim()
                        val id = editing
                        if (id != null) update { old -> old.map { if (it.id == id) it.copy(name = label) else it } }
                        else {
                            val queue = snapshotAudioQueue(player)
                            if (queue == null) message = "Start an audio queue before saving a playlist."
                            else update { it + MusicPlaylist(UUID.randomUUID().toString(), label, queue.tracks) }
                        }
                    }) { Text(if (editing == null) "Save current queue" else "Rename playlist") }
                    if (editing != null) TextButton(onClick = { editing = null; name = "" }) { Text("Cancel") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it) }
                if (!busy && loaded && lists.isEmpty()) Text("No saved playlists yet.")
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(lists, key = { it.id }) { list ->
                        Column {
                            Text(list.name, style = MaterialTheme.typography.titleMedium)
                            Text("${list.tracks.size} tracks", style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(enabled = !busy, onClick = { play(AudioQueue(list.tracks), false) }) { Text("Play") }
                                TextButton(enabled = !busy, onClick = { editing = list.id; name = list.name }) { Text("Rename") }
                                TextButton(enabled = !busy, onClick = { update { all -> all.filter { it.id != list.id } } }) { Text("Delete") }
                            }
                        }
                    }
                }
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}
