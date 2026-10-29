package com.parkingconcepts.mortimer

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlinx.coroutines.delay

@Composable
internal fun AudioTools(player: Player, uri: String?, position: Long, duration: Long) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("playback_tools", Context.MODE_PRIVATE) }
    var a by remember(uri) { mutableStateOf<Long?>(null) }
    var b by remember(uri) { mutableStateOf<Long?>(null) }
    var deadline by remember { mutableLongStateOf(prefs.getLong("sleep_deadline", 0)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uri) {
        if (prefs.getString("loop_uri", null) == uri) { a = prefs.getLong("loop_a", 0); b = prefs.getLong("loop_b", 0) }
        while (true) { now = System.currentTimeMillis(); deadline = prefs.getLong("sleep_deadline", 0); delay(1000) }
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("A–B repeat", color = LocalMortimerColors.current.text)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(enabled = uri != null, onClick = { a = position; b = null; prefs.edit().remove("loop_uri").apply() }) { Text("A: ${a?.let(::playerTime) ?: "Set here"}") }
            TextButton(enabled = a != null && uri != null, onClick = {
                try {
                    val loop = PlaybackLoop(a!!, position)
                    b = loop.endMs; prefs.edit().putString("loop_uri", uri).putLong("loop_a", loop.startMs).putLong("loop_b", loop.endMs).apply(); error = null
                } catch (failure: Exception) { error = failure.message }
            }) { Text("B: ${b?.let(::playerTime) ?: "Set here"}") }
            TextButton(onClick = { a = null; b = null; prefs.edit().remove("loop_uri").apply() }) { Text("Clear loop") }
        }
        Text(if (deadline > now) "Sleep timer · ${playerTime(deadline - now)} remaining" else "Sleep timer", color = LocalMortimerColors.current.text)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf(15, 30, 60, 90).forEach { minutes -> TextButton(onClick = {
                deadline = System.currentTimeMillis() + minutes * 60_000L; prefs.edit().putLong("sleep_deadline", deadline).apply()
            }) { Text("$minutes min") } }
            TextButton(onClick = { deadline = 0; prefs.edit().remove("sleep_deadline").apply() }) { Text("Off") }
        }
        if (player.playerError != null) TextButton(onClick = { player.prepare(); player.play() }) { Text("Retry playback") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
