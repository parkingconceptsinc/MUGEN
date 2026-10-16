package com.parkingconcepts.mortimer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private fun hzLabel(hz: Int) = if (hz >= 1000) "${hz / 1000}k" else hz.toString()
private fun signed(v: Float, digits: Int = 0) = (if (v > 0) "+" else "") + "%.${digits}f".format(v)

/** Same controls as the PWA's equalizer screen. */
@Composable
fun EqualizerSection(modifier: Modifier) {
    val context = LocalContext.current
    val c = LocalMortimerColors.current
    var s by remember { mutableStateOf(AudioEffects.load(context)) }
    var custom by remember { mutableStateOf(AudioEffects.loadCustomPresets(context)) }
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s) {
        AudioEffects.apply(s)
        AudioEffects.save(context, s)
    }
    val presets = AudioEffects.PRESETS + custom
    val accent = c.accent

    Column(modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Equalizer", color = c.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    if (s.enabled) s.preset + (if (s.preamp != 0f) " · preamp ${signed(s.preamp)} dB" else "") else "Off",
                    color = c.muted, fontSize = 12.sp,
                )
            }
            Switch(
                checked = s.enabled, onCheckedChange = { s = s.copy(enabled = it) },
                colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = c.onAccent),
            )
        }
        if (!AudioEffects.available) {
            Text("This device does not support equalizing the player. Balance and volume boost are still available.", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        }

        Column(Modifier.fillMaxWidth().background(c.panel, RoundedCornerShape(16.dp)).padding(14.dp)) {
            val line = if (s.enabled) accent else c.muted
            Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                val w = size.width
                val h = size.height
                val pts = s.bands.mapIndexed { i, db -> Offset(i / (s.bands.size - 1f) * w, h / 2 - db / AudioEffects.RANGE * (h / 2 - 6)) }
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 0 until pts.size - 1) {
                        val p0 = pts.getOrElse(i - 1) { pts[i] }
                        val p1 = pts[i]
                        val p2 = pts[i + 1]
                        val p3 = pts.getOrElse(i + 2) { pts[i + 1] }
                        cubicTo(p1.x + (p2.x - p0.x) / 6, p1.y + (p2.y - p0.y) / 6, p2.x - (p3.x - p1.x) / 6, p2.y - (p3.y - p1.y) / 6, p2.x, p2.y)
                    }
                }
                val fill = Path().apply { addPath(path); lineTo(w, h); lineTo(0f, h); close() }
                drawLine(c.line, Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 2f)
                drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.4f), line.copy(alpha = 0f))))
                drawPath(path, line, style = Stroke(width = 5f))
            }
            Spacer(Modifier.height(8.dp))
            AudioEffects.BANDS.forEachIndexed { i, hz ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(hzLabel(hz), color = c.muted, fontSize = 12.sp, modifier = Modifier.width(36.dp))
                    Slider(
                        value = s.bands[i], valueRange = -AudioEffects.RANGE..AudioEffects.RANGE,
                        onValueChange = { v -> s = s.copy(enabled = true, preset = "Custom", bands = s.bands.mapIndexed { j, b -> if (j == i) (v * 2).roundToInt() / 2f else b }) },
                        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = c.panel2),
                        modifier = Modifier.weight(1f).height(34.dp),
                    )
                    Text(signed(s.bands[i]), color = c.text, fontSize = 12.sp, modifier = Modifier.width(34.dp).clickable {
                        s = s.copy(preset = "Custom", bands = s.bands.mapIndexed { j, b -> if (j == i) 0f else b })
                    })
                }
            }
        }

        SectionLabel("Presets")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            presets.forEach { (name, bands) ->
                Chip(if (name in custom) "$name  ✕" else name, s.enabled && s.preset == name) {
                    if (name in custom && s.preset == name && s.enabled) deleting = name
                    else s = s.copy(enabled = true, preset = name, bands = bands)
                }
            }
            Chip("＋ Save", false) { naming = true }
        }
        if (custom.isNotEmpty()) Text("Tap a custom preset twice to delete it.", color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))

        SectionLabel("More settings")
        Column(Modifier.fillMaxWidth().background(c.panel, RoundedCornerShape(16.dp)).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("3D virtual surround", color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Wider stereo effect; availability depends on the device.", color = c.muted, fontSize = 11.sp)
                }
                Switch(
                    checked = s.surroundEnabled,
                    onCheckedChange = { s = s.copy(surroundEnabled = it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = c.onAccent),
                )
            }
            if (s.surroundEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Surround strength", color = c.muted, fontSize = 12.sp, modifier = Modifier.width(112.dp))
                    Slider(
                        value = s.surroundStrength,
                        valueRange = 0f..1f,
                        onValueChange = { s = s.copy(surroundStrength = it) },
                        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = c.panel2),
                        modifier = Modifier.weight(1f),
                    )
                    Text("${(s.surroundStrength * 100).roundToInt()}%", color = c.text, fontSize = 12.sp)
                }
            }
            EffectSlider("Preamp", "${signed(s.preamp, 1)} dB", s.preamp, -AudioEffects.RANGE..AudioEffects.RANGE, onReset = { s = s.copy(preamp = 0f) }) {
                s = s.copy(enabled = true, preamp = (it * 2).roundToInt() / 2f)
            }
            EffectSlider(
                "Balance",
                when {
                    abs(s.balance) < 0.01f -> "Center"
                    s.balance < 0 -> "L ${(-s.balance * 100).roundToInt()}%"
                    else -> "R ${(s.balance * 100).roundToInt()}%"
                },
                s.balance, -1f..1f, onReset = { s = s.copy(balance = 0f) },
            ) { s = s.copy(balance = (it * 20).roundToInt() / 20f) }
            EffectSlider("Volume boost", "${(s.boost * 100).roundToInt()}%", s.boost, 1f..2f, onReset = { s = s.copy(boost = 1f) }) {
                s = s.copy(boost = (it * 20).roundToInt() / 20f)
            }
            if (s.boost > 1.3f) Text("High volume boost may distort louder tracks.", color = c.muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(10.dp))
        MenuItem("↺ Reset all effects") { s = AudioEffects.Settings() }
        Text("Tap a band value to reset it to zero. Effects apply to music and videos. Virtual surround is not Dolby-certified 5.1 decoding.", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
    }

    if (naming) {
        var name by remember { mutableStateOf(if (s.preset == "Custom") "My preset" else "${s.preset} (custom)") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save preset") },
            text = { SearchBox(name, "Name", { name = it }) },
            confirmButton = {
                TextButton(onClick = {
                    val n = name.trim()
                    if (n.isNotEmpty() && n !in AudioEffects.PRESETS) {
                        custom = custom + (n to s.bands)
                        AudioEffects.saveCustomPresets(context, custom)
                        s = s.copy(preset = n)
                    }
                    naming = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “$name”? ") },
            confirmButton = {
                TextButton(onClick = {
                    custom = custom - name
                    AudioEffects.saveCustomPresets(context, custom)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

private fun abs(v: Float) = kotlin.math.abs(v)

@Composable
private fun EffectSlider(label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, onReset: () -> Unit, onChange: (Float) -> Unit) {
    val c = LocalMortimerColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = c.accent, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onReset).padding(4.dp))
    }
    Slider(
        value = current, valueRange = range, onValueChange = onChange,
        colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.panel2),
    )
}
