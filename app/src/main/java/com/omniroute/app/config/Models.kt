package com.omniroute.app.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

val ConfigJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

@Serializable
data class ClientKey(
    val label: String = "",
    val secret: String = "",
)

@Serializable
data class AppMeta(
    var bootStart: Boolean = true,
    var wakeLock: Boolean = true,
    var webviewUrl: String = "http://127.0.0.1:20128/dashboard",
    var clientKeys: List<ClientKey> = emptyList(),
    var themeMode: String = "oled",
    var clayDepth: Int = 85,
    var highContrast: Boolean = false,
    var providerLimits: Map<String, Long> = emptyMap(),
)

@Serializable
data class ProviderConfigJson(
    val id: String = "",
    val kind: String = "OpenAiCompatible",
    @SerialName("base_url") val baseUrl: String = "",
    @SerialName("api_key") val apiKey: String = "",
    val models: List<String> = emptyList(),
)

@Serializable
data class ComboStepJson(
    val provider: String = "",
    val model: String = "",
)

@Serializable
data class ComboJson(
    val name: String = "",
    val steps: List<ComboStepJson> = emptyList(),
)

@Serializable
data class GatewayConfigJson(
    val bind: String = "127.0.0.1",
    val port: Int = 20128,
    @SerialName("lan_enabled") val lanEnabled: Boolean = false,
    @SerialName("gateway_keys") val gatewayKeys: List<String> = emptyList(),
    @SerialName("default_combo") val defaultCombo: String? = null,
    val providers: List<ProviderConfigJson> = emptyList(),
    val combos: List<ComboJson> = emptyList(),
    @SerialName("request_timeout_ms") val requestTimeoutMs: Long = 600_000L,
    @SerialName("stream_idle_timeout_ms") val streamIdleTimeoutMs: Long = 60_000L,
)

@Serializable
internal data class ConfigWrapper(
    val core: GatewayConfigJson,
    val app: AppMeta,
)
