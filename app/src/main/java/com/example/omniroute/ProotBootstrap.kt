package com.example.omniroute

import android.content.Context
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app installer for the PRoot engine. Checks each component; if missing it
 * downloads and installs it, if present it is skipped:
 *
 *  1. proot support libs (talloc + android-shmem) — copied from jniLibs
 *  2. Debian 12 rootfs — downloaded from images.linuxcontainers.org (~95 MB)
 *  3. Node.js 22 — downloaded from nodejs.org (~30 MB)
 *  4. omniroute — `npm install -g` inside the rootfs (~3-6 GB on disk)
 */
object ProotBootstrap {
    private const val LXC_BASE = "https://images.linuxcontainers.org/images/debian/bookworm/arm64/default/"
    private const val NODE_URL = "https://nodejs.org/dist/v22.23.4/node-v22.23.4-linux-arm64.tar.xz"
    private const val NODE_TOPDIR = "node-v22.23.4-linux-arm64"

    private val _progress = MutableStateFlow("")
    val progress: StateFlow<String> = _progress
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private fun say(s: String) {
        _progress.value = s
        AppLog.log("SETUP", s)
    }

    fun rootfsReady(c: Context) = File(ProotRuntime.rootfs(c), "usr/bin").isDirectory
    fun nodeReady(c: Context) = File(ProotRuntime.nodeRoot(c), "bin/node").exists()
    fun libsReady(c: Context) = File(c.filesDir, "proot/lib/libtalloc.so.2").exists()

    /**
     * Installs everything that is missing. Safe to call repeatedly: existing
     * components are skipped. Returns null on success, or an error message.
     */
    suspend fun installAll(context: Context): String? = withContext(Dispatchers.IO) {
        if (_running.value) return@withContext "setup sudah berjalan"
        _running.value = true
        try {
            val c = context.applicationContext
            val prootDir = File(c.filesDir, "proot").apply { mkdirs() }
            File(prootDir, "tmp").mkdirs()
            File(prootDir, "lib").mkdirs()

            // 1. support libs (shipped inside the APK)
            if (!libsReady(c)) {
                say("Menyalin library proot...")
                val libDir = File(c.applicationInfo.nativeLibraryDir)
                File(libDir, "libtalloc.so").copyTo(File(prootDir, "lib/libtalloc.so.2"), overwrite = true)
                File(libDir, "libandroid-shmem.so").copyTo(File(prootDir, "lib/libandroid-shmem.so"), overwrite = true)
            } else say("Library proot: sudah ada, dilewati")

            // DNS untuk guest. Image Debian punya symlink ke /run/systemd/resolve
            // yang tidak ada di dalam guest → symlink menggantung; menulis ke
            // sana melempar FileNotFoundException dan membatalkan install.
            // Jadi: hapus symlink menggantung dulu, lalu tulis file biasa.
            runCatching {
                val resolv = File(ProotRuntime.rootfs(c), "etc/resolv.conf")
                if (!resolv.exists()) {
                    resolv.parentFile?.mkdirs()
                    runCatching { java.nio.file.Files.deleteIfExists(resolv.toPath()) }
                    resolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
                    say("DNS guest: resolv.conf ditulis")
                }
            }.onFailure {
                AppLog.log("SETUP", "peringatan DNS guest: ${it.message}")
            }

            // 2. Debian rootfs
            if (!rootfsReady(c)) {
                val url = resolveRootfsUrl()
                say("Mengunduh rootfs Debian 12 (~95 MB)...")
                val xz = download(url, File(prootDir, "rootfs.tar.xz"))
                say("Mengekstrak rootfs...")
                extractTarXz(xz, ProotRuntime.rootfs(c))
                xz.delete()
                check(rootfsReady(c)) { "rootfs tidak lengkap setelah ekstraksi" }
            } else say("Rootfs Debian: sudah ada, dilewati")

            // 3. Node.js
            if (!nodeReady(c)) {
                say("Mengunduh Node.js 22 (~30 MB)...")
                val xz = download(NODE_URL, File(prootDir, "node.tar.xz"))
                say("Mengekstrak Node.js...")
                extractTarXz(xz, ProotRuntime.nodeRoot(c))
                xz.delete()
                // tar has a top-level dir; flatten it
                if (!nodeReady(c)) {
                    val sub = ProotRuntime.nodeRoot(c).resolve(NODE_TOPDIR)
                    if (sub.isDirectory) sub.listFiles()?.forEach { it.renameTo(File(sub.parentFile, it.name)) }
                    sub.deleteRecursively()
                }
                check(nodeReady(c)) { "node tidak lengkap setelah ekstraksi" }
            } else say("Node.js 22: sudah ada, dilewati")

            // 4. omniroute via npm inside the rootfs
            if (ProotRuntime.installedEntryHostPath(c) == null) {
                say("Memasang omniroute (npm install, 10-30 menit)...")
                val entry = npmInstallOmniroute(c)
                File(c.filesDir, "proot/omniroute-installed").writeText(entry)
            } else say("Omniroute: sudah ada, dilewati")

            say("Selesai — engine siap.")
            null
        } catch (e: Exception) {
            AppLog.log("ERROR", "setup gagal: ${e.message}")
            "Setup gagal: ${e.message}"
        } finally {
            _running.value = false
        }
    }

