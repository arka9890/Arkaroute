package com.omniroute.app.config

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

class ConfigStore(private val filesDir: File, private val crypto: CryptoEngine) {
    private val configFile = File(filesDir, "config.enc")
    private val corruptFile = File(filesDir, "config.corrupt")
    private var cachedCore: GatewayConfigJson? = null

    fun load(): Pair<GatewayConfigJson, AppMeta> {
        val raw: ByteArray
        try {
            if (!configFile.exists()) {
                val defaults = GatewayConfigJson() to AppMeta()
                cachedCore = defaults.first
                return defaults
            }
            raw = configFile.readBytes()
        } catch (e: Exception) {
            val defaults = GatewayConfigJson() to AppMeta()
            cachedCore = defaults.first
            return defaults
        }
        try {
            val plain = crypto.decrypt(raw)
            val wrapper = ConfigJson.decodeFromString<ConfigWrapper>(plain.decodeToString())
            cachedCore = wrapper.core
            return wrapper.core to wrapper.app
        } catch (e: Exception) {
            try {
                corruptFile.writeBytes(raw)
            } catch (ignored: Exception) {
            }
            val defaults = GatewayConfigJson() to AppMeta()
            cachedCore = defaults.first
            return defaults
        }
    }

    fun save(core: GatewayConfigJson, app: AppMeta) {
        val wrapper = ConfigWrapper(core = core, app = app)
        val json = ConfigJson.encodeToString(wrapper)
        val encrypted = crypto.encrypt(json.toByteArray())
        filesDir.mkdirs()
        configFile.writeBytes(encrypted)
        cachedCore = core
    }

    fun coreJson(): String {
        val core = cachedCore ?: load().first
        return ConfigJson.encodeToString(core)
    }
}
