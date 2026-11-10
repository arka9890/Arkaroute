package com.example.omniroute

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebView
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private val Gold = Color(0xFFE8B74A)
private val Emerald = Color(0xFF34D399)
private val Slate = Color(0xFF0B1220)
private val SlateCard = Color(0xFF131B2E)
private val TextHi = Color(0xFFF8FAFC)
private val TextLo = Color(0xFF94A3B8)
private val CardShape = RoundedCornerShape(32.dp)
private val Pill = RoundedCornerShape(999.dp)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.log("BOOT", "OmniRoute Rust core ready (arm64-v8a)")
        setContent { OmniRouteApp() }
    }
}

private fun Context.isBatteryOptimIgnorable(): Boolean {
    val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    return pm.isIgnoringBatteryOptimizations(packageName)
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
                val online = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ProotRuntime.probe(prefs.port) }
                statusText = if (online) "{\"status\":\"online\",\"port\":${prefs.port},\"engine\":\"omniroute-proot\"}" else "{\"status\":\"standby\",\"port\":${prefs.port},\"engine\":\"omniroute-proot\"}"
            } else {
                statusText = NativeBridge.statusJson()
            }
        }
    }

    val background = if (prefs.oled && prefs.theme == "dark") Color(0xFF000000) else Slate

    Column(
        modifier = Modifier.fillMaxSize().background(background).statusBarsPadding().navigationBarsPadding()
    ) {
        HeaderStrip(background = background, status = statusText, prefs = prefs)

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
                )
                1 -> DashboardTab(prefs = prefs)
                else -> SettingsTab(
                    prefs = prefs,
                    onPrefsChange = { prefs = it },
                )
            }
        }

        BottomNavBar(selected = tab, onSelect = { tab = it })
    }
}

@Composable
private fun HeaderStrip(background: Color, status: String, prefs: AppPrefs.Snapshot) {
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
                    .background(if (online) Emerald.copy(alpha = 0.18f) else Color(0xFF1E293B))
                    .border(1.dp, if (online) Emerald.copy(alpha = 0.55f) else Color(0xFF334155), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("OR", color = Gold, fontWeight = FontWeight.Black, fontSize = 15.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("OmniRoute", color = TextHi, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    StatusDot(online = online)
                }
                Text(if (online) "Daemon online • ${prefs.port}\n" else "Standby • port ${prefs.port}", color = TextLo, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun StatusDot(online: Boolean) {
    val c = if (online) Emerald else Color(0xFF475569)
    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(c))
}

@Composable
private fun BottomNavBar(selected: Int, onSelect: (Int) -> Unit) {
    val items = listOf("Service", "Dashboard", "Settings")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp)
            .shadow(18.dp, Pill, ambientColor = Color.Black.copy(0.35f), spotColor = Color.Black.copy(0.45f))
            .clip(Pill)
            .background(SlateCard.copy(alpha = 0.96f))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(Pill)
                    .background(if (active) Gold.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = if (active) Gold else TextLo, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ClayCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier,
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = SlateCard),
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
) {
    val context = LocalContext.current
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
            ClayCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Engine OmniRoute Belum Terpasang", color = TextHi, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                    Text(
                        "Butuh sekali instal: rootfs Debian 12, Node.js 22, dan paket omniroute. Komponen yang sudah ada otomatis dilewati.",
                        color = TextLo, fontSize = 12.sp
                    )
                    if (setupProgress.isNotEmpty()) Text(setupProgress, color = Gold, fontSize = 12.sp)
                    ActionButton(
                        label = if (setupRunning) "Memasang..." else "Install Engine",
                        modifier = Modifier.fillMaxWidth(),
                        primary = !setupRunning,
                        onClick = { if (!setupRunning) onInstall() }
                    )
                }
            }
        }

        ClayCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("PRoot: " + if (online) "Running" else "Stopped", color = TextHi, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp)
                        Text("Gateway http://127.0.0.1:${prefs.port}", color = TextLo, fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    StreamBadge(online = online)
                }

                GridTelemetry(uptime = uptime, hits = hits, inflight = inflight, port = prefs.port, arch = "aarch64")

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(
                        label = if (online) "Stop" else "Start",
                        modifier = Modifier.weight(1f),
                        primary = true,
                        onClick = { OmniRouteToggleAction(context, online, onUpdatePrefs) }
                    )
                    ActionButton("Restart", modifier = Modifier.weight(1f), onClick = {
                        val intent = Intent(context, com.example.omniroute.service.OmniRouteService::class.java).apply { action = com.example.omniroute.service.OmniRouteService.ACTION_RESTART }
                        if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
                        AppLog.log("ROUTE", "Restart requested")
                        onUpdatePrefs()
                    })
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("Git Pull", modifier = Modifier.weight(1f), onClick = {
                        AppLog.log("INFO", "git pull dispatch (native stub)")
                    })
                    ActionButton("Flush Cache", modifier = Modifier.weight(1f), onClick = {
                        NativeBridge.flushCache()
                        AppLog.log("METRIC", "cache flushed")
                    })
                    ActionButton("PRoot CLI", modifier = Modifier.weight(1f), onClick = {
                        AppLog.log("INFO", "opening PRoot shell placeholder")
                    })
                }
            }
        }

        ClayCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("PROOT-SERVICE.LOG", color = Gold, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, letterSpacing = 1.2.sp)
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
                        Text(line, color = lineColor(line), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }
        }

        ClayCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("System Paths", color = TextHi, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                PathRow("Gateway", "http://127.0.0.1:${prefs.port}")
                PathRow("Rootfs Debian", "/data/data/com.example.omniroute/files/debian")
                PathRow("Architecture", "aarch64")
                PathRow("Upstream", prefs.upstream)
                PathRow("Rootfs Distro", "Debian 12 Bookworm PRoot")
            }
        }
    }
}

