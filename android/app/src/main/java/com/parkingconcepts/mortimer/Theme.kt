package com.parkingconcepts.mortimer

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class MortimerColors(
    val dark: Boolean,
    val bg: Color,
    val panel: Color,
    val panel2: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
)

private data class Palette(
    val accent: Color,
    val accentLight: Color,
    val onAccent: Color = Color(0xFF101014),
)

private val PALETTES = mapOf(
    "ember" to Palette(Color(0xFFFF7849), Color(0xFFE2622F)),
    "ocean" to Palette(Color(0xFF45B8FF), Color(0xFF087DBB), Color(0xFF06131D)),
    "emerald" to Palette(Color(0xFF43D6A0), Color(0xFF12865F), Color(0xFF071811)),
    "violet" to Palette(Color(0xFFB69AFF), Color(0xFF7952C8), Color(0xFF170E26)),
    "rose" to Palette(Color(0xFFFF7EAB), Color(0xFFD74478), Color(0xFF260914)),
    "gold" to Palette(Color(0xFFFFCF5A), Color(0xFFB88312), Color(0xFF211704)),
)

private val DarkBase = MortimerColors(
    dark = true,
    bg = Color(0xFF07070A),
    panel = Color(0xFF121218),
    panel2 = Color(0xFF1A1A22),
    line = Color(0xFF24242E),
    text = Color(0xFFF4F4F6),
    muted = Color(0xFF8F8F9C),
    accent = PALETTES.getValue("ember").accent,
    onAccent = PALETTES.getValue("ember").onAccent,
)

private val LightBase = MortimerColors(
    dark = false,
    bg = Color(0xFFF5F6F8),
    panel = Color(0xFFFFFFFF),
    panel2 = Color(0xFFECEEF2),
    line = Color(0xFFD9DCE3),
    text = Color(0xFF191A20),
    muted = Color(0xFF646875),
    accent = PALETTES.getValue("ember").accentLight,
    onAccent = Color(0xFFFFFAF7),
)

val DarkColors = DarkBase
val LightColors = LightBase
val LocalMortimerColors = staticCompositionLocalOf { DarkColors }

/** Appearance preference: "system", "light" or "dark". */
object ThemePref {
    private const val PREFS = "mortimer_prefs"

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("theme", "system") ?: "system"

    fun set(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("theme", value).apply()
    }

    fun getPalette(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("palette", "ember") ?: "ember"

    fun setPalette(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("palette", value).apply()
    }
}

@Composable
fun MortimerTheme(pref: String, palette: String? = null, content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dark = when (pref) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val selected = PALETTES[palette ?: ThemePref.getPalette(context)] ?: PALETTES.getValue("ember")
    val base = if (dark) DarkBase else LightBase
    val c = base.copy(
        accent = if (dark) selected.accent else selected.accentLight,
        onAccent = if (dark) selected.onAccent else Color(0xFFFFFAF7),
    )
    val scheme = if (dark) {
        darkColorScheme(
            background = c.bg, surface = c.panel, surfaceVariant = c.panel2,
            primary = c.accent, onPrimary = c.onAccent, secondary = c.accent,
            onSecondary = c.onAccent, onBackground = c.text, onSurface = c.text,
            onSurfaceVariant = c.muted, outline = c.line,
        )
    } else {
        lightColorScheme(
            background = c.bg, surface = c.panel, surfaceVariant = c.panel2,
            primary = c.accent, onPrimary = c.onAccent, secondary = c.accent,
            onSecondary = c.onAccent, onBackground = c.text, onSurface = c.text,
            onSurfaceVariant = c.muted, outline = c.line,
        )
    }
    CompositionLocalProvider(LocalMortimerColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
