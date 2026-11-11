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

    // registry npm: utama lalu mirror Asia (kadang registry.npmjs.org timeout
    // di jaringan seluler → ETIMEDOUT). Versi paket tetap sama persis.
    private val NPM_REGISTRIES = listOf(
        "https://registry.npmjs.org/",
        "https://registry.npmmirror.com/",
    )
    private const val NPM_ATTEMPTS = 3

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
     * Tulis /etc/resolv.conf milik guest sebagai file biasa.
     * Symlink bawaan image Debian (menunjuk /run/systemd/resolve/...) menggantung
     * di dalam guest sehingga DNS tidak bisa dipakai npm install.
     */
    private fun ensureGuestDns(context: Context) {
        val resolv = File(ProotRuntime.rootfs(context), "etc/resolv.conf")
        resolv.parentFile?.mkdirs()
        // hapus symlink menggantung (atau file lama) lalu tulis file biasa
        runCatching { java.nio.file.Files.deleteIfExists(resolv.toPath()) }
        resolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        say("DNS guest: resolv.conf ditulis")
    }

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

            // 2. Debian rootfs — ambil dari aset bawaan APK (offline, instan),
            //    unduh dari internet hanya kalau asetnya tidak ada.
            if (!rootfsReady(c)) {
                val tarFile = File(prootDir, "rootfs.tar.xz")
                if (extractBundledAsset(c, "rootfs.tar.xz", tarFile)) {
                    say("Mengambil rootfs Debian 12 dari bawaan aplikasi...")
                } else {
                    say("Mengunduh rootfs Debian 12 (~95 MB)...")
                    download(resolveRootfsUrl(), tarFile)
                }
                say("Mengekstrak rootfs...")
                extractTarXz(tarFile, ProotRuntime.rootfs(c))
                tarFile.delete()
                check(rootfsReady(c)) { "rootfs tidak lengkap setelah ekstraksi" }
            } else say("Rootfs Debian: sudah ada, dilewati")

            // DNS untuk guest. WAJIB setelah ekstraksi rootfs: image Debian
            // berisi etc/resolv.conf sebagai symlink ke /run/systemd/resolve
            // yang tidak ada di dalam guest → symlink menggantung, DNS mati dan
            // `npm install` gagal. Ekstraksi menimpa file yang kita tulis
            // sebelumnya, jadi langkah ini harus dijalankan SETELAH ekstraksi.
            runCatching { ensureGuestDns(c) }.onFailure {
                AppLog.log("SETUP", "peringatan DNS guest: ${it.message}")
            }

            // 3. Node.js — sama: dari aset bawaan dulu, unduh sebagai cadangan.
            if (!nodeReady(c)) {
                val tarFile = File(prootDir, "node.tar.xz")
                if (extractBundledAsset(c, "node.tar.xz", tarFile)) {
                    say("Mengambil Node.js 22 dari bawaan aplikasi...")
                } else {
                    say("Mengunduh Node.js 22 (~30 MB)...")
                    download(NODE_URL, tarFile)
                }
                say("Mengekstrak Node.js...")
                extractTarXz(tarFile, ProotRuntime.nodeRoot(c))
                tarFile.delete()
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

    /**
     * Memasang omniroute lewat npm di dalam rootfs.
     *
     * npm sering gagal di jaringan seluler ("network read ETIMEDOUT"), jadi:
     *  - coba registry utama lalu mirror (npmmirror.com),
     *  - longgarkan timeout & kurangi jumlah socket,
     *  - ulangi beberapa kali. Cache npm tersimpan di /root/.npm sehingga
     *    percobaan berikutnya jauh lebih murah (tidak mengulang unduhan).
     *
     * Return: path guest dari entry CLI.
     */
    private fun npmInstallOmniroute(context: Context): String {
        var lastError: Exception? = null
        for (attempt in 1..NPM_ATTEMPTS) {
            for (registry in NPM_REGISTRIES) {
                try {
                    say("npm percobaan $attempt • ${registry.substringAfter("https://")}")
                    runNpm(context, registry)
                    return verifyOmniroutePackage(context)
                } catch (e: Exception) {
                    lastError = e
                    AppLog.log("SETUP", "npm gagal ($registry): ${e.message}")
                    cleanupPartialInstall(context)
                }
            }
        }
        throw lastError ?: IllegalStateException("npm install omniroute gagal")
    }

    /** Menjalankan satu proses `npm install -g omniroute` di dalam proot. */
    private fun runNpm(context: Context, registry: String) {
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
            "--registry=$registry",
            "--fetch-retries=5",
            "--fetch-retry-maxtimeout=120000",
            "--fetch-timeout=600000",
            "--maxsockets=3",
            "--no-audit",
            "--no-fund",
            "--prefer-offline",
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
        check(code == 0) { "npm exit $code" }
    }

    /**
     * Salin aset bawaan APK (rootfs.tar.xz / node.tar.xz) ke folder kerja.
     * Return true kalau aset tersedia & berhasil disalin.
     */
    private fun extractBundledAsset(context: Context, assetName: String, dest: File): Boolean {
        return try {
            context.assets.open(assetName).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output, 1 shl 16) }
            }
            AppLog.log("SETUP", "aset bawaan dipakai: $assetName")
            true
        } catch (e: Exception) {
            AppLog.log("SETUP", "aset $assetName tidak tersedia (${e.message}) — pakai unduhan")
            false
        }
    }

    /** Buang paket setengah jadi agar percobaan berikutnya bersih. */
    private fun cleanupPartialInstall(context: Context) {
        val modulesDir = File(ProotRuntime.nodeRoot(context), "lib/node_modules")
        runCatching {
            modulesDir.listFiles()?.filter { it.name.startsWith(".omniroute-") }?.forEach { it.deleteRecursively() }
        }
    }

    /**
     * Pastikan paket omniroute benar-benar terpasang; kalau npm sempat terputus
     * dan meninggalkan paket di folder staging (.omniroute-XXXX), promote
     * staging yang utuh ke folder final. Return: path guest entry CLI.
     */
    private fun verifyOmniroutePackage(context: Context): String {
        val c = context.applicationContext
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
