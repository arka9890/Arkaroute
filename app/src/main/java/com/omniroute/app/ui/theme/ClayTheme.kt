package com.omniroute.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class ClayColors(
    val background: Color,
    val surface: Color,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val onSurface: Color,
)

private val CyanPrimary = Color(0xFF22D3EE)
private val CyanSecondary = Color(0xFF67E8F9)
private val VioletTertiary = Color(0xFFA5B4FC)

val LocalClayColors = staticCompositionLocalOf {
    ClayColors(
        background = Color(0xFF0B1326),
        surface = Color(0xFF16203B),
        primary = CyanPrimary,
        secondary = CyanSecondary,
        tertiary = VioletTertiary,
        onSurface = Color(0xFFE8ECF5),
    )
}

val LocalClayDepth = staticCompositionLocalOf { 85 }

object ClayTheme {
    val colors: ClayColors
        @Composable get() = LocalClayColors.current

    val depth: Int
        @Composable get() = LocalClayDepth.current

    /** Elevation in dp units derived from [depth] (10..100 → 0.4f..2.4f). */
    val elevation: Float
        @Composable get() = depthToElevation(LocalClayDepth.current)
}

fun depthToElevation(depth: Int): Float {
    val clamped = depth.coerceIn(10, 100)
    return 0.4f + (clamped - 10) / 90f * 2.0f
}

@Composable
fun ClayTheme(colors: ClayColors, depth: Int = 85, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalClayColors provides colors,
        LocalClayDepth provides depth,
        content = content,
    )
}

/**
 * Builds [ClayColors] from a persisted themeMode string.
 * - `"oled"` (default): pure dark navy `#0B1326`
 * - `"soft"`: lighter navy `#131C33`
 * - `"system"`: follows [isSystemInDarkTheme] (dark → oled, light → soft)
 * - [highContrast] bumps text contrast to pure white.
 */
@Composable
fun clayColorsFor(themeMode: String, highContrast: Boolean = false): ClayColors {
    val dark = when (themeMode) {
        "soft" -> false
        "system" -> isSystemInDarkTheme()
        else -> true // "oled" and any unknown value fall back to OLED
    }
    val base = if (dark) {
        ClayColors(
            background = Color(0xFF0B1326),
            surface = Color(0xFF16203B),
            primary = CyanPrimary,
            secondary = CyanSecondary,
            tertiary = VioletTertiary,
            onSurface = Color(0xFFE8ECF5),
        )
    } else {
        ClayColors(
            background = Color(0xFF131C33),
            surface = Color(0xFF1C2742),
            primary = CyanPrimary,
            secondary = Color(0xFF38BDF8),
            tertiary = Color(0xFFA5B4FC),
            onSurface = Color(0xFFEDF2FB),
        )
    }
    return if (highContrast) base.copy(onSurface = Color.White, primary = Color(0xFF00E5FF)) else base
}
