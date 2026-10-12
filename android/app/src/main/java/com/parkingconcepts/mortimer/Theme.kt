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

/** Same palette as the PWA's `--bg`, `--panel`… variables in dark and light mode. */
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

val DarkColors = MortimerColors(
    dark = true,
    bg = Color(0xFF07070A),
    panel = Color(0xFF121218),
    panel2 = Color(0xFF1A1A22),
    line = Color(0xFF24242E),
    text = Color(0xFFF4F4F6),
    muted = Color(0xFF8F8F9C),
    accent = Color(0xFFFF7849),
    onAccent = Color(0xFF111114),
)

val LightColors = MortimerColors(
    dark = false,
    bg = Color(0xFFF5F6F8),
    panel = Color(0xFFFFFFFF),
    panel2 = Color(0xFFECEEF2),
    line = Color(0xFFD9DCE3),
    text = Color(0xFF191A20),
    muted = Color(0xFF646875),
    accent = Color(0xFFE2622F),
    onAccent = Color(0xFFFFFAF7),
)

val LocalMortimerColors = staticCompositionLocalOf { DarkColors }

/** "system", "light" or "dark". */
object ThemePref {
    private const val PREFS = "mortimer_prefs"
    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("theme", "system") ?: "system"

    fun set(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("theme", value).apply()
    }
}

@Composable
fun MortimerTheme(pref: String, content: @Composable () -> Unit) {
    val dark = when (pref) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val c = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(background = c.bg, surface = c.panel, surfaceVariant = c.panel2, primary = c.accent, onPrimary = c.onAccent, onBackground = c.text, onSurface = c.text, onSurfaceVariant = c.muted, outline = c.line)
    } else {
        lightColorScheme(background = c.bg, surface = c.panel, surfaceVariant = c.panel2, primary = c.accent, onPrimary = c.onAccent, onBackground = c.text, onSurface = c.text, onSurfaceVariant = c.muted, outline = c.line)
    }
    CompositionLocalProvider(LocalMortimerColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
