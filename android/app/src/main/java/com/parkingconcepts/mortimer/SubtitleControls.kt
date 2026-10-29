package com.parkingconcepts.mortimer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun SubtitleControls(
    timeline: SubtitleTimeline?, sync: SubtitleSync, enabled: Boolean, position: Long,
    message: String?, onLoad: () -> Unit, onEnabled: (Boolean) -> Unit,
    reference: SubtitleReference?, onReference: (SubtitleReference?) -> Unit,
    playing: Boolean, onTogglePlayback: () -> Unit, onSeek: (Long) -> Unit,
    onSync: (SubtitleSync) -> Unit, onClose: () -> Unit, export: @Composable () -> Unit = {},
) {
    var selected by remember(timeline) { mutableIntStateOf(0) }
    var localError by remember { mutableStateOf<String?>(null) }
    var offset by remember(sync) { mutableStateOf((sync.offsetMs / 1000.0).toString()) }
    fun calibrate(second: Boolean) {
        val cue = timeline?.cues?.getOrNull(selected) ?: return
        try {
            val point = SubtitleReference(cue.startMs, position)
            if (second) onSync(SubtitleSync.calibrate(reference ?: error("Set reference 1 first."), point))
            else { onReference(point); onSync(SubtitleSync.calibrate(point)) }
            localError = null
        } catch (failure: Exception) { localError = failure.message }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Subtitles · SRT / WebVTT", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onLoad) { Text("Choose file") }
                    TextButton(onClick = { onEnabled(!enabled) }) { Text(if (enabled) "Hide subtitles" else "Show subtitles") }
                }
                message?.let { Text(it) }
                export()
                localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Offset: ${sync.offsetMs} ms · timing scale: ${"%.5f".format(sync.scale)}")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(-500L, -100L, 100L, 500L).forEach { change ->
                        TextButton(onClick = { runCatching { onSync(sync.copy(offsetMs = (sync.offsetMs + change).coerceIn(-86_400_000, 86_400_000))) } }) { Text("${if (change > 0) "+" else ""}$change ms") }
                    }
                }
                Row {
                    OutlinedTextField(value = offset, onValueChange = { offset = it }, label = { Text("Offset in seconds (+ = later)") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val value = offset.toDoubleOrNull()
                        if (value == null || !value.isFinite() || value !in -86400.0..86400.0) localError = "Enter seconds between -86400 and 86400."
                        else { onSync(sync.copy(offsetMs = (value * 1000).toLong())); localError = null }
                    }) { Text("Apply") }
                }
                Text("Pause at the moment a phrase is spoken, select that phrase below, then set reference 1. For drift, repeat with a later phrase and reference 2. All phrases adjust automatically.", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { onSeek(-5000) }) { Text("−5 sec") }
                    TextButton(onClick = onTogglePlayback) { Text(if (playing) "Pause" else "Play") }
                    TextButton(onClick = { onSeek(5000) }) { Text("+5 sec") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(enabled = timeline != null, onClick = { calibrate(false) }) { Text("Set reference 1") }
                    TextButton(enabled = reference != null, onClick = { calibrate(true) }) { Text("Set reference 2") }
                    TextButton(onClick = { onReference(null); onSync(SubtitleSync()); localError = null }) { Text("Reset") }
                }
                Text("Video position: ${playerTime(position)}")
                LazyColumn(Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 300.dp)) {
                    itemsIndexed(timeline?.cues.orEmpty()) { index, cue ->
                        ListItem(
                            headlineContent = { Text(cue.text, maxLines = 3) },
                            supportingContent = { Text("${index + 1} · ${playerTime(cue.startMs)}${if (selected == index) " · Selected" else ""}") },
                            modifier = Modifier.fillMaxWidth().clickable { selected = index },
                            colors = ListItemDefaults.colors(containerColor = if (index == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
                        )
                    }
                }
                TextButton(onClick = onClose) { Text("Back to video") }
            }
        }
    }
}
