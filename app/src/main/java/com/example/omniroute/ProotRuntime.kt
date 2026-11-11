package com.example.omniroute

import android.content.Context
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Manages the PRoot Debian 12 subsystem that runs the real OmniRoute (Node.js)
 * engine. Binaries that must be executed (proot + its loader) live in
 * jniLibs so the OS allows exec; the rootfs and Node live in filesDir.
 */
object ProotRuntime {
    private const val MARKER = "proot/omniroute-installed"

    fun prootBinary(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath

    fun loaderBinary(context: Context): String? =
        File(context.applicationInfo.nativeLibraryDir, "libproot_loader.so")
            .takeIf { it.exists() }?.absolutePath

    fun rootfs(context: Context): File = File(context.filesDir, "proot/rootfs")
    fun nodeRoot(context: Context): File = File(context.filesDir, "proot/node")

    /** Path (guest-side) of the omniroute CLI entry, saved by the setup step. */
    fun entryJs(context: Context): String? {
        val marker = File(context.filesDir, MARKER).takeIf { it.exists() }
            ?.readText()?.trim()?.takeIf { it.isNotEmpty() }
        if (marker != null) return marker

        // Fallback: pakai path default HANYA kalau file-nya benar-benar ada.
        // Kalau tidak, kembalikan null supaya installer tahu omniroute belum
        // terpasang (dulu selalu mengembalikan path, sehingga npm install
        // dilewati dan app jatuh ke proxy Rust).
        val fallback = "/root/node/lib/node_modules/omniroute/bin/omniroute.mjs"
        val host = File(nodeRoot(context), fallback.removePrefix("/root/node/"))
        return if (host.exists()) fallback else null
    }

    /** Path host-side entry CLI kalau paketnya benar-benar terpasang. */
    fun installedEntryHostPath(context: Context): String? =
        File(nodeRoot(context), "lib/node_modules/omniroute/bin/omniroute.mjs")
            .takeIf { it.exists() }?.absolutePath

    /** Path host-side of the entry JS, atau null kalau belum terpasang. */
    fun entryHostPath(context: Context): String? =
        entryJs(context)?.let { guest ->
            File(nodeRoot(context), guest.removePrefix("/root/node/")).takeIf { it.exists() }
                ?.absolutePath
        }

    fun isInstalled(context: Context): Boolean {
        // 1. Cek file fisik (normal path)
        if (!File(prootBinary(context)).exists()) return false
        if (!File(rootfs(context), "usr/bin").isDirectory) return false
        if (!File(nodeRoot(context), "bin/node").exists()) return false
        if (entryHostPath(context) != null) return true

        // 2. Fallback: marker hilang (misal setelah install ulang APK) tapi
        //    proot masih menjalankan omniroute di port default → anggap installed.
        val prefs = AppPrefs.snapshot(context)
        return probe(prefs.port)
    }

    /**
     * proot command running omniroute inside Debian. Guest sees:
     *   /            <- files/proot/rootfs
     *   /root/node   <- files/proot/node (Node.js runtime + global npm packages)
     */
    fun command(context: Context, port: Int): List<String> {
        val entry = entryJs(context)
            ?: throw IllegalStateException("omniroute entry not installed")
        val env = File(nodeRoot(context), "bin/omniroute-env").let { if (it.exists()) it.readText().trim() else "" }
        return listOf(
            prootBinary(context),
            "--kill-on-exit", "-0",
            "-r", rootfs(context).absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", nodeRoot(context).absolutePath + ":/root/node",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "PATH=/root/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm",
            "NODE_PATH=/root/node/lib/node_modules",
            "PORT=" + port,
        ) + env.split('\n').filter { it.contains('=') }.map { it.trim() } +
            listOf("/root/node/bin/node", entry, "--port", port.toString())
    }

    /** Quick TCP probe used for status when the real engine is installed. */
    fun probe(port: Int, timeoutMs: Int = 1000): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            true
        }
    } catch (_: Exception) {
        false
    }
}
