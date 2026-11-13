package com.omniroute.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniroute.app.R
import com.omniroute.app.data.GatewayUiState
import com.omniroute.app.data.HealthSnapshot
import com.omniroute.app.ui.components.ClayButton
import com.omniroute.app.ui.components.ClayCard
import com.omniroute.app.ui.components.RecessedWell
import com.omniroute.app.ui.theme.ClayTheme
import java.util.Locale

/**
 * Native dashboard replacing the WebView tab. Renders [HealthSnapshot] directly
 * via Compose instead of a Chromium renderer, cutting ~50-100 MB RAM per tab
 * that was previously consumed by WebView + V8 + Blink. The URL pill at the
 * top remains so users can copy the endpoint or open it in their system
 * browser — no in-app web rendering.
 *
 * Data source: [state.health], refreshed by
 * [com.omniroute.app.data.GatewayRepository] polling. No second fetch loop.
 */
@Composable
fun DashboardScreen(
    state: GatewayUiState,
    onRefreshUrl: (String) -> Unit,
    onOpenExternally: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClayTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snapshot = state.health
    val live = state.running && snapshot != null

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        UrlTray(
            url = state.app.webviewUrl,
            online = live,
            onCopy = {
                clipboard.setText(AnnotatedString(state.app.webviewUrl))
                Toast.makeText(context, R.string.webview_copied_toast, Toast.LENGTH_SHORT).show()
            },
            onOpenExternally = onOpenExternally,
            colors = colors,
        )

        if (!live) {
            RecessedWell(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.status_offline),
                        color = colors.onSurface.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    ClayButton(onClick = onOpenExternally) {
                        Text(text = stringResource(R.string.webview_open_ext))
                    }
                }
            }
        } else {
            val snap = snapshot!!
            HeroDashboard(snap, state)
            TelemetryDashboard(snap)
            ProvidersDashboard(snap)
            CombosDashboard(snap, state)
            LogsDashboard(snap)
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun UrlTray(
    url: String,
    online: Boolean,
    onCopy: () -> Unit,
    onOpenExternally: () -> Unit,
    colors: com.omniroute.app.ui.theme.ClayColors,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RecessedWell(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.webview_copy_desc),
                    tint = colors.onSurface,
                )
            }
            IconButton(onClick = onOpenExternally) {
                Icon(
                    imageVector = Icons.Filled.OpenInNew,
                    contentDescription = stringResource(R.string.webview_open_desc),
                    tint = colors.onSurface,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (online) colors.primary.copy(alpha = 0.16f)
                        else colors.onSurface.copy(alpha = 0.12f),
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    text = if (online) stringResource(R.string.status_online) else stringResource(R.string.status_offline),
                    color = if (online) colors.primary else colors.onSurface.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "Dasbor Native — hemat RAM",
                color = colors.onSurface.copy(alpha = 0.4f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun HeroDashboard(snap: HealthSnapshot, state: GatewayUiState) {
    val colors = ClayTheme.colors
    ClayCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Dasbor Gateway",
                color = colors.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.primary.copy(alpha = 0.16f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "v${snap.version.ifBlank { "—" }}",
                    color = colors.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WellBox("UPTIME", formatUptime(snap.uptimeSeconds), Modifier.weight(1f))
            WellBox("STATUS", snap.status.ifBlank { "—" }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TelemetryDashboard(snap: HealthSnapshot) {
    val colors = ClayTheme.colors
    val total = snap.tokensIn + snap.tokensOut
    val slowest = snap.providers.maxOfOrNull { it.lastLatencyMs } ?: 0L
    val slowestId = snap.providers.maxByOrNull { it.lastLatencyMs }?.id.orEmpty()
    val healthPct = if (snap.providers.isNotEmpty()) {
        snap.providers.count { it.healthy } * 100 / snap.providers.size
    } else 0

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricBox(
                label = "PERMINTAAN",
                value = snap.requestsTotal.toString(),
                footer = "+ gagal ${snap.requestsFailed}",
                modifier = Modifier.weight(1f),
                colors = colors,
            )
            MetricBox(
                label = "TOTAL TOKEN",
                value = formatTokens(total),
                footer = "${snap.tokensIn} masuk · ${snap.tokensOut} keluar",
                modifier = Modifier.weight(1f),
                colors = colors,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricBox(
                label = "LATENSI MAKS",
                value = if (slowest > 0) "$slowest ms" else "—",
                footer = slowestId.ifBlank { "—" },
                modifier = Modifier.weight(1f),
                colors = colors,
            )
            MetricBox(
                label = "KESIHATAN",
                value = "$healthPct%",
                footer = "${snap.providers.size} provider",
                modifier = Modifier.weight(1f),
                colors = colors,
            )
        }
    }
}

@Composable
private fun ProvidersDashboard(snap: HealthSnapshot) {
    val colors = ClayTheme.colors
    ClayCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Provider",
            color = colors.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
        Spacer(Modifier.height(8.dp))
        if (snap.providers.isEmpty()) {
            Text(
                text = "Belum ada provider terkonfigurasi.",
                color = colors.onSurface.copy(alpha = 0.6f),
                fontSize = 12.sp,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                snap.providers.forEach { p ->
                    RecessedWell(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = p.id.ifBlank { "—" },
                                color = colors.onSurface,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (p.healthy) colors.primary.copy(alpha = 0.16f)
                                        else colors.onSurface.copy(alpha = 0.12f),
                                    )
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = if (p.healthy) "SEHAT" else "GAGAL",
                                    color = if (p.healthy) colors.primary else colors.onSurface.copy(alpha = 0.7f),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Latensi ${p.lastLatencyMs} ms · ${p.requests} permintaan",
                            color = colors.onSurface.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        p.lastError?.let { err ->
                            Text(
                                text = "Gagal — $err",
                                color = colors.onSurface.copy(alpha = 0.6f),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CombosDashboard(snap: HealthSnapshot, state: GatewayUiState) {
    val colors = ClayTheme.colors
    ClayCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Kombinasi",
            color = colors.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
        Spacer(Modifier.height(8.dp))
        if (snap.combos.isEmpty()) {
            Text(
                text = "Belum ada kombinasi.",
                color = colors.onSurface.copy(alpha = 0.6f),
                fontSize = 12.sp,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                snap.combos.forEach { name ->
                    RecessedWell(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = name,
                            color = colors.onSurface,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogsDashboard(snap: HealthSnapshot) {
    val colors = ClayTheme.colors
    ClayCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Log Terakhir",
            color = colors.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
        Spacer(Modifier.height(8.dp))
        RecessedWell(modifier = Modifier.fillMaxWidth()) {
            val lines = snap.logs.takeLast(10)
            if (lines.isEmpty()) {
                Text(
                    "—",
                    color = colors.onSurface.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    lines.forEach { log ->
                        Text(
                            text = "${log.ts} [${log.level}] ${log.message}",
                            color = colors.onSurface,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WellBox(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = ClayTheme.colors
    RecessedWell(modifier = modifier) {
        Column {
            Text(
                text = label,
                color = colors.onSurface.copy(alpha = 0.6f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                color = colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun MetricBox(
    label: String,
    value: String,
    footer: String,
    modifier: Modifier,
    colors: com.omniroute.app.ui.theme.ClayColors,
) {
    ClayCard(modifier = modifier) {
        Text(
            text = label,
            color = colors.onSurface.copy(alpha = 0.6f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            color = colors.primary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = footer,
            color = colors.onSurface.copy(alpha = 0.6f),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatUptime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}

private fun formatTokens(total: Long): String = when {
    total < 1_000 -> "$total tok"
    total < 1_000_000 -> String.format(Locale.US, "%.1fK tok", total / 1_000.0)
    else -> String.format(Locale.US, "%.1fM tok", total / 1_000_000.0)
}
