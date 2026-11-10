package com.example.omniroute

import android.content.Context
import androidx.compose.ui.graphics.Color

/**
 * Palet warna claymorphic — dark (default saat ini) dan light.
 * Dipilih menurut prefs.theme: "dark" / "light" / "system".
 */
object ThemePalette {
    val dark = Palette(
        background = Color(0xFF0B1220),
        card = Color(0xFF131B2E),
        surface = Color(0xFF18213A),
        textHi = Color(0xFFF8FAFC),
        textLo = Color(0xFF94A3B8),
        gold = Color(0xFFE8B74A),
        emerald = Color(0xFF34D399),
        accentBg = Color(0xFF1E293B),
        accentBorder = Color(0xFF334155),
        inactive = Color(0xFF475569),
        error = Color(0xFFF87171),
    )

    val light = Palette(
        background = Color(0xFFF8FAFC),
        card = Color(0xFFFFFFFF),
        surface = Color(0xFFF1F5F9),
        textHi = Color(0xFF0F172A),
        textLo = Color(0xFF64748B),
        gold = Color(0xFFB45309),
        emerald = Color(0xFF059669),
        accentBg = Color(0xFFE2E8F0),
        accentBorder = Color(0xFFCBD5E1),
        inactive = Color(0xFF94A3B8),
        error = Color(0xFFDC2626),
    )

    data class Palette(
        val background: Color,
        val card: Color,
        val surface: Color,
        val textHi: Color,
        val textLo: Color,
        val gold: Color,
        val emerald: Color,
        val accentBg: Color,
        val accentBorder: Color,
        val inactive: Color,
        val error: Color,
    )

    /** Pilih palet sesuai pengaturan tema. */
    fun get(context: Context, theme: String): Palette = when (theme) {
        "light" -> light
        "system" -> if (isSystemDark(context)) dark else light
        else -> dark
    }

    private fun isSystemDark(context: Context): Boolean {
        return context.applicationContext.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
}
