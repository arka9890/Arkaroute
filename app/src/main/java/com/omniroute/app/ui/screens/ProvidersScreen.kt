package com.omniroute.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniroute.app.config.AppMeta
import com.omniroute.app.config.GatewayConfigJson
import com.omniroute.app.data.GatewayUiState
import com.omniroute.app.ui.components.ClayButton
import com.omniroute.app.ui.components.ClayCard
import com.omniroute.app.ui.components.ClayChip
import com.omniroute.app.ui.components.RecessedWell
import com.omniroute.app.ui.theme.ClayTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val NO_VALUE = "—"

@Composable
fun ProvidersScreen(
    state: GatewayUiState,
    modifier: Modifier = Modifier,
    baseUrl: String = "",
    onSaveConfigAndRestart: (GatewayConfigJson, AppMeta) -> Unit = { _, _ -> },
    onToast: (String) -> Unit = {},
) {
    val colors = ClayTheme.colors
    val scope = rememberCoroutineScope { Dispatchers.IO }
    val resolvedBaseUrl = baseUrl.ifBlank {
        val core = state.core
        val host = if (core.bind == "0.0.0.0") "127.0.0.1" else core.bind
        "http://$host:${core.port}"
    }

    val providers = state.core.providers
    val healthByProvider = state.health?.providers.orEmpty().associateBy { it.id }
    val manualLimits = state.app.providerLimits

    var selectedFilter by remember { mutableStateOf("Semua") }
    val filtered = when (selectedFilter) {
        "OpenAI-compatible" -> providers.filter { it.kind == "OpenAiCompatible" }
        "Anthropic" -> providers.filter { it.kind == "Anthropic" }
        else -> providers
    }

    var showEditor by remember { mutableStateOf(false) }

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
                onToast("Tidak terjangkau: $err")
                return@launch
            }
            try {
                val json = JSONObject(body)
                val ok = json.optBoolean("ok", false)
                if (ok) {
                    val latency = json.optLong("latency_ms", 0L)
                    onToast("Latensi: $latency ms")
                } else {
                    val error = json.optString("error", "error")
                    onToast("Tidak terjangkau: $error")
                }
            } catch (e: Exception) {
                onToast("Tidak terjangkau: ${e.message}")
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

        ClayCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Penyedia",
                    color = colors.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f),
                )
                ClayButton(onClick = { showEditor = true }) {
                    Text(text = "+ Provider")
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            val connectedCount = providers.size
            SummaryPill(label = "TERHUBUNG", value = "$connectedCount Terhubung", modifier = Modifier.weight(1f))
            val hasManual = manualLimits.isNotEmpty()
            val totalUsage = state.health?.providers.orEmpty().sumOf { it.tokensIn + it.tokensOut }
            val totalLimit = manualLimits.values.sum()
            val pct = if (hasManual && totalLimit > 0) (totalUsage * 100 / totalLimit).toInt() else 0
            SummaryPill(
                label = "KUOTA TOTAL",
                value = if (hasManual) "$pct%" else NO_VALUE,
                modifier = Modifier.weight(1f),
            )
            val fallback = if (state.core.combos.isNotEmpty()) "Aktif" else NO_VALUE
            SummaryPill(label = "FALLBACK", value = fallback, modifier = Modifier.weight(1f))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClayChip(selected = selectedFilter == "Semua", onClick = { selectedFilter = "Semua" }, label = "Semua")
            ClayChip(selected = selectedFilter == "OpenAI-compatible", onClick = { selectedFilter = "OpenAI-compatible" }, label = "OpenAI-compatible")
            ClayChip(selected = selectedFilter == "Anthropic", onClick = { selectedFilter = "Anthropic" }, label = "Anthropic")
        }

        filtered.forEach { p ->
            val stat = healthByProvider[p.id]
            val limit = manualLimits[p.id]
            ClayCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = p.id,
                        color = colors.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (p.kind == "OpenAiCompatible") "OpenAI-compatible" else "Anthropic",
                        color = colors.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusPill(healthy = stat?.healthy ?: true)
                }
                Spacer(Modifier.height(8.dp))
                RecessedWell(modifier = Modifier.fillMaxWidth()) {
                    if (limit != null && limit > 0) {
                        val usage = (stat?.tokensIn ?: 0L) + (stat?.tokensOut ?: 0L)
                        val cardPct = (usage * 100 / limit).toInt()
                        Text(
                            text = "$cardPct%",
                            color = colors.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { cardPct / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "permintaan: ${stat?.requests ?: 0}",
                            color = colors.onSurface.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                        )
                        Text(
                            text = "masuk: ${stat?.tokensIn ?: 0} · keluar: ${stat?.tokensOut ?: 0}",
                            color = colors.onSurface.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                        )
                        Text(
                            text = "latensi: ${stat?.lastLatencyMs ?: 0} ms",
                            color = colors.onSurface.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                        )
                    } else {
                        Text(
                            text = NO_VALUE,
                            color = colors.onSurface.copy(alpha = 0.6f),
                            fontSize = 14.sp,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClayButton(onClick = { ping(p.id) }) {
                        Text(text = "Uji")
                    }
                    ClayButton(enabled = false, onClick = {}) {
                        Text(text = "Rotasi Sesi")
                    }
                    Text(
                        text = "segera",
                        color = colors.onSurface.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
        }

        ClayCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Pohon Fallback Otomatis",
                color = colors.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(8.dp))
            val combo = state.core.defaultCombo?.let { name ->
                state.core.combos.firstOrNull { it.name == name }
            } ?: state.core.combos.firstOrNull()
            combo?.steps?.forEachIndexed { index, step ->
                Text(
                    text = "${index + 1}. ${step.provider} • ${step.model}",
                    color = colors.onSurface,
                    fontSize = 13.sp,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
    }

    if (showEditor) {
        ProviderEditorDialog(
            state = state,
            onDismiss = { showEditor = false },
            onSave = { core, app ->
                onSaveConfigAndRestart(core, app)
                showEditor = false
            },
        )
    }
}

@Composable
private fun SummaryPill(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = ClayTheme.colors
    ClayCard(modifier = modifier) {
        Text(
            text = label,
            color = colors.onSurface.copy(alpha = 0.6f),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            color = colors.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun StatusPill(healthy: Boolean) {
    val colors = ClayTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (healthy) colors.primary else colors.onSurface.copy(alpha = 0.25f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = if (healthy) "Sehat" else "Tidak sehat",
            color = if (healthy) Color(0xFF06222B) else colors.onSurface,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
