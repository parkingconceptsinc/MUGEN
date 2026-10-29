package com.parkingconcepts.mortimer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import kotlin.math.roundToLong

@Composable
internal fun StudioTimeField(label: String, value: Long, enabled: Boolean, action: String = "Set", onFocus: () -> Unit = {}, onApply: (Long) -> Unit) {
    var text by remember(value) { mutableStateOf(studioTime(value)) }
    var invalid by remember(value) { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(value = text, onValueChange = { text = it.take(24); invalid = false }, label = { Text(label) },
            singleLine = true, enabled = enabled, isError = invalid,
            supportingText = { if (invalid) Text("Use hh:mm:ss.mmm or seconds") },
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.weight(1f).onFocusChanged { if (it.isFocused) onFocus() },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
        TextButton(enabled = enabled, onClick = { val parsed = parseStudioTime(text); if (parsed == null) invalid = true else onApply(parsed) }) { Text(action) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StudioTrimPanel(clip: StudioClip, sourceHead: Long?, enabled: Boolean, onEdit: (VideoEditSpec) -> Unit, onSourceSeek: (Long) -> Unit, onBeginEdit: () -> Unit = {}) {
    val e = clip.edit
    var range by remember(e.startMs, e.endMs) { mutableStateOf(e.startMs.toFloat()..e.endMs.toFloat()) }
    var message by remember(clip.id) { mutableStateOf<String?>(null) }
    fun trim(start: Long, end: Long) {
        if (start < 0 || end > clip.source.duration || end - start < MIN_TRIM_MS) message = "The range must be inside the source and at least 0.5 seconds long."
        else { message = null; onEdit(e.copy(startMs = start, endMs = end)) }
    }
    Text("Source trim", style = MaterialTheme.typography.titleMedium)
    Text(clip.source.title, style = MaterialTheme.typography.bodySmall)
    Text(if (sourceHead != null) "Source: ${studioTime(sourceHead)} / ${studioTime(clip.source.duration)}" else "Playhead is outside the selected clip", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    RangeSlider(value = range, onValueChange = { next ->
        onBeginEdit()
        val start = clip.source.snap(next.start.toLong()).coerceAtMost(e.endMs - MIN_TRIM_MS)
        val end = clip.source.snap(next.endInclusive.toLong()).coerceAtLeast(start + MIN_TRIM_MS).coerceAtMost(clip.source.duration)
        range = start.toFloat()..end.toFloat()
    }, onValueChangeFinished = { trim(range.start.toLong(), range.endInclusive.toLong()) },
        valueRange = 0f..clip.source.duration.toFloat(), enabled = enabled)
    Text("${studioTime(range.start.toLong())} → ${studioTime(range.endInclusive.toLong())}", fontFamily = FontFamily.Monospace)
    StudioTimeField("In · source time", e.startMs, enabled, onFocus = onBeginEdit) { trim(it, e.endMs) }
    StudioTimeField("Out · source time", e.endMs, enabled, onFocus = onBeginEdit) { trim(e.startMs, it) }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        TextButton(enabled = enabled && sourceHead != null, onClick = { sourceHead?.let { trim(clip.source.snap(it), e.endMs) } }) { Text("Set In at playhead") }
        TextButton(enabled = enabled && sourceHead != null, onClick = { sourceHead?.let { trim(e.startMs, clip.source.snap(it)) } }) { Text("Set Out at playhead") }
    }
    TextButton(enabled = enabled, onClick = { trim(0, clip.source.duration) }) { Text("Restore full range") }
    Row {
        TextButton(enabled = enabled, onClick = { onSourceSeek(e.startMs) }) { Text("Go to In") }
        TextButton(enabled = enabled, onClick = { onSourceSeek(e.endMs - 1) }) { Text("Go to Out") }
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Text("In/Out always use source time. The timeline shows the final project.", style = MaterialTheme.typography.bodySmall)
    Text("Clip speed", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f).forEach { speed ->
            FilterChip(selected = e.speed == speed, enabled = enabled, onClick = { onEdit(e.copy(speed = speed)) }, label = { Text("${speed}×") })
        }
    }
    Text("Pitch preserved · output duration ${studioTime(clip.duration)}", style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun StudioAdjustment(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, onCommit: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label); Text("${(draft * if (range.endInclusive <= 1f) 100 else 1).roundToLong()}%", fontFamily = FontFamily.Monospace)
    }
    Slider(value = draft, onValueChange = { draft = it }, onValueChangeFinished = { onCommit(draft) }, valueRange = range, enabled = enabled)
}

@Composable
internal fun StudioImagePanel(clip: StudioClip, enabled: Boolean, onEdit: (VideoEditSpec) -> Unit) {
    val e = clip.edit
    Text("Transform", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf(0, 90, 180, 270).forEach { rotation ->
            FilterChip(selected = e.rotation == rotation, enabled = enabled, onClick = { onEdit(e.copy(rotation = rotation)) }, label = { Text("$rotation°") })
        }
    }
    Row { Switch(checked = e.flip, enabled = enabled, onCheckedChange = { onEdit(e.copy(flip = it)) }); Text("Mirror", Modifier.padding(12.dp)) }
    HorizontalDivider()
    Text("Color correction", style = MaterialTheme.typography.titleMedium)
    StudioAdjustment("Brightness", e.brightness, -1f..1f, enabled) { onEdit(e.copy(brightness = it)) }
    StudioAdjustment("Contrast", e.contrast, -0.9f..0.9f, enabled) { onEdit(e.copy(contrast = it)) }
    StudioAdjustment("Saturation", e.saturation, -100f..100f, enabled) { onEdit(e.copy(saturation = it)) }
    TextButton(enabled = enabled, onClick = { onEdit(e.copy(rotation = 0, flip = false, brightness = 0f, contrast = 0f, saturation = 0f)) }) { Text("Reset image") }
}

@Composable
internal fun StudioAudioPanel(clip: StudioClip, enabled: Boolean, onEdit: (VideoEditSpec) -> Unit) {
    Text("Clip audio", style = MaterialTheme.typography.titleMedium)
    Row { Switch(checked = clip.edit.mute, enabled = enabled, onCheckedChange = { onEdit(clip.edit.copy(mute = it)) }); Text("Mute clip", Modifier.padding(12.dp)) }
    StudioAdjustment("Volume", clip.edit.volume, 0f..1f, enabled && !clip.edit.mute) { onEdit(clip.edit.copy(volume = it)) }
    Text("Applies to preview and MP4. At 0%, this clip has no audio.", style = MaterialTheme.typography.bodySmall)
}
