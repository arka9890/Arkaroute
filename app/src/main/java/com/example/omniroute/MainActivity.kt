package com.example.omniroute

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebView
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.log("BOOT", "OmniRoute Android app ready")
        setContent { OmniRouteApp() }
    }
}

private fun Context.isBatteryOptimIgnorable(): Boolean {
    val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    return pm.isIgnoringBatteryOptimizations(packageName)
}

/** Ambil palet warna sesuai tema yang tersimpan. */
private fun Context.currentPalette(): ThemePalette.Palette {
    val prefs = AppPrefs.snapshot(this)
    return ThemePalette.get(this, prefs.theme)
}

/** Terjemahkan kunci sesuai bahasa yang tersimpan. */
internal fun Context.str(key: String): String {
    return AppStrings.get(key, AppPrefs.snapshot(this).language)
}

@Composable
private fun OmniRouteApp() {
    val context = LocalContext.current
    var prefs by remember { mutableStateOf(AppPrefs.snapshot(context)) }
    var tab by remember { mutableStateOf(0) }
    var statusText by remember { mutableStateOf("{\"status\":\"standby\"}") }
    val setupRunning by ProotBootstrap.running.collectAsState()
    val setupProgress by ProotBootstrap.progress.collectAsState()
    var engineReady by remember { mutableStateOf(ProotRuntime.isInstalled(context)) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(setupRunning) {
        if (!setupRunning) engineReady = ProotRuntime.isInstalled(context)
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1200)
            if (ProotRuntime.isInstalled(context)) {
                val online = withContext(Dispatchers.IO) { ProotRuntime.probe(prefs.port) }
                statusText = if (online) "{\"status\":\"online\",\"port\":${prefs.port},\"engine\":\"omniroute-proot\"}" else "{\"status\":\"standby\",\"port\":${prefs.port},\"engine\":\"omniroute-proot\"}"
            } else {
                statusText = NativeBridge.statusJson()
            }
        }
    }

    val palette = currentPalette(context, prefs.theme)
    val background = if (prefs.oled && prefs.theme == "dark") Color(0xFF000000) else palette.background

    // State browser in-app di-hoist supaya tab tetap hidup saat pindah bottom-nav.
    val browserState = remember { BrowserState(context) }
    DisposableEffect(Unit) { onDispose { browserState.destroyAll() } }

    Column(
        modifier = Modifier.fillMaxSize().background(background).statusBarsPadding().navigationBarsPadding()
    ) {
        HeaderStrip(background = background, status = statusText, prefs = prefs, palette = palette, context = context)

        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                0 -> ServiceTab(
                    prefs = prefs,
                    status = statusText,
                    engineReady = engineReady,
                    setupRunning = setupRunning,
                    setupProgress = setupProgress,
                    onInstall = { scope.launch { ProotBootstrap.installAll(context) } },
                    onUpdatePrefs = { prefs = AppPrefs.snapshot(context) },
                    palette = palette,
                    context = context,
                )
                1 -> InAppBrowser(
                    state = browserState,
                    homeUrl = "http://127.0.0.1:${prefs.port}",
                    palette = palette,
                    context = context,
                )
                else -> SettingsTab(
                    prefs = prefs,
                    onPrefsChange = { prefs = it },
                    palette = palette,
                    context = context,
                )
            }
        }

        BottomNavBar(selected = tab, onSelect = { tab = it }, palette = palette, context = context)
    }
}

/** Baca palet sesuai tema (rekomposisi otomatis saat prefs.theme berubah). */
@Composable
private fun currentPalette(context: Context, theme: String): ThemePalette.Palette {
    return ThemePalette.get(context, theme)
}

@Composable
private fun HeaderStrip(background: Color, status: String, prefs: AppPrefs.Snapshot, palette: ThemePalette.Palette, context: Context) {
    val json = runCatching { JSONObject(status) }.getOrDefault(JSONObject())
    val online = json.optString("status") == "online"
    val uptime = json.optLong("uptime_secs")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (online) palette.emerald.copy(alpha = 0.18f) else palette.inactive.copy(alpha = 0.18f))
                    .border(1.dp, if (online) palette.emerald.copy(alpha = 0.55f) else palette.inactive.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("OR", color = palette.gold, fontWeight = FontWeight.Black, fontSize = 15.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(context.str("app_name"), color = palette.textHi, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    StatusDot(online = online, palette = palette)
                }
                Text(
                    if (online) "${context.str("header_online")}${prefs.port}\n" else "${context.str("header_standby")}${prefs.port}\n",
                    color = palette.textLo, fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun StatusDot(online: Boolean, palette: ThemePalette.Palette) {
    val c = if (online) palette.emerald else palette.inactive
    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(c))
}

@Composable
private fun BottomNavBar(selected: Int, onSelect: (Int) -> Unit, palette: ThemePalette.Palette, context: Context) {
    val items = listOf(context.str("tab_service"), context.str("tab_dashboard"), context.str("tab_settings"))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp)
            .shadow(18.dp, Pill, ambientColor = Color.Black.copy(0.35f), spotColor = Color.Black.copy(0.45f))
            .clip(Pill)
            .background(palette.card.copy(alpha = 0.96f))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(Pill)
                    .background(if (active) palette.gold.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = if (active) palette.gold else palette.textLo, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp)
            }
        }
    }
}

private val Pill = RoundedCornerShape(999.dp)
private val CardShape = RoundedCornerShape(32.dp)

@Composable
private fun ClayCard(modifier: Modifier = Modifier, palette: ThemePalette.Palette, content: @Composable () -> Unit) {
    Card(
        modifier = modifier,
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = palette.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) { content() }
}

@Composable
private fun ServiceTab(
    prefs: AppPrefs.Snapshot,
    status: String,
    engineReady: Boolean,
    setupRunning: Boolean,
    setupProgress: String,
    onInstall: () -> Unit,
    onUpdatePrefs: () -> Unit,
    palette: ThemePalette.Palette,
    context: Context,
) {
    val json = runCatching { JSONObject(status) }.getOrDefault(JSONObject())
    val online = json.optString("status") == "online"
    val uptime = json.optLong("uptime_secs")
    val hits = json.optLong("hits")
    val inflight = json.optInt("inflight")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!engineReady) {
            ClayCard(modifier = Modifier.fillMaxWidth(), palette = palette) {
                Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(context.str("engine_not_installed"), color = palette.textHi, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                    Text(context.str("engine_not_installed_desc"), color = palette.textLo, fontSize = 12.sp)
                    if (setupProgress.isNotEmpty()) Text(setupProgress, color = palette.gold, fontSize = 12.sp)
                    ActionButton(
                        label = if (setupRunning) context.str("btn_installing") else context.str("btn_install_engine"),
                        modifier = Modifier.fillMaxWidth(),
                        primary = !setupRunning,
                        onClick = { if (!setupRunning) onInstall() },
                        palette = palette,
                        context = context,
                    )
                }
            }
        }

        ClayCard(modifier = Modifier.fillMaxWidth(), palette = palette) {
            Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("PRoot: " + if (online) context.str("status_online") else context.str("status_stopped"), color = palette.textHi, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp)
                        Text("${context.str("gateway")}${prefs.port}", color = palette.textLo, fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    StreamBadge(online = online, palette = palette, context = context)
                }

                GridTelemetry(uptime = uptime, hits = hits, inflight = inflight, port = prefs.port, arch = "aarch64", palette = palette, context = context)

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(
                        label = if (online) context.str("btn_stop") else context.str("btn_start"),
                        modifier = Modifier.weight(1f),
                        primary = true,
                        onClick = { OmniRouteToggleAction(context, online, onUpdatePrefs) },
                        palette = palette,
                        context = context,
                    )
                    ActionButton(context.str("btn_restart"), modifier = Modifier.weight(1f), onClick = {
                        val intent = Intent(context, com.example.omniroute.service.OmniRouteService::class.java).apply { action = com.example.omniroute.service.OmniRouteService.ACTION_RESTART }
                        if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
                        AppLog.log("ROUTE", context.str("btn_restart"))
                        onUpdatePrefs()
                    }, palette = palette, context = context)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(context.str("btn_git_pull"), modifier = Modifier.weight(1f), onClick = {
                        scopeGitPull(context)
                    }, palette = palette, context = context)
                    ActionButton(context.str("btn_flush_cache"), modifier = Modifier.weight(1f), onClick = {
                        NativeBridge.flushCache()
                        AppLog.log("METRIC", "cache flushed")
                    }, palette = palette, context = context)
                    ActionButton(context.str("btn_proot_cli"), modifier = Modifier.weight(1f), onClick = {
                        openProotCli(context)
                    }, palette = palette, context = context)
                    ActionButton(context.str("btn_check_update"), modifier = Modifier.weight(1f), onClick = {
                        scopeCheckUpdate(context)
                    }, palette = palette, context = context)
                }
            }
        }

        ClayCard(modifier = Modifier.fillMaxWidth(), palette = palette) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(context.str("log_proot_service"), color = palette.gold, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, letterSpacing = 1.2.sp)
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF0F172A))
                        .padding(14.dp)
                ) {
                    AppLog.buffer.takeLast(80).forEach { line ->
                        Text(line, color = lineColor(line, palette), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }
        }

        ClayCard(modifier = Modifier.fillMaxWidth(), palette = palette) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(context.str("system_paths"), color = palette.textHi, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                PathRow(context.str("path_gateway"), "http://127.0.0.1:${prefs.port}", palette = palette, context = context)
                PathRow(context.str("path_rootfs"), "/data/data/com.example.omniroute/files/proot/rootfs", palette = palette, context = context)
                PathRow(context.str("path_arch"), "aarch64", palette = palette, context = context)
                PathRow(context.str("path_upstream"), prefs.upstream, palette = palette, context = context)
                PathRow(context.str("path_distro"), "Debian 12 Bookworm PRoot", palette = palette, context = context)
            }
        }
    }
}

private var gitPullScope: kotlinx.coroutines.CoroutineScope? = null
private var updateScope: kotlinx.coroutines.CoroutineScope? = null

private fun scopeGitPull(context: Context) {
    val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
    gitPullScope = scope
    scope.launch {
        AppLog.log("INFO", "git pull: memulai...")
        try {
            // Coba npm update -g omniroute di dalam proot
            val result = runProotCmd(context, listOf("/root/node/bin/npm", "update", "-g", "omniroute"))
            AppLog.log("INFO", "git pull: ${result.take(300)}")
        } catch (e: Exception) {
            AppLog.log("ERROR", "git pull gagal: ${e.message}")
        }
        gitPullScope = null
    }
}

private fun scopeCheckUpdate(context: Context) {
    val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
    updateScope = scope
    scope.launch {
        AppLog.log("INFO", "cek pembaruan: memeriksa npm registry...")
        try {
            val result = runProotCmd(context, listOf("/root/node/bin/npm", "view", "omniroute", "version"))
            AppLog.log("INFO", "versi terbaru npm: ${result.trim().take(100)}")
        } catch (e: Exception) {
            AppLog.log("ERROR", "cek pembaruan gagal: ${e.message}")
        }
        updateScope = null
    }
}

private fun runProotCmd(context: Context, cmd: List<String>): String {
    val prootDir = File(context.filesDir, "proot")
    val fullCmd = listOf(
        ProotRuntime.prootBinary(context),
        "--kill-on-exit", "-0",
        "-r", ProotRuntime.rootfs(context).absolutePath,
        "-b", "/dev",
        "-b", "/proc",
        "-b", "/sys",
        "-b", ProotRuntime.nodeRoot(context).absolutePath + ":/root/node",
        "-w", "/root",
        "/usr/bin/env", "-i",
        "HOME=/root",
        "PATH=/root/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM=xterm",
    ) + cmd
    val pb = ProcessBuilder(*fullCmd.toTypedArray())
    pb.environment()["PROOT_LOADER"] = ProotRuntime.loaderBinary(context) ?: ""
    pb.environment()["PROOT_TMP_DIR"] = File(prootDir, "tmp").absolutePath
    pb.environment()["PROOT_NO_SECCOMP"] = "1"
    pb.environment()["LD_LIBRARY_PATH"] = File(prootDir, "lib").absolutePath
    pb.redirectErrorStream(true)
    val proc = pb.start()
    val output = proc.inputStream.bufferedReader().readText()
    proc.waitFor()
    return output
}

private fun openProotCli(context: Context) {
    // Jalankan perintah sederhana di dalam proot dan tampilkan hasilnya
    val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
    scope.launch {
        try {
            val result = runProotCmd(context, listOf("/root/node/bin/node", "--version"))
            val msg = "Node di dalam proot: ${result.trim()}"
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(context)
                    .setTitle("PRoot CLI")
                    .setMessage(msg)
                    .setPositiveButton("OK") { _, _ -> }
                    .show()
            }
            AppLog.log("INFO", "PRoot CLI: $msg")
        } catch (e: Exception) {
            AppLog.log("ERROR", "PRoot CLI gagal: ${e.message}")
        }
    }
}

private fun OmniRouteToggleAction(context: Context, currentlyOnline: Boolean, onDone: () -> Unit) {
    val intent = Intent(context, com.example.omniroute.service.OmniRouteService::class.java).apply {
        action = if (currentlyOnline) com.example.omniroute.service.OmniRouteService.ACTION_STOP else com.example.omniroute.service.OmniRouteService.ACTION_START
    }
    if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    AppLog.log("INFO", if (currentlyOnline) context.str("btn_stop") else context.str("btn_start"))
    onDone()
}

@Composable
private fun StreamBadge(online: Boolean, palette: ThemePalette.Palette, context: Context) {
    Row(
        modifier = Modifier
            .clip(Pill)
            .background(if (online) palette.emerald.copy(alpha = 0.15f) else palette.inactive.copy(alpha = 0.15f))
            .border(1.dp, if (online) palette.emerald.copy(alpha = 0.5f) else palette.inactive.copy(alpha = 0.3f), Pill)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (online) palette.emerald else palette.inactive))
        Spacer(Modifier.width(6.dp))
        Text(if (online) context.str("stream_active") else context.str("stream_idle"), color = palette.textHi, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun GridTelemetry(uptime: Long, hits: Long, inflight: Int, port: Int, arch: String, palette: ThemePalette.Palette, context: Context) {
    fun fmt(s: Long): String = "%02dh %02dm".format(s / 3600, (s % 3600) / 60)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        MetricCard(context.str("tele_uptime"), fmt(uptime), modifier = Modifier.weight(1f), palette = palette, context = context)
        MetricCard(context.str("tele_routes"), hits.toString(), modifier = Modifier.weight(1f), palette = palette, context = context)
        MetricCard(context.str("tele_inflight"), inflight.toString(), modifier = Modifier.weight(1f), palette = palette, context = context)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        MetricCard(context.str("tele_port"), port.toString(), modifier = Modifier.weight(1f), palette = palette, context = context)
        MetricCard(context.str("tele_arch"), arch, modifier = Modifier.weight(1f), palette = palette, context = context)
        MetricCard(context.str("tele_engine"), if (ProotRuntime.isInstalled(LocalContext.current)) context.str("engine_proot") else context.str("engine_rust"), modifier = Modifier.weight(1f), palette = palette, context = context)
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier, palette: ThemePalette.Palette, context: Context) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .shadow(10.dp, shape, ambientColor = Color.Black.copy(0.35f), spotColor = Color.Black.copy(0.4f))
            .clip(shape)
            .background(palette.surface)
            .padding(14.dp)
    ) {
        Column {
            Text(label, color = palette.textLo, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.1.sp)
            Spacer(Modifier.height(6.dp))
            Text(value, color = palette.textHi, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
        }
    }
}

