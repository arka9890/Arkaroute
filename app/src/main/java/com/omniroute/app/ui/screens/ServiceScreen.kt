package com.omniroute.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniroute.app.R
import com.omniroute.app.data.GatewayUiState
import com.omniroute.app.ui.components.ClayButton
import com.omniroute.app.ui.components.ClayCard
import com.omniroute.app.ui.components.ClayToggle
import com.omniroute.app.ui.components.RecessedWell
import com.omniroute.app.ui.theme.ClayTheme
import java.util.Locale

/** Em dash shown when no live value is available (stopped or no data). */
private const val NO_VALUE = "—"

/**
 * Service screen for the gateway daemon: hero card with status + daemon
 * toggle, 2×2 telemetry bento, routing pipeline badges, live SSE log well,
 * and the restart / clear-cache actions.
 *
 * Pure function of [GatewayUiState] + callbacks, so JVM tests need no
 * Android services. Metric mapping is pinned — every value shown is derived
 * from the health snapshot; nothing is invented.
 */
@Composable
fun ServiceScreen(
    state: GatewayUiState,
    onDaemon: (Boolean) -> Unit,
    onRestart: () -> Unit,
    onClearCache: () -> Unit,
    onCopyLog: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClayTheme.colors
    val snapshot = state.health
    val live = state.running && snapshot != null

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        HeroCard(
            running = state.running,
            version = snapshot?.version.orEmpty(),
            toggleChecked = state.running,
            hostText = hostText(state),
            uptimeText = if (live) formatUptime(snapshot!!.uptimeSeconds) else NO_VALUE,
            onDaemon = onDaemon,
        )
        TelemetryBento(
            live = live,
            requestsPerMinute = state.requestsPerMinute,
            requestsTotal = snapshot?.requestsTotal ?: 0,
            tokensIn = snapshot?.tokensIn ?: 0,
            tokensOut = snapshot?.tokensOut ?: 0,
            slowestLatencyMs = snapshot?.providers?.maxOfOrNull { it.lastLatencyMs } ?: 0,
            slowestProviderId = snapshot?.providers?.maxByOrNull { it.lastLatencyMs }?.id.orEmpty(),
            healthyCount = snapshot?.providers?.count { it.healthy } ?: 0,
            providerCount = snapshot?.providers?.size ?: 0,
            requestsFailed = snapshot?.requestsFailed ?: 0,
        )
        PipelineCard(state = state)
        LogsCard(
            logText = snapshot?.logs.orEmpty().joinToString("\n") { "${it.ts} [${it.level}] ${it.message}" },
            onCopyLog = onCopyLog,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ClayButton(onClick = onRestart, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.restart_action))
            }
            ClayButton(onClick = onClearCache, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.clear_cache_action))
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun HeroCard(
    running: Boolean,
    version: String,
    toggleChecked: Boolean,
    hostText: String,
    uptimeText: String,
    onDaemon: (Boolean) -> Unit,
) {
    val colors = ClayTheme.colors
    val daemonDesc = stringResource(R.string.daemon_desc)
    ClayCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(running = running)
            if (version.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "v$version",
                    color = colors.onSurface.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.service_title),
            color = colors.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.service_subtitle),
            color = colors.onSurface.copy(alpha = 0.7f),
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.daemon_label),
                color = colors.onSurface,
                fontSize = 14.sp,
            )
            Spacer(Modifier.weight(1f))
            ClayToggle(
                checked = toggleChecked,
                onCheckedChange = onDaemon,
                modifier = Modifier.semantics { contentDescription = daemonDesc },
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Well(
                label = stringResource(R.string.host_label),
                value = hostText,
                modifier = Modifier.weight(1f),
            )
            Well(
                label = stringResource(R.string.uptime_label),
                value = uptimeText,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatusPill(running: Boolean) {
    val colors = ClayTheme.colors
    val background = if (running) colors.primary else colors.onSurface.copy(alpha = 0.25f)
    val textColor = if (running) Color(0xFF06222B) else colors.onSurface
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            text = stringResource(if (running) R.string.status_running else R.string.status_stopped),
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun Well(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = ClayTheme.colors
    RecessedWell(modifier = modifier) {
        Column {
            Text(
                text = label,
                color = colors.onSurface.copy(alpha = 0.6f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                color = colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TelemetryBento(
    live: Boolean,
    requestsPerMinute: Long,
    requestsTotal: Long,
    tokensIn: Long,
    tokensOut: Long,
    slowestLatencyMs: Long,
    slowestProviderId: String,
    healthyCount: Int,
    providerCount: Int,
    requestsFailed: Long,
) {
    val requestsValue = if (live) stringResource(R.string.requests_per_minute_format, requestsPerMinute) else NO_VALUE
    val requestsFooter = if (live) stringResource(R.string.requests_total_footer, requestsTotal) else NO_VALUE
    val tokensValue = if (live) formatTokens(tokensIn + tokensOut) else NO_VALUE
    val tokensFooter = if (live) stringResource(R.string.tokens_footer, tokensIn, tokensOut) else NO_VALUE
    val latencyValue = if (live && slowestLatencyMs > 0) slowestLatencyMs.toString() else NO_VALUE
    val latencyFooter = if (latencyValue != NO_VALUE) slowestProviderId else NO_VALUE
    val healthValue = if (live && providerCount > 0) "${healthyCount * 100 / providerCount}%" else NO_VALUE
    val healthFooter = if (live) stringResource(R.string.requests_failed_footer, requestsFailed) else NO_VALUE

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricCard(
            label = stringResource(R.string.metric_requests),
            value = requestsValue,
            footer = requestsFooter,
            modifier = Modifier.weight(1f),
        )
        MetricCard(
            label = stringResource(R.string.metric_tokens),
            value = tokensValue,
            footer = tokensFooter,
            modifier = Modifier.weight(1f),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricCard(
            label = stringResource(R.string.metric_latency),
            value = latencyValue,
            footer = latencyFooter,
            modifier = Modifier.weight(1f),
        )
        MetricCard(
            label = stringResource(R.string.metric_health),
            value = healthValue,
            footer = healthFooter,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MetricCard(label: String, value: String, footer: String, modifier: Modifier = Modifier) {
    val colors = ClayTheme.colors
    ClayCard(modifier = modifier) {
        Text(
            text = label,
            color = colors.onSurface.copy(alpha = 0.6f),
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            color = colors.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = footer,
            color = colors.onSurface.copy(alpha = 0.6f),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PipelineCard(state: GatewayUiState) {
    val colors = ClayTheme.colors
    val combo = state.core.defaultCombo?.let { name ->
        state.core.combos.firstOrNull { it.name == name }
    } ?: state.core.combos.firstOrNull()
    val steps = combo?.steps?.take(4).orEmpty()
    val healthByProvider = state.health?.providers.orEmpty().associateBy { it.id }

    ClayCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.pipeline_title),
                color = colors.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.primary.copy(alpha = 0.16f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.pipeline_pill),
                    color = colors.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            steps.forEachIndexed { index, step ->
                val healthy = healthByProvider[step.provider]?.healthy
                val healthLine = when (healthy) {
                    true -> stringResource(R.string.pipeline_health_ok)
                    false -> stringResource(R.string.pipeline_health_bad)
                    null -> NO_VALUE
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "$index",
                        color = colors.primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(20.dp),
                    )
                    RecessedWell(modifier = Modifier.weight(1f)) {
                        Column {
                            Text(
                                text = "${step.provider} • ${step.model}",
                                color = colors.onSurface,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = healthLine,
                                color = if (healthy == true) colors.primary else colors.onSurface.copy(alpha = 0.6f),
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogsCard(logText: String, onCopyLog: (String) -> Unit) {
    val colors = ClayTheme.colors
    ClayCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.logs_title),
                color = colors.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f),
            )
            ClayButton(onClick = { onCopyLog(logText) }) {
                Text(text = stringResource(R.string.logs_copy))
            }
        }
        Spacer(Modifier.height(10.dp))
        RecessedWell(modifier = Modifier.fillMaxWidth()) {
            if (logText.isEmpty()) {
                Text(
                    text = NO_VALUE,
                    color = colors.onSurface.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                Text(
                    text = logText,
                    color = colors.onSurface,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/** Local host from [GatewayUiState.core]; `0.0.0.0` maps to `127.0.0.1`. */
private fun hostText(state: GatewayUiState): String {
    val host = if (state.core.bind == "0.0.0.0") "127.0.0.1" else state.core.bind
    return "$host:${state.core.port}/v1"
}

private fun formatUptime(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
}

private fun formatTokens(total: Long): String = when {
    total < 1_000 -> "$total tok"
    total < 1_000_000 -> String.format(Locale.US, "%.1fK tok", total / 1_000.0)
    else -> String.format(Locale.US, "%.1fM tok", total / 1_000_000.0)
}
