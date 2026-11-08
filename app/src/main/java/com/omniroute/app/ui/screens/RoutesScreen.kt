package com.omniroute.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniroute.app.config.AppMeta
import com.omniroute.app.config.ComboJson
import com.omniroute.app.config.ComboStepJson
import com.omniroute.app.config.GatewayConfigJson
import com.omniroute.app.config.ProviderConfigJson
import com.omniroute.app.data.GatewayUiState
import com.omniroute.app.data.LogLine
import com.omniroute.app.ui.components.ClayButton
import com.omniroute.app.ui.components.ClayCard
import com.omniroute.app.ui.components.ClayChip
import com.omniroute.app.ui.components.ClayToggle
import com.omniroute.app.ui.components.RecessedWell
import com.omniroute.app.ui.theme.ClayTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private data class RouteItem(val combo: ComboJson, val step: ComboStepJson)

@Composable
fun RoutesScreen(
    state: GatewayUiState,
    modifier: Modifier = Modifier,
    baseUrl: String = "",
    onSaveConfigAndRestart: (GatewayConfigJson, AppMeta) -> Unit = { _, _ -> },
    onToast: (String) -> Unit = {},
    onOpenComboEditor: (String) -> Unit = {},
) {
    val colors = ClayTheme.colors
    val scope = rememberCoroutineScope { Dispatchers.IO }
    val resolvedBaseUrl = baseUrl.ifBlank {
        val core = state.core
        val host = if (core.bind == "0.0.0.0") "127.0.0.1" else core.bind
        "http://$host:${core.port}"
    }

    val combos = state.core.combos
    val defaultComboName = state.core.defaultCombo ?: combos.firstOrNull()?.name
    val healthByProvider = state.health?.providers.orEmpty().associateBy { it.id }
    val providersById = state.core.providers.associateBy { it.id }

    val allRoutes = combos.flatMap { combo -> combo.steps.map { RouteItem(combo, it) } }

    var query by remember { mutableStateOf("") }
    var selectedChip by remember { mutableStateOf("Semua") }
    var pendingDelete by remember { mutableStateOf<Pair<String, ComboStepJson>?>(null) }

    val filteredRoutes = allRoutes.filter { item ->
        val chipOk = when (selectedChip) {
            "Default" -> item.combo.name == defaultComboName
            "Cadangan" -> item.combo.name != defaultComboName
            else -> true
        }
        val searchOk = query.isBlank() ||
            item.step.provider.contains(query, ignoreCase = true) ||
            item.step.model.contains(query, ignoreCase = true)
        chipOk && searchOk
    }

    fun ping(providerId: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val conn = URL("$resolvedBaseUrl/providers/$providerId/ping").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.doOutput = true
                    conn.outputStream.close()
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                    Triple(code, body, null as String?)
                } catch (e: Exception) {
                    Triple(0, "", e.message)
                }
            }
            val (_, body, err) = result
            if (err != null) {
                withContext(Dispatchers.Main) { onToast("Tidak terjangkau: $err") }
                return@launch
            }
            try {
                val json = JSONObject(body)
                val ok = json.optBoolean("ok", false)
                if (ok) {
                    val latency = json.optLong("latency_ms", 0L)
                    withContext(Dispatchers.Main) { onToast("Latensi: $latency ms") }
                } else {
                    val error = json.optString("error", "error")
                    withContext(Dispatchers.Main) { onToast("Tidak terjangkau: $error") }
                }
            } catch (e: Exception) {
                val msg = e.message
                withContext(Dispatchers.Main) { onToast("Tidak terjangkau: $msg") }
            }
        }
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
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Filled.Route,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "${combos.size} rantai · ${state.health?.providers.orEmpty().count { it.healthy }} provider sehat",
                    color = colors.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.primary.copy(alpha = 0.16f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "TERSINKRON",
                        color = colors.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // Search
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Cari provider atau model...") },
            modifier = Modifier.fillMaxWidth(),
        )

        // Chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClayChip(selected = selectedChip == "Semua", onClick = { selectedChip = "Semua" }, label = "Semua")
            ClayChip(selected = selectedChip == "Default", onClick = { selectedChip = "Default" }, label = "Default")
            ClayChip(selected = selectedChip == "Cadangan", onClick = { selectedChip = "Cadangan" }, label = "Cadangan")
        }

        // CTA banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(colors.primary.copy(alpha = 0.35f), colors.surface))),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Susun rantai fallback provider & model",
                    color = colors.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                ClayButton(onClick = { onOpenComboEditor("") }) {
                    Text(text = "+ Tambah Rute Baru")
                }
            }
        }

        // Route cards
        filteredRoutes.forEach { item ->
            val combo = item.combo
            val step = item.step
            val stat = healthByProvider[step.provider]
            val providerCfg = providersById[step.provider]
            val kind = providerCfg?.kind ?: "OpenAiCompatible"
            val enabled = combo.enabled
            val badge = if (!enabled) "Nonaktif" else if (combo.name == defaultComboName) "PRIMER" else "CADANGAN"
            val statusText = when (stat?.healthy) {
                true -> "Sehat"
                false -> "Gagal — ${stat.lastError ?: ""}".trimEnd()
                null -> "—"
            }

            ClayCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (enabled) 1f else 0.5f)
                    .clickable(enabled = enabled, onClick = { onOpenComboEditor(combo.name) }),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(
                        imageVector = if (kind == "Anthropic") androidx.compose.material.icons.Icons.Filled.Person else androidx.compose.material.icons.Icons.Filled.ModelTraining,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${step.provider} • ${step.model}",
                        color = colors.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                    ClayToggle(
                        checked = enabled,
                        onCheckedChange = { checked ->
                            val updated = state.core.combos.map {
                                if (it.name == combo.name) it.copy(enabled = checked) else it
                            }
                            onSaveConfigAndRestart(state.core.copy(combos = updated), state.app)
                            onToast(if (checked) "Node diaktifkan" else "Rute diperbarui")
                        },
                        modifier = Modifier.semantics { contentDescription = "toggle enabled" },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Latensi", color = colors.onSurface.copy(alpha = 0.6f), fontSize = 10.sp)
                        Text("${stat?.lastLatencyMs ?: 0} ms", color = colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Status", color = colors.onSurface.copy(alpha = 0.6f), fontSize = 10.sp)
                        Text(statusText, color = colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Permintaan", color = colors.onSurface.copy(alpha = 0.6f), fontSize = 10.sp)
                        Text("${stat?.requests ?: 0}", color = colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(colors.primary.copy(alpha = 0.16f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = badge,
                            color = colors.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    ClayButton(onClick = { ping(step.provider) }) {
                        Text(text = "Ping")
                    }
                    ClayButton(onClick = { onOpenComboEditor(combo.name) }) {
                        Text(text = "Edit")
                    }
                    ClayButton(onClick = { pendingDelete = combo.name to step }) {
                        Text(text = "Hapus")
                    }
                }
            }
        }

        // Log request terakhir
        ClayCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Log Request Terakhir",
                color = colors.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(8.dp))
            RecessedWell(modifier = Modifier.fillMaxWidth()) {
                val logs = state.health?.logs.orEmpty().takeLast(5)
                if (logs.isEmpty()) {
                    Text(
                        text = "—",
                        color = colors.onSurface.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        logs.forEach { log: LogLine ->
                            Text(
                                text = log.message.ifBlank { "${log.ts} [${log.level}] ${log.message}".trim() },
                                color = colors.onSurface,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }

    if (pendingDelete != null) {
        val (comboName, step) = pendingDelete!!
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Hapus Rute") },
            text = { Text("Hapus ${step.provider} • ${step.model}?") },
            confirmButton = {
                TextButton(onClick = {
                    val updatedCombos = state.core.combos.map { c ->
                        if (c.name == comboName) {
                            c.copy(steps = c.steps.filterNot { it.provider == step.provider && it.model == step.model })
                        } else c
                    }
                    onSaveConfigAndRestart(state.core.copy(combos = updatedCombos), state.app)
                    onToast("Rute diperbarui")
                    pendingDelete = null
                }) {
                    Text("Hapus")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Batal")
                }
            },
        )
    }
}
