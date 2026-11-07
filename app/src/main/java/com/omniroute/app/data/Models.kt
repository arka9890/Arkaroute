package com.omniroute.app.data

import com.omniroute.app.config.AppMeta
import com.omniroute.app.config.GatewayConfigJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/** Per-provider view inside a [HealthSnapshot]; mirrors core `ProviderSnapshot`. */
@Serializable
data class ProviderStat(
    val id: String = "",
    val healthy: Boolean = true,
    val requests: Long = 0,
    @SerialName("tokens_in") val tokensIn: Long = 0,
    @SerialName("tokens_out") val tokensOut: Long = 0,
    @SerialName("last_latency_ms") val lastLatencyMs: Long = 0,
    @SerialName("last_error") val lastError: String? = null,
)

/** One entry of the in-memory log buffer; mirrors core `LogEntry`. */
@Serializable
data class LogLine(
    val ts: String = "",
    val level: String = "",
    val message: String = "",
)

/**
 * Point-in-time view of gateway health, parsed from the `GET /health` body.
 * Field names match core `StatsSnapshot` (snake_case on the wire).
 */
@Serializable
data class HealthSnapshot(
    val status: String = "",
    val version: String = "",
    @SerialName("uptime_seconds") val uptimeSeconds: Long = 0,
    @SerialName("lan_enabled") val lanEnabled: Boolean = false,
    @SerialName("requests_total") val requestsTotal: Long = 0,
    @SerialName("requests_failed") val requestsFailed: Long = 0,
    @SerialName("tokens_in") val tokensIn: Long = 0,
    @SerialName("tokens_out") val tokensOut: Long = 0,
    val providers: List<ProviderStat> = emptyList(),
    val combos: List<String> = emptyList(),
    val logs: List<LogLine> = emptyList(),
)

/** UI-facing gateway state, refreshed by [GatewayRepository] polls. */
data class GatewayUiState(
    val running: Boolean,
    val health: HealthSnapshot?,
    val requestsPerMinute: Long,
    val core: GatewayConfigJson,
    val app: AppMeta,
    val lastError: String?,
)
