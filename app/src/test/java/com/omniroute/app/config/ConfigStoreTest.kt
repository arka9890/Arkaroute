package com.omniroute.app.config

import android.content.Context
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

class FakeCryptoEngine(private val xor: Byte = 0x5A) : CryptoEngine {
    override fun encrypt(plain: ByteArray): ByteArray = plain.map { (it.toInt() xor xor.toInt()).toByte() }.toByteArray()
    override fun decrypt(blob: ByteArray): ByteArray = blob.map { (it.toInt() xor xor.toInt()).toByte() }.toByteArray()
}

@RunWith(RobolectricTestRunner::class)
class ConfigStoreTest {
    private lateinit var dir: File

    @Before fun setup() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        dir = File(ctx.filesDir, "configstore-test-${System.nanoTime()}")
        dir.mkdirs()
    }

    @Test fun missing_file_loads_defaults() {
        val store = ConfigStore(dir, FakeCryptoEngine())
        val (core, app) = store.load()
        assertEquals("127.0.0.1", core.bind)
        assertEquals(20128, core.port)
        assertFalse(core.lanEnabled)
        assertTrue(core.gatewayKeys.isEmpty())
        assertTrue(core.providers.isEmpty())
        assertTrue(core.combos.isEmpty())
        assertNull(core.defaultCombo)
        assertEquals(600_000L, core.requestTimeoutMs)
        assertEquals(60_000L, core.streamIdleTimeoutMs)
        assertTrue(app.bootStart)
        assertTrue(app.wakeLock)
        assertEquals("http://127.0.0.1:20128/dashboard", app.webviewUrl)
        assertTrue(app.clientKeys.isEmpty())
        // second load is stable
        val (core2, app2) = store.load()
        assertEquals(core, core2)
        assertEquals(app, app2)
    }

    @Test fun save_then_load_roundtrips() {
        val store = ConfigStore(dir, FakeCryptoEngine())
        val core = GatewayConfigJson(
            port = 3000,
            providers = listOf(
                ProviderConfigJson(
                    id = "prov1",
                    kind = "OpenAiCompatible",
                    baseUrl = "https://api.example.com/v1",
                    apiKey = "sk-test",
                    models = listOf("gpt-4"),
                )
            ),
            combos = listOf(
                ComboJson(name = "combo1", steps = listOf(ComboStepJson(provider = "prov1", model = "gpt-4")))
            ),
        )
        val app = AppMeta(
            bootStart = false,
            wakeLock = true,
            webviewUrl = "http://127.0.0.1:20128/dashboard",
            clientKeys = listOf(ClientKey(label = "phone", secret = "secret123")),
        )
        store.save(core, app)
        val (loadedCore, loadedApp) = store.load()
        assertEquals(core, loadedCore)
        assertEquals(app, loadedApp)
    }

    @Test fun corrupt_config_backed_up_and_reset_to_defaults() {
        val store = ConfigStore(dir, FakeCryptoEngine())
        val garbage = "not-valid-json{".toByteArray()
        File(dir, "config.enc").writeBytes(garbage)
        val (core, app) = store.load()
        assertEquals("127.0.0.1", core.bind)
        assertEquals(20128, core.port)
        assertFalse(core.lanEnabled)
        assertTrue(core.providers.isEmpty())
        assertTrue(app.bootStart)
        assertTrue(app.clientKeys.isEmpty())
        val corrupt = File(dir, "config.corrupt")
        assertTrue(corrupt.exists())
        assertArrayEquals(garbage, corrupt.readBytes())
    }

    @Test fun coreJson_matches_wire_names() {
        val store = ConfigStore(dir, FakeCryptoEngine())
        store.save(GatewayConfigJson(), AppMeta())
        val json = store.coreJson()
        assertTrue(json.contains("lan_enabled"))
        assertTrue(json.contains("request_timeout_ms"))
        assertFalse(json.contains("lanEnabled"))
    }
}
