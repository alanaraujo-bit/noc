package com.noc.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Paleta do Noc: superfícies quentes e neutras, um único acento cor de brasa.
 * Nada de neon, gradiente ou vidro: contraste e tipografia fazem o trabalho.
 */
@Immutable
data class NocColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val line: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val ok: Color,
    val warn: Color,
    val err: Color,
    val okSoft: Color,
    val warnSoft: Color,
    val errSoft: Color,
    val codeBg: Color,
    val scrim: Color,
    val isDark: Boolean,
)

val LightNoc = NocColors(
    bg = Color(0xFFF5F3EF),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFEDEAE4),
    surface3 = Color(0xFFE3DFD7),
    line = Color(0xFFE2DED6),
    text = Color(0xFF191816),
    text2 = Color(0xFF5C5852),
    text3 = Color(0xFF8C877F),
    accent = Color(0xFFC45A1A),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFF7E4D6),
    ok = Color(0xFF2C8653),
    warn = Color(0xFFA86F12),
    err = Color(0xFFBF3B27),
    okSoft = Color(0xFFDDEFE3),
    warnSoft = Color(0xFFF6EBD3),
    errSoft = Color(0xFFF7DEDA),
    codeBg = Color(0xFFF0EDE7),
    scrim = Color(0x66000000),
    isDark = false,
)

val DarkNoc = NocColors(
    bg = Color(0xFF111110),
    surface = Color(0xFF1A1918),
    surface2 = Color(0xFF232220),
    surface3 = Color(0xFF2D2C29),
    line = Color(0xFF2C2B28),
    text = Color(0xFFEEECE8),
    text2 = Color(0xFFA8A49D),
    text3 = Color(0xFF75716A),
    accent = Color(0xFFF08A4B),
    onAccent = Color(0xFF1A0D05),
    accentSoft = Color(0xFF38241A),
    ok = Color(0xFF5DBE88),
    warn = Color(0xFFE2AA4E),
    err = Color(0xFFEC6B57),
    okSoft = Color(0xFF1B2E23),
    warnSoft = Color(0xFF30281A),
    errSoft = Color(0xFF34201C),
    codeBg = Color(0xFF0C0C0B),
    scrim = Color(0x99000000),
    isDark = true,
)

val LocalNoc = staticCompositionLocalOf { LightNoc }

object Noc {
    val colors: NocColors @Composable get() = LocalNoc.current
}

@Composable
fun NocTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkNoc else LightNoc
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.text,
            secondary = c.text2, onSecondary = c.bg, background = c.bg, onBackground = c.text,
            surface = c.surface, onSurface = c.text, surfaceVariant = c.surface2, onSurfaceVariant = c.text2,
            surfaceContainerLowest = c.bg, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
            surfaceContainerHigh = c.surface2, surfaceContainerHighest = c.surface3,
            outline = c.line, outlineVariant = c.line, error = c.err, onError = c.bg, scrim = c.scrim,
            inverseSurface = c.text, inverseOnSurface = c.bg,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.text,
            secondary = c.text2, onSecondary = c.bg, background = c.bg, onBackground = c.text,
            surface = c.surface, onSurface = c.text, surfaceVariant = c.surface2, onSurfaceVariant = c.text2,
            surfaceContainerLowest = c.surface, surfaceContainerLow = c.bg, surfaceContainer = c.surface,
            surfaceContainerHigh = c.surface2, surfaceContainerHighest = c.surface3,
            outline = c.line, outlineVariant = c.line, error = c.err, onError = c.onAccent, scrim = c.scrim,
            inverseSurface = c.text, inverseOnSurface = c.bg,
        )
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val insets = WindowCompat.getInsetsController(window, view)
            insets.isAppearanceLightStatusBars = !dark
            insets.isAppearanceLightNavigationBars = !dark
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.decorView.setBackgroundColor(c.bg.toArgb())
        }
    }
    CompositionLocalProvider(LocalNoc provides c) {
        MaterialTheme(colorScheme = scheme, typography = NocTypography, shapes = NocShapes, content = content)
    }
}
