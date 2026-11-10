package com.example.omniroute

import android.content.Context

object AppPrefs {
    private const val FILE = "omniroute_prefs"
    private const val PORT = "port"
    private const val UPSTREAM = "upstream"
    private const val THEME = "theme"
    private const val OLED = "oled"
    private const val LANGUAGE = "language"
    private const val AUTO_START = "auto_start"
    private const val BATTERY_EXEMPT = "battery_exempt"

    data class Snapshot(
        val port: Int,
        val upstream: String,
        val theme: String,
        val oled: Boolean,
        val language: String,
        val autoStart: Boolean,
        val batteryExempt: Boolean,
    )

    fun snapshot(context: Context): Snapshot {
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return Snapshot(
            port = p.getInt(PORT, 8080),
            upstream = p.getString(UPSTREAM, "http://127.0.0.1:11434") ?: "http://127.0.0.1:11434",
            theme = p.getString(THEME, "dark") ?: "dark",
            oled = p.getBoolean(OLED, false),
            language = p.getString(LANGUAGE, "id") ?: "id",
            autoStart = p.getBoolean(AUTO_START, true),
            batteryExempt = p.getBoolean(BATTERY_EXEMPT, false),
        )
    }

    fun setPort(context: Context, port: Int) = edit(context) { it.putInt(PORT, port) }
    fun setUpstream(context: Context, upstream: String) = edit(context) { it.putString(UPSTREAM, upstream) }
    fun setTheme(context: Context, theme: String) = edit(context) { it.putString(THEME, theme) }
    fun setOled(context: Context, enabled: Boolean) = edit(context) { it.putBoolean(OLED, enabled) }
    fun setLanguage(context: Context, language: String) = edit(context) { it.putString(LANGUAGE, language) }
    fun setAutoStart(context: Context, enabled: Boolean) = edit(context) { it.putBoolean(AUTO_START, enabled) }
    fun setBatteryExempt(context: Context, enabled: Boolean) = edit(context) { it.putBoolean(BATTERY_EXEMPT, enabled) }

    fun reset(context: Context) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun edit(context: Context, block: (android.content.SharedPreferences.Editor) -> Unit) {
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        p.edit().apply { block(this) }.apply()
    }
}