private fun OmniRouteToggleAction(context: Context, currentlyOnline: Boolean, onDone: () -> Unit) {
    val intent = Intent(context, com.example.omniroute.service.OmniRouteService::class.java).apply {
        action = if (currentlyOnline) com.example.omniroute.service.OmniRouteService.ACTION_STOP else com.example.omniroute.service.OmniRouteService.ACTION_START
    }
    if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    AppLog.log("INFO", if (currentlyOnline) "Stop requested" else "Start requested")
    onDone()
}

@Composable
private fun StreamBadge(online: Boolean) {
    Row(
        modifier = Modifier
            .clip(Pill)
            .background(if (online) Emerald.copy(alpha = 0.15f) else Color(0xFF1E293B))
            .border(1.dp, if (online) Emerald.copy(alpha = 0.5f) else Color(0xFF334155), Pill)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (online) Emerald else Color(0xFF64748B)))
        Spacer(Modifier.width(6.dp))
        Text(if (online) "Stream Active" else "Stream idle", color = TextHi, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun GridTelemetry(uptime: Long, hits: Long, inflight: Int, port: Int, arch: String) {
    fun fmt(s: Long): String = "%02dh %02dm".format(s / 3600, (s % 3600) / 60)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        MetricCard("UPTIME", fmt(uptime), modifier = Modifier.weight(1f))
        MetricCard("ROUTES", hits.toString(), modifier = Modifier.weight(1f))
        MetricCard("INFLIGHT", inflight.toString(), modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        MetricCard("PORT", port.toString(), modifier = Modifier.weight(1f))
        MetricCard("ARCH", arch, modifier = Modifier.weight(1f))
        MetricCard("ENGINE", "Rust", modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .shadow(10.dp, shape, ambientColor = Color.Black.copy(0.35f), spotColor = Color.Black.copy(0.4f))
            .clip(shape)
            .background(Color(0xFF18213A))
            .padding(14.dp)
    ) {
        Column {
            Text(label, color = TextLo, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.1.sp)
            Spacer(Modifier.height(6.dp))
            Text(value, color = TextHi, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
        }
    }
}

@Composable
private fun PathRow(label: String, path: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = TextLo, modifier = Modifier.width(150.dp), fontSize = 12.sp)
        Text(path, color = TextHi, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ActionButton(label: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (primary) Gold else SlateCard.copy(alpha = 0.7f))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (primary) Color(0xFF1F2937) else TextHi, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

private fun lineColor(line: String): Color = when {
    line.contains("ERROR") -> Color(0xFFF87171)
    line.contains("ROUTE") -> Color(0xFF60A5FA)
    line.contains("METRIC") -> Color(0xFFA3E635)
    else -> Color(0xFFCBD5E1)
}

@Composable
private fun DashboardTab(prefs: AppPrefs.Snapshot) {
    val context = LocalContext.current
    var url by remember { mutableStateOf("http://127.0.0.1:${prefs.port}") }
    var stopLoad by remember { mutableStateOf(false) }
    var webView: WebView? by remember { mutableStateOf(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("OmniRoute WebView", color = TextHi, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            QuickProbeButton(port = prefs.port)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TabGroupPill("Gateway")
            Spacer(Modifier.weight(1f))
            TabGroupPill("Local LLMs")
            TabGroupPill("Docs / Swagger")
        }

        ClayCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("http://127.0.0.1:8080") }
                    )
                    ActionButton("Refresh", onClick = { webView?.loadUrl(url) })
                    ActionButton("Stop", onClick = { webView?.stopLoading() })
                    ActionButton("Browser", onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) })
                }
            }
        }

        ClayCard(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                        loadUrl(url)
                        webView = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            UpstreamStatus("openai-main", "200 OK")
            UpstreamStatus("anthropic-fallback", "Standby")
            UpstreamStatus("ollama-local", "200 OK")
        }
    }
}

