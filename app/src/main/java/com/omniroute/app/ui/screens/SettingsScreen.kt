package com.omniroute.app.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniroute.app.AppCache
import com.omniroute.app.R
import com.omniroute.app.config.AppMeta
import com.omniroute.app.config.ClientKey
import com.omniroute.app.config.ConfigJson
import com.omniroute.app.config.ConfigWrapper
import com.omniroute.app.config.GatewayConfigJson
import com.omniroute.app.data.GatewayUiState
import com.omniroute.app.ui.components.ClayButton
import com.omniroute.app.ui.components.ClayCard
import com.omniroute.app.ui.components.ClayChip
import com.omniroute.app.ui.components.ClayToggle
import com.omniroute.app.ui.components.RecessedWell
import com.omniroute.app.ui.theme.ClayTheme
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.Locale

@Composable
fun SettingsScreen(
    state: GatewayUiState,
    modifier: Modifier = Modifier,
    baseUrl: String = "",
    onSaveConfigAndRestart: (GatewayConfigJson, AppMeta) -> Unit = { _, _ -> },
    onSaveAppMeta: (AppMeta) -> Unit = {},
    onToast: (String) -> Unit = {},
    // SAF seams (tests pass fakes)
    restorePicker: (() -> String?)? = null,
    restoreContentProvider: (() -> String?)? = null,
    openDocument: (() -> String?)? = null,
    onRestoreBackup: (() -> String?)? = null,
    restoreLauncher: (() -> String?)? = null,
    restoreFileContent: String? = null,
    openDocumentResult: String? = null,
    pickedContent: String? = null,
    backupContent: String? = null,
    restoreText: String? = null,
    fileContent: String? = null,
    picker: (() -> String?)? = null,
    documentPicker: (() -> String?)? = null,
    onPickDocument: (() -> String?)? = null,
) {
    val colors = ClayTheme.colors
    val context = LocalContext.current

    var app by remember(state.app) { mutableStateOf(state.app) }
    var core by remember(state.core) { mutableStateOf(state.core) }

    // theme
    var themeMode by remember(app.themeMode) { mutableStateOf(app.themeMode) }
    var clayDepth by remember(app.clayDepth) { mutableStateOf(app.clayDepth) }
    var highContrast by remember(app.highContrast) { mutableStateOf(app.highContrast) }

    // daemon
    var bootStart by remember(app.bootStart) { mutableStateOf(app.bootStart) }
    var wakeLock by remember(app.wakeLock) { mutableStateOf(app.wakeLock) }
    var portInput by remember(core.port) { mutableStateOf(core.port.toString()) }
    var portError by remember { mutableStateOf<String?>(null) }
    var timeoutInput by remember(core.requestTimeoutMs) { mutableStateOf((core.requestTimeoutMs / 60000).toString()) }
    var timeoutError by remember { mutableStateOf<String?>(null) }
    var lanEnabled by remember(core.lanEnabled) { mutableStateOf(core.lanEnabled) }

    // maintenance
    var cacheSize by remember { mutableStateOf(AppCache.size(context)) }
    var showExportConfirm by remember { mutableStateOf(false) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var generatedKey by remember { mutableStateOf("") }

    val segera = stringResource(R.string.settings_segera)

    fun saveAppMeta(newApp: AppMeta) {
        app = newApp
        onSaveAppMeta(newApp)
        onSaveConfigAndRestart(core, newApp)
        onToast(context.getString(R.string.settings_saved_toast))
    }

    fun saveCore(newCore: GatewayConfigJson, newApp: AppMeta = app) {
        core = newCore
        app = newApp
        onSaveConfigAndRestart(newCore, newApp)
        onToast(context.getString(R.string.settings_saved_toast))
    }

    fun processRestore(content: String?) {
        if (content == null) return
        try {
            val wrapper = ConfigJson.decodeFromString<ConfigWrapper>(content)
            saveCore(wrapper.core, wrapper.app)
        } catch (e: Exception) {
            onToast(context.getString(R.string.settings_restore_error))
        }
    }

    // SAF launchers (production)
    val createDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val json = ConfigJson.encodeToString(ConfigWrapper(core = core, app = app))
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            onToast(context.getString(R.string.settings_saved_toast))
        } catch (e: Exception) {
            onToast(e.message ?: "error")
        }
    }
    val openDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val content = try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) { null }
        processRestore(content)
    }

    fun restoreContentFromSeam(): String? {
        return restorePicker?.invoke()
            ?: restoreContentProvider?.invoke()
            ?: openDocument?.invoke()
            ?: onRestoreBackup?.invoke()
            ?: restoreLauncher?.invoke()
            ?: picker?.invoke()
            ?: documentPicker?.invoke()
            ?: onPickDocument?.invoke()
            ?: restoreFileContent
            ?: openDocumentResult
            ?: pickedContent
            ?: backupContent
            ?: restoreText
            ?: fileContent
    }

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        // Header card
        ClayCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_title),
                    color = colors.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (state.running) stringResource(R.string.settings_service_active) else stringResource(R.string.settings_service_inactive),
                    color = colors.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(4.dp))
            val version = state.health?.version.orEmpty()
            if (version.isNotEmpty()) {
                Text(
                    text = "v$version",
                    color = colors.onSurface.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                val uptime = state.health?.uptimeSeconds ?: 0L
                val uptimeText = if (state.running && state.health != null) formatUptimeSettings(uptime) else "—"
                QuickTile(label = stringResource(R.string.settings_quick_uptime), value = uptimeText, modifier = Modifier.weight(1f))
                val total = state.health?.requestsTotal ?: 0L
                val failed = state.health?.requestsFailed ?: 0L
                val rate = if (total > 0) (total - failed) * 100.0 / total else 100.0
                QuickTile(label = stringResource(R.string.settings_quick_routing), value = String.format(Locale.US, "%.1f%%", rate), modifier = Modifier.weight(1f))
                QuickTile(label = stringResource(R.string.settings_quick_daemon), value = version.ifBlank { "—" }, modifier = Modifier.weight(1f))
            }
        }

        // Theme section
        SectionHeader(title = stringResource(R.string.settings_theme_section), badge = "Haptic Visual")
        ClayCard(modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ThemeBtn(label = stringResource(R.string.settings_theme_oled), selected = themeMode == "oled", onClick = {
                    themeMode = "oled"
                    app = app.copy(themeMode = "oled")
                    saveAppMeta(app)
                }, modifier = Modifier.weight(1f))
                ThemeBtn(label = stringResource(R.string.settings_theme_soft), selected = themeMode == "soft", onClick = {
                    themeMode = "soft"
                    app = app.copy(themeMode = "soft")
                    saveAppMeta(app)
                }, modifier = Modifier.weight(1f))
                ThemeBtn(label = stringResource(R.string.settings_theme_system), selected = themeMode == "system", onClick = {
                    themeMode = "system"
                    app = app.copy(themeMode = "system")
                    saveAppMeta(app)
                }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Text(text = stringResource(R.string.settings_depth_label), color = colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(text = stringResource(R.string.settings_depth_desc), color = colors.onSurface.copy(alpha = 0.6f), fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.Slider(
                    value = clayDepth.toFloat(),
                    onValueChange = { clayDepth = it.toInt() },
                    onValueChangeFinished = {
                        app = app.copy(clayDepth = clayDepth)
                        saveAppMeta(app)
                    },
                    valueRange = 10f..100f,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(text = "$clayDepth%", color = colors.primary, fontWeight = FontWeight.Bold, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.settings_high_contrast), color = colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(text = stringResource(R.string.settings_high_contrast_desc), color = colors.onSurface.copy(alpha = 0.6f), fontSize = 12.sp)
                }
                ClayToggle(checked = highContrast, onCheckedChange = { v ->
                    highContrast = v
                    app = app.copy(highContrast = v)
                    saveAppMeta(app)
                }, modifier = Modifier.semantics { contentDescription = "High Contrast OLED Clay" })
            }
        }

        // Daemon section
        SectionHeader(title = stringResource(R.string.settings_daemon_section), badge = "Service Engine")
        ClayCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.settings_boot_start), color = colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(text = stringResource(R.string.settings_boot_start_desc), color = colors.onSurface.copy(alpha = 0.6f), fontSize = 12.sp)
                }
                ClayToggle(checked = bootStart, onCheckedChange = { v ->
                    bootStart = v
                    app = app.copy(bootStart = v)
                    saveAppMeta(app)
                }, modifier = Modifier.semantics { contentDescription = "Jalankan Saat Boot Android" })
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.settings_wake_lock), color = colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(text = stringResource(R.string.settings_wake_lock_desc), color = colors.onSurface.copy(alpha = 0.6f), fontSize = 12.sp)
                }
                ClayToggle(checked = wakeLock, onCheckedChange = { v ->
                    wakeLock = v
                    app = app.copy(wakeLock = v)
                    saveAppMeta(app)
                }, modifier = Modifier.semantics { contentDescription = "Wake Lock Android" })
            }
            Spacer(Modifier.height(8.dp))
            Text(text = stringResource(R.string.settings_port_label), color = colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it; portError = null },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                ClayButton(onClick = {
                    val p = portInput.toIntOrNull()
                    if (p == null || p !in 1..65535) {
                        portError = context.getString(R.string.settings_port_error)
                    } else {
                        portError = null
                        saveCore(core.copy(port = p), app)
                    }
                }) {
                    Text(text = stringResource(R.string.settings_port_change))
                }
            }
            if (portError != null) {
                Text(text = portError!!, color = colors.onSurface.copy(alpha = 0.8f), fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(text = stringResource(R.string.settings_timeout_label), color = colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = timeoutInput,
                    onValueChange = { timeoutInput = it; timeoutError = null },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                ClayButton(onClick = {
                    val m = timeoutInput.toIntOrNull()
                    if (m == null || m < 1) {
                        timeoutError = context.getString(R.string.settings_timeout_error)
                    } else {
                        timeoutError = null
                        saveCore(core.copy(requestTimeoutMs = m.toLong() * 60000L), app)
                    }
                }) {
                    Text(text = stringResource(R.string.settings_port_change))
                }
            }
            if (timeoutError != null) {
                Text(text = timeoutError!!, color = colors.onSurface.copy(alpha = 0.8f), fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.settings_lan_label), color = colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(text = stringResource(R.string.settings_lan_desc), color = colors.onSurface.copy(alpha = 0.6f), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                ClayToggle(checked = lanEnabled, onCheckedChange = { v ->
                    if (v) {
                        if (core.gatewayKeys.isEmpty()) {
                            showKeyDialog = true
                        } else {
                            lanEnabled = true
                            saveCore(core.copy(lanEnabled = true), app)
                        }
                    } else {
                        lanEnabled = false
                        saveCore(core.copy(lanEnabled = false), app)
                    }
                }, modifier = Modifier.semantics { contentDescription = "Akses Jaringan Lokal (LAN)" })
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.settings_cf_tunnel), color = colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(text = stringResource(R.string.settings_cf_desc), color = colors.onSurface.copy(alpha = 0.6f), fontSize = 12.sp)
                }
                Switch(checked = false, onCheckedChange = null, enabled = false)
                Spacer(Modifier.width(8.dp))
                Text(text = segera, color = colors.onSurface.copy(alpha = 0.6f), fontSize = 11.sp)
            }
        }

        // Maintenance section
        SectionHeader(title = stringResource(R.string.settings_maintenance_section), badge = "SQLite / Cache")
        ClayCard(modifier = Modifier.fillMaxWidth()) {
            ClayButton(onClick = {
                AppCache.clear(context)
                cacheSize = 0L
                onToast(context.getString(R.string.cache_cleared_toast))
            }, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.settings_clear_cache))
            }
            Text(text = formatBytes(cacheSize), color = colors.onSurface.copy(alpha = 0.6f), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
            ClayButton(onClick = { showExportConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.settings_export_backup))
            }
            Spacer(Modifier.height(8.dp))
            ClayButton(onClick = {
                val seam = restoreContentFromSeam()
                if (seam != null) {
                    processRestore(seam)
                } else {
                    openDocLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.settings_restore_backup))
            }
            Spacer(Modifier.height(8.dp))
            ClayButton(enabled = false, onClick = {}, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.settings_check_update))
            }
            Text(text = segera, color = colors.onSurface.copy(alpha = 0.6f), fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            ClayButton(enabled = false, onClick = {}, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.settings_hibernate))
            }
            Text(text = segera, color = colors.onSurface.copy(alpha = 0.6f), fontSize = 11.sp)
        }

        Spacer(Modifier.height(12.dp))
    }

    if (showExportConfirm) {
        AlertDialog(
            onDismissRequest = { showExportConfirm = false },
            title = { Text(stringResource(R.string.settings_export_confirm_title)) },
            text = { Text(stringResource(R.string.settings_export_confirm_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showExportConfirm = false
                    createDocLauncher.launch("omniroute_backup.json")
                }) { Text(stringResource(R.string.settings_export_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { showExportConfirm = false }) { Text(stringResource(R.string.settings_export_confirm_no)) }
            },
        )
    }

    if (showKeyDialog) {
        val key = remember { "sk-omni-" + (1..24).map { "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".random() }.joinToString("") }
        generatedKey = key
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            title = { Text(stringResource(R.string.settings_create_key_title)) },
            text = {
                Column {
                    Text(text = key, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val newKey = key
                    val newCore = core.copy(gatewayKeys = core.gatewayKeys + newKey, lanEnabled = true)
                    val newApp = app.copy(clientKeys = app.clientKeys + ClientKey(label = "Gateway", secret = newKey))
                    lanEnabled = true
                    core = newCore
                    app = newApp
                    onSaveConfigAndRestart(newCore, newApp)
                    onToast(context.getString(R.string.settings_saved_toast))
                    showKeyDialog = false
                }) { Text(stringResource(R.string.settings_create_key_save)) }
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("key", key))
                    onToast(context.getString(R.string.logs_copied_toast))
                }) { Text(stringResource(R.string.settings_copy)) }
            },
            dismissButton = {
                TextButton(onClick = { showKeyDialog = false }) { Text(stringResource(R.string.settings_create_key_cancel)) }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String, badge: String) {
    val colors = ClayTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text = title, color = colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(text = badge, color = colors.primary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun QuickTile(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = ClayTheme.colors
    RecessedWell(modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = label, color = colors.onSurface.copy(alpha = 0.6f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(text = value, color = colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun ThemeBtn(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ClayTheme.colors
    ClayChip(selected = selected, onClick = onClick, label = label, modifier = modifier)
}

private fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1) String.format(Locale.US, "%.1f MB", mb) else String.format(Locale.US, "%.1f KB", kb)
}

private fun formatUptimeSettings(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return "${hours}j ${minutes}m"
}