@Composable
private fun PathRow(label: String, path: String, palette: ThemePalette.Palette, context: Context) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = palette.textLo, modifier = Modifier.width(150.dp), fontSize = 12.sp)
        Text(path, color = palette.textHi, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ActionButton(label: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit, palette: ThemePalette.Palette, context: Context) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (primary) palette.gold else palette.card.copy(alpha = 0.7f))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (primary) Color(0xFF1F2937) else palette.textHi, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

private fun lineColor(line: String, palette: ThemePalette.Palette): Color = when {
    line.contains("ERROR") -> palette.error
    line.contains("ROUTE") -> Color(0xFF60A5FA)
    line.contains("METRIC") -> Color(0xFFA3E635)
    else -> palette.textLo
}

@Composable
private fun SettingsTab(prefs: AppPrefs.Snapshot, onPrefsChange: (AppPrefs.Snapshot) -> Unit, palette: ThemePalette.Palette, context: Context) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ClayCard(modifier = Modifier.fillMaxWidth(), palette = palette) {
            Column(modifier = Modifier.padding(22.dp)) {
                Text(context.str("settings_title"), color = palette.textHi, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                Spacer(Modifier.height(4.dp))
                Text(context.str("settings_subtitle"), color = palette.textLo, fontSize = 12.sp)
            }
        }

        SettingSectionTitle(context.str("theme_title"), palette = palette, context = context)
        ClayCard(palette = palette) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ThemeOption(context.str("theme_dark"), prefs.theme == "dark", palette = palette, isLight = prefs.theme == "light", context = context) { AppPrefs.setTheme(context, "dark"); onPrefsChange(AppPrefs.snapshot(context)) }
            ThemeOption(context.str("theme_light"), prefs.theme == "light", palette = palette, isLight = prefs.theme == "light", context = context) { AppPrefs.setTheme(context, "light"); onPrefsChange(AppPrefs.snapshot(context)) }
            ThemeOption(context.str("theme_system"), prefs.theme == "system", palette = palette, isLight = prefs.theme == "light", context = context) { AppPrefs.setTheme(context, "system"); onPrefsChange(AppPrefs.snapshot(context)) }
            ToggleRow(context.str("oled"), prefs.oled, palette = palette, isLight = prefs.theme == "light", context = context) { AppPrefs.setOled(context, it); onPrefsChange(AppPrefs.snapshot(context)) }
        }}

        SettingSectionTitle(context.str("language_title"), palette = palette, context = context)
        ClayCard(palette = palette) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ThemeOption(context.str("lang_id"), prefs.language == "id", palette = palette, isLight = prefs.theme == "light", context = context) { AppPrefs.setLanguage(context, "id"); onPrefsChange(AppPrefs.snapshot(context)) }
            ThemeOption(context.str("lang_en"), prefs.language == "en", palette = palette, isLight = prefs.theme == "light", context = context) { AppPrefs.setLanguage(context, "en"); onPrefsChange(AppPrefs.snapshot(context)) }
        }}

        SettingSectionTitle(context.str("background_title"), palette = palette, context = context)
        ClayCard(palette = palette) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToggleRow(context.str("autostart"), prefs.autoStart, palette = palette, isLight = prefs.theme == "light", context = context) { AppPrefs.setAutoStart(context, it); onPrefsChange(AppPrefs.snapshot(context)) }
            ToggleRow(context.str("battery_exempt"), prefs.batteryExempt, palette = palette, isLight = prefs.theme == "light", context = context) {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                AppPrefs.setBatteryExempt(context, true); onPrefsChange(AppPrefs.snapshot(context))
            }
            PortEditor(prefs = prefs, onPrefsChange = onPrefsChange, palette = palette, context = context)
            Text(context.str("port_binding"), color = palette.textLo, fontSize = 12.sp)
        }}

        SettingSectionTitle(context.str("system_title"), palette = palette, context = context)
        ClayCard(palette = palette) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextRow(context.str("core_version"), context.str("setting_core_version"), palette = palette, context = context)
            TextRow(context.str("rust_engine"), context.str("setting_rust_engine"), palette = palette, context = context)
            TextRow(context.str("rootfs_env"), context.str("setting_rootfs_env"), palette = palette, context = context)
            TextRow(context.str("host_proot_os"), context.str("setting_host_os"), palette = palette, context = context)
            ActionButton(context.str("btn_check_update"), onClick = { scopeCheckUpdate(context) }, palette = palette, context = context)
            ActionButton(context.str("btn_reset_defaults"), onClick = {
                AppPrefs.reset(context)
                onPrefsChange(AppPrefs.snapshot(context))
                AppLog.log("INFO", "settings reset to defaults")
            }, palette = palette, context = context)
        }}

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SettingSectionTitle(title: String, palette: ThemePalette.Palette, context: Context) {
    Text(title, color = palette.gold, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.1.sp)
}