@Composable
private fun QuickProbeButton(port: Int) {
    ActionButton("Quick Test", onClick = {
        val ok = NativeBridge.health(port) == 1
        AppLog.log("METRIC", "GET /healthz => ${if (ok) "online" else "standby"}")
    }, primary = true)
}

@Composable
private fun TabGroupPill(label: String) {
    Box(
        modifier = Modifier
            .clip(Pill)
            .background(SlateCard)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(label, color = TextLo, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun UpstreamStatus(name: String, state: String) {
    val ok = state.startsWith("200")
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(SlateCard.copy(alpha = 0.8f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) Emerald else Color(0xFFFACC15)))
        Spacer(Modifier.width(8.dp))
        Text(name, color = TextHi, fontSize = 11.sp)
        Spacer(Modifier.width(8.dp))
        Text(state, color = if (ok) Emerald else Color(0xFFFACC15), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingsTab(prefs: AppPrefs.Snapshot, onPrefsChange: (AppPrefs.Snapshot) -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ClayCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(22.dp)) {
                Text("Settings", color = TextHi, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                Spacer(Modifier.height(4.dp))
                Text("Appearance, persistence, and update channel", color = TextLo, fontSize = 12.sp)
            }
        }

        SettingSectionTitle("Theme Selection")
        ClayCard { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ThemeOption("Gelap (Dark Claymorphism)", prefs.theme == "dark") { AppPrefs.setTheme(context, "dark"); onPrefsChange(AppPrefs.snapshot(context)) }
            ThemeOption("Terang (Light Claymorphism)", prefs.theme == "light") { AppPrefs.setTheme(context, "light"); onPrefsChange(AppPrefs.snapshot(context)) }
            ThemeOption("Sistem (Auto Follow)", prefs.theme == "system") { AppPrefs.setTheme(context, "system"); onPrefsChange(AppPrefs.snapshot(context)) }
            ToggleRow("OLED True Black (#000000)", prefs.oled) { AppPrefs.setOled(context, it); onPrefsChange(AppPrefs.snapshot(context)) }
        }}

        SettingSectionTitle("Lokalisasi")
        ClayCard { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ThemeOption("Bahasa Indonesia", prefs.language == "id") { AppPrefs.setLanguage(context, "id"); onPrefsChange(AppPrefs.snapshot(context)) }
            ThemeOption("English (US)", prefs.language == "en") { AppPrefs.setLanguage(context, "en"); onPrefsChange(AppPrefs.snapshot(context)) }
        }}

        SettingSectionTitle("Background Daemon (Keep-Alive)")
        ClayCard { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToggleRow("Auto-start on Boot", prefs.autoStart) { AppPrefs.setAutoStart(context, it); onPrefsChange(AppPrefs.snapshot(context)) }
            ToggleRow("Keep-Alive Background Service", prefs.batteryExempt) {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                AppPrefs.setBatteryExempt(context, true); onPrefsChange(AppPrefs.snapshot(context))
            }
            TextRow("Listening Local Port", prefs.port.toString())
            Text("Binding: 127.0.0.1 only", color = TextLo, fontSize = 12.sp)
        }}

        SettingSectionTitle("Sistem & Update Channel")
        ClayCard { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextRow("Core Version", "OmniRoute JNI bridge 1.0.0")
            TextRow("Rust Engine", "libomniroute.so (cdylib)")
            TextRow("Rootfs Environment", "Debian 12 Bookworm")
            TextRow("Host PRoot OS", "Linux 5.10+ (PRoot)")
            ActionButton("Cek Pembaruan", onClick = { AppLog.log("INFO", "update check requested") })
            ActionButton("Reset ke Pengaturan Default", onClick = {
                AppPrefs.reset(context)
                onPrefsChange(AppPrefs.snapshot(context))
                AppLog.log("INFO", "settings reset to defaults")
            })
        }}

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SettingSectionTitle(title: String) {
    Text(title, color = Gold, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.1.sp)
}

@Composable
private fun ThemeOption(label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(if (active) 10.dp else 0.dp, RoundedCornerShape(20.dp), ambientColor = Gold.copy(alpha = 0.15f), spotColor = Gold.copy(alpha = 0.18f))
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) Color(0xFF1C2541) else SlateCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(if (active) Gold else Color(0xFF475569)))
        Spacer(Modifier.width(12.dp))
        Text(label, color = TextHi, fontSize = 13.sp)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = TextHi, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TextRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = TextLo, modifier = Modifier.weight(1f), fontSize = 12.sp)
        Text(value, color = TextHi, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Right)
    }
}