    /** Resolves the newest available LXC rootfs URL, falling back to a pinned one. */
    private fun resolveRootfsUrl(): String = runCatching {
        val html = httpGet(LXC_BASE)
        val dates = Regex("""href="(\d{8}_\d{2}:\d{2})/"""").findAll(html).map { it.groupValues[1] }.toList()
        val newest = dates.maxOrNull() ?: error("no image")
        LXC_BASE + java.net.URLEncoder.encode(newest, "UTF-8").replace("%3A", ":") + "/rootfs.tar.xz"
    }.getOrDefault(LXC_BASE + "20261109_05%3A24/rootfs.tar.xz")

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun download(url: String, dest: File): File {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        val total = conn.contentLengthLong
        var done = 0L
        var lastPct = -10
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt() / 5 * 5
                        if (pct > lastPct) { lastPct = pct; say("Mengunduh ${dest.name}: $pct%") }
                    }
                }
            }
        }
        return dest
    }

    private fun extractTarXz(xz: File, dest: File) {
        dest.mkdirs()
        val base = dest.canonicalPath
        TarArchiveInputStream(XZCompressorInputStream(BufferedInputStream(FileInputStream(xz)))).use { tar ->
            while (true) {
                val e: TarArchiveEntry = tar.nextTarEntry ?: break
                val out = File(dest, e.name)
                if (!out.canonicalPath.startsWith(base)) continue // block path traversal
                when {
                    e.isDirectory -> out.mkdirs()
                    e.isSymbolicLink -> {
                        out.parentFile?.mkdirs()
                        out.delete()
                        runCatching { Os.symlink(e.linkName, out.canonicalPath) }
                            .onFailure { runCatching { Os.symlink(e.linkName, out.path) } }
                    }
                    else -> {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { tar.copyTo(it) }
                        if (e.mode and 0b111_000000 != 0) out.setExecutable(true) // any exec bit set
                    }
                }
            }
        }
    }

    /** Runs `npm install -g omniroute` inside the proot rootfs; returns the entry js path. */
    private fun npmInstallOmniroute(context: Context): String {
        val c = context.applicationContext
        val prootDir = File(c.filesDir, "proot")
        val cmd = listOf(
            ProotRuntime.prootBinary(c),
            "--kill-on-exit", "-0",
            "-r", ProotRuntime.rootfs(c).absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", ProotRuntime.nodeRoot(c).absolutePath + ":/root/node",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "PATH=/root/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm",
            "NODE_PATH=/root/node/lib/node_modules",
            "/root/node/bin/npm", "install", "-g", "omniroute",
        )
        val pb = ProcessBuilder(cmd)
        pb.environment()["PROOT_LOADER"] = ProotRuntime.loaderBinary(c) ?: ""
        pb.environment()["PROOT_TMP_DIR"] = File(prootDir, "tmp").absolutePath
        pb.environment()["PROOT_NO_SECCOMP"] = "1"
        pb.environment()["LD_LIBRARY_PATH"] = File(prootDir, "lib").absolutePath
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val reader = proc.inputStream.bufferedReader()
        var lastSay = 0L
        val line = Thread {
            try {
                while (true) {
                    val l = reader.readLine() ?: break
                    val now = System.currentTimeMillis()
                    if (now - lastSay > 30_000 && l.isNotBlank()) {
                        lastSay = now
                        say("npm: " + l.take(120))
                    }
                }
            } catch (_: Exception) {}
        }
        line.isDaemon = true
        line.start()
        val code = proc.waitFor()

        // npm yang terputus (mis. koneksi putus) bisa meninggalkan paket di folder
        // staging (.omniroute-XXXX) sementara folder final tidak lengkap.
        // Kalau begitu, promote staging yang utuh ke folder final.
        val modulesDir = File(ProotRuntime.nodeRoot(c), "lib/node_modules")
        val pkgFile = File(modulesDir, "omniroute/package.json")
        if (!pkgFile.exists()) {
            val staging = modulesDir.listFiles()?.filter { it.isDirectory && it.name.startsWith(".omniroute-") } ?: emptyList()
            val complete = staging.firstOrNull {
                File(it, "package.json").exists() && File(it, "bin/omniroute.mjs").exists()
            }
            if (complete != null) {
                val target = File(modulesDir, "omniroute")
                target.deleteRecursively()
                if (complete.renameTo(target)) {
                    AppLog.log("SETUP", "paket omniroute dipulihkan dari ${complete.name}")
                }
            }
        }

        check(code == 0) { "npm exit $code" }

        // find the bin entry from package.json
        check(pkgFile.exists()) { "package.json omniroute tidak ditemukan" }
        val bin = JSONObject(pkgFile.readText()).optJSONObject("bin")
        val rel = bin?.let { b ->
            val names = b.names()!!
            if (names.length() > 0) b.getString(names.getString(0)) else null
        } ?: error("bin entry tidak ditemukan")
        val entry = "/root/node/lib/node_modules/omniroute/" + rel.removePrefix("./")
        check(File(ProotRuntime.nodeRoot(c), entry.removePrefix("/root/node/")).exists()) { "entry js tidak ada: $entry" }
        return entry
    }
}