@Composable
private fun ThemeOption(label: String, active: Boolean, palette: ThemePalette.Palette, context: Context, isLight: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(if (active) 10.dp else 0.dp, RoundedCornerShape(20.dp), ambientColor = palette.gold.copy(alpha = 0.15f), spotColor = palette.gold.copy(alpha = 0.18f))
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) (if (isLight) palette.gold.copy(alpha = 0.10f) else Color(0xFF1C2541)) else palette.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(if (active) palette.gold else palette.inactive))
        Spacer(Modifier.width(12.dp))
        Text(label, color = if (active && isLight) Color(0xFF0F172A) else palette.textHi, fontSize = 13.sp)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, palette: ThemePalette.Palette, context: Context, isLight: Boolean = false, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = palette.textHi, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TextRow(label: String, value: String, palette: ThemePalette.Palette, context: Context) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = palette.textLo, modifier = Modifier.weight(1f), fontSize = 12.sp)
        Text(value, color = palette.textHi, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Right)
    }
}

/** Editor port: simpan ke prefs; kalau service online → restart otomatis di port baru. */
@Composable
private fun PortEditor(prefs: AppPrefs.Snapshot, onPrefsChange: (AppPrefs.Snapshot) -> Unit, palette: ThemePalette.Palette, context: Context) {
    var draft by remember(prefs.port) { mutableStateOf(prefs.port.toString()) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(context.str("port_label"), color = palette.textHi, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(context.str("port_hint"), color = palette.textLo, fontSize = 11.sp)
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.filter(Char::isDigit) },
            singleLine = true,
            modifier = Modifier.width(86.dp),
            textStyle = androidx.compose.ui.text.TextStyle(color = palette.textHi, fontSize = 13.sp)
        )
        ActionButton(context.str("port_save"), primary = true, palette = palette, context = context, onClick = {
            val p = draft.trim().toIntOrNull()
            when {
                p == null || p !in 1..65535 ->
                    AppLog.error("SETTINGS", "port tidak valid: '$draft' (harus 1-65535)")
                p == prefs.port ->
                    AppLog.log("INFO", "port tidak berubah: $p")
                else -> {
                    val wasOnline = ProotRuntime.probe(prefs.port)
                    AppPrefs.setPort(context, p)
                    onPrefsChange(AppPrefs.snapshot(context))
                    if (wasOnline) {
                        val intent = Intent(context, com.example.omniroute.service.OmniRouteService::class.java).apply {
                            action = com.example.omniroute.service.OmniRouteService.ACTION_RESTART
                        }
                        if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
                        AppLog.log("INFO", "port $prefs.port → $p • service di-restart otomatis")
                    } else {
                        AppLog.log("INFO", "port $prefs.port → $p • aktif saat service di-Start")
                    }
                    draft = p.toString()
                }
            }
        })
    }
}
