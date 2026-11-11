package com.example.omniroute

import android.system.Os
import java.io.File

/**
 * Membunuh proses OmniRoute/PRoot yang masih hidup di perangkat.
 * Dipakai tombol "Kill" supaya port yang sudah terikat bisa dibebaskan
 * (error "Port ... is already in use by an unknown process").
 */
object OmniRouteKiller {
    // penanda kuat di cmdline proses (aman: tidak cocok dengan package app kita)
    private val STRONG_MARKERS = listOf(
        "libproot.so",
        "omniroute.mjs",
        "node_modules/omniroute",
        "bin/omniroute",
    )

    /** Daftar PID proses omniroute/proot milik uid app ini. */
    fun findPids(): List<Int> {
        val pids = mutableListOf<Int>()
        val entries = File("/proc").listFiles() ?: return pids
        for (dir in entries) {
            val pid = dir.name.toIntOrNull() ?: continue
            if (pid <= 1) continue
            val cmd = try {
                File(dir, "cmdline").readBytes().toString(Charsets.ISO_8859_1).replace('\u0000', ' ')
            } catch (_: Exception) {
                continue // proses milik app lain: tidak boleh dibaca/dibunuh
            }
            if (matches(cmd)) pids += pid
        }
        return pids
    }

    /** Bunuh semua proses yang cocok. Return jumlah proses yang mati. */
    fun killAll(): Int {
        var killed = 0
        findPids().forEach { pid ->
            try {
                Os.kill(pid, 9) // SIGKILL
                killed++
            } catch (_: Exception) {
                // sudah mati / tidak diizinkan
            }
        }
        return killed
    }

    private fun matches(cmd: String): Boolean {
        // jangan pernah bunyi proses app ini sendiri
        if (cmd.contains("com.example.omniroute")) return false
        if (STRONG_MARKERS.any { cmd.contains(it) }) return true
        // proses yang mengganti title jadi "omniroute (v16.4.2)"
        return cmd.trimStart().startsWith("omniroute")
    }
}
