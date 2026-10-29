package com.parkingconcepts.mortimer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

internal val StudioAccent = Color(0xFF78DFCF)
internal val StudioPanel = Color(0xFF1A2028)

@Composable
internal fun StudioTimeline(project: StudioProject, selected: String, position: Long, locked: Boolean,
    onSelect: (String, Long) -> Unit, onSeek: (Long) -> Unit, onSplit: () -> Unit, onMove: (Int) -> Unit,
    onDuplicate: () -> Unit, onDelete: () -> Unit, compact: Boolean = false) {
    var zoom by remember { mutableIntStateOf(1) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val index = project.clips.indexOfFirst { it.id == selected }
    Column(Modifier.fillMaxWidth().background(StudioPanel, RoundedCornerShape(10.dp)).padding(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(enabled = !locked, onClick = onSplit) { Text("✂ Dividir") }
            TextButton(enabled = !locked && index > 0, onClick = { onMove(-1) }) { Text("← Mover") }
            TextButton(enabled = !locked && index < project.clips.lastIndex, onClick = { onMove(1) }) { Text("Mover →") }
            TextButton(enabled = !locked && project.clips.size < 64, onClick = onDuplicate) { Text("Duplicar") }
            TextButton(enabled = !locked && project.clips.size > 1, onClick = onDelete) { Text("Eliminar") }
            TextButton(enabled = zoom > 1, onClick = { zoom /= 2 }) { Text("−") }
            Text("${zoom}×", Modifier.padding(top = 15.dp), fontSize = 12.sp)
            TextButton(enabled = zoom < 16, onClick = { zoom *= 2 }) { Text("＋") }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val viewport = maxWidth
            val width = viewport * zoom
            val density = LocalDensity.current
            val px = with(density) { width.toPx() }
            val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.LTGRAY; textSize = with(density) { 10.sp.toPx() } } }
            Column(Modifier.horizontalScroll(scroll)) {
                Canvas(Modifier.width(width).height(24.dp)) {
                    for (tick in 0..(zoom * 4)) {
                        val x = size.width * tick / (zoom * 4)
                        drawLine(Color(0xFF5A6677), Offset(x, size.height - 5), Offset(x, size.height), 1f)
                        if (tick < zoom * 4) drawContext.canvas.nativeCanvas.drawText(studioTime(project.duration * tick / (zoom * 4)).dropLast(4), x + 4, size.height - 8, paint)
                    }
                }
                Row(Modifier.width(width).height(if (compact) 38.dp else 66.dp)) {
                    project.clips.forEachIndexed { i, clip ->
                        val clipWidth = width * (clip.duration.toFloat() / project.duration)
                        Column(Modifier.width(clipWidth).fillMaxHeight().padding(end = 2.dp)
                            .background(if (clip.id == selected) Color(0xFF365967) else Color(0xFF263B51), RoundedCornerShape(4.dp))
                            .border(if (clip.id == selected) 2.dp else 1.dp, if (clip.id == selected) StudioAccent else Color(0xFF425463), RoundedCornerShape(4.dp))
                            .clickable(enabled = !locked) { onSelect(clip.id, project.offset(i)) }.padding(4.dp)) {
                            Text("V1 · ${i + 1} ${clip.source.title}", fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (clipWidth >= 65.dp && !compact) Text("${clip.edit.speed}× · ${studioTime(clip.duration)}", fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1)
                            Spacer(Modifier.weight(1f))
                            Text(if (clip.edit.mute || clip.edit.volume == 0f) "A1 · Silencio" else "A1 · ${(clip.edit.volume * 100).toInt()}%", fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1,
                                modifier = Modifier.fillMaxWidth().background(Color(0xFF204C40)))
                        }
                    }
                }
            }
            Canvas(Modifier.fillMaxWidth().height(if (compact) 62.dp else 90.dp)) {
                val head = px * position.toDouble() / project.duration - scroll.value
                if (head in 0.0..size.width.toDouble()) drawLine(Color.White, Offset(head.toFloat(), 0f), Offset(head.toFloat(), size.height), 2.dp.toPx())
            }
        }
        Slider(value = (position.toDouble() / project.duration).toFloat().coerceIn(0f, 1f),
            onValueChange = { onSeek((it * project.duration).toLong()) }, enabled = !locked,
            modifier = Modifier.fillMaxWidth().height(if (compact) 32.dp else 48.dp), valueRange = 0f..1f)
        if (!compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Cabezal · ${studioTime(position)}", fontSize = 11.sp)
            if (zoom > 1) Row {
                TextButton(onClick = { scope.launch { scroll.animateScrollTo((scroll.value - scroll.viewportSize / 2).coerceAtLeast(0)) } }) { Text("‹ Vista") }
                TextButton(onClick = { scope.launch { scroll.animateScrollTo((scroll.value + scroll.viewportSize / 2).coerceAtMost(scroll.maxValue)) } }) { Text("Vista ›") }
            }
            Text("Total · ${studioTime(project.duration)}", fontSize = 11.sp)
        }
    }
}
