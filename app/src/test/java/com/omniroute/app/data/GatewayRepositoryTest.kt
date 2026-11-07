package com.omniroute.app.data

import com.omniroute.app.config.AppMeta
import com.omniroute.app.config.ConfigStore
import com.omniroute.app.config.CryptoEngine
import com.omniroute.app.config.GatewayConfigJson
import com.omniroute.app.service.GatewayService
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

private class IdentityCryptoEngine : CryptoEngine {
    override fun encrypt(plain: ByteArray): ByteArray = plain
    override fun decrypt(blob: ByteArray): ByteArray = blob
}

private class FakeBridge : GatewayBridge {
    val calls = mutableListOf<String>()
    override fun startServer(configJson: String): String {
        calls += "startServer"
        return "{\"ok\":true}"
    }

    override fun stopServer(): String {
        calls += "stopServer"
        return "{\"ok\":true}"
    }

    override fun isRunning(): Boolean {
        calls += "isRunning"
        return false
    }
}

private class FakeHealthSource : HealthSource {
    var body: String? = null
    var fetchCount = 0
    var lastBaseUrl: String? = null
    override fun fetch(baseUrl: String): String? {
        fetchCount++
        lastBaseUrl = baseUrl
        return body
    }
}

private class FakeServiceController : ServiceController {
    val actions = mutableListOf<String>()
    override fun startService() {
        actions += GatewayService.ACTION_START
    }

    override fun stopService() {
        actions += GatewayService.ACTION_STOP
    }
}

class GatewayRepositoryTest {
    private lateinit var storeDir: File
    private lateinit var bridge: FakeBridge
    private lateinit var healthSource: FakeHealthSource
    private lateinit var serviceController: FakeServiceController
    private lateinit var repository: GatewayRepository

    @Before
    fun setUp() {
        storeDir = Files.createTempDirectory("gateway-repository-test").toFile()
        bridge = FakeBridge()
        healthSource = FakeHealthSource()
        serviceController = FakeServiceController()
        repository = GatewayRepository(bridge, healthSource, store(), serviceController)
    }

    private fun store(): ConfigStore = ConfigStore(storeDir, IdentityCryptoEngine())

    private fun healthJson(requestsTotal: Long = 42): String = """
        {
          "status": "ok",
          "version": "0.1.0",
          "uptime_seconds": 105,
          "lan_enabled": false,
          "requests_total": $requestsTotal,
          "requests_failed": 1,
          "tokens_in": 1000,
          "tokens_out": 2000,
          "providers": [
            {"id": "p1", "healthy": true, "requests": 10, "tokens_in": 100, "tokens_out": 50, "last_latency_ms": 120, "last_error": null},
            {"id": "p2", "healthy": false, "requests": 3, "tokens_in": 0, "tokens_out": 0, "last_latency_ms": 0, "last_error": "timeout"}
          ],
          "combos": ["combo-a", "combo-b"],
          "logs": [{"ts": "2026-11-06T00:00:00Z", "level": "info", "message": "gateway started"}]
        }
    """.trimIndent()

    @Test
    fun parses_health_snapshot() {
        healthSource.body = healthJson(42)
        repository.pollOnce()
        val state = repository.state.value
        assertTrue(state.running)
        assertNotNull(state.health)
        assertEquals(105L, state.health?.uptimeSeconds)
        assertEquals(2, state.health?.providers?.size)
        assertEquals(1, state.health?.logs?.size)
        assertEquals("p1", state.health?.providers?.first()?.id)
        assertEquals(1000L, state.health?.tokensIn)
        assertEquals("gateway started", state.health?.logs?.first()?.message)
    }

    @Test
    fun health_unavailable_marks_gateway_stopped() {
        healthSource.body = null
        repository.pollOnce()
        val state = repository.state.value
        assertFalse(state.running)
        assertNull(state.health)
    }

    @Test
    fun save_config_restarts_service_stop_then_start() {
        val core = GatewayConfigJson(port = 3000)
        val app = AppMeta(wakeLock = false)
        val message = repository.saveConfigAndRestart(core, app)
        assertEquals(
            listOf(GatewayService.ACTION_STOP, GatewayService.ACTION_START),
            serviceController.actions,
        )
        val (loadedCore, loadedApp) = store().load()
        assertEquals(3000, loadedCore.port)
        assertFalse(loadedApp.wakeLock)
        assertEquals(3000, repository.state.value.core.port)
        assertEquals("Gateway dimulai ulang", message)
    }

    @Test
    fun computes_requests_per_minute() = runTest {
        val repo = GatewayRepository(
            bridge,
            healthSource,
            store(),
            serviceController,
            nowMs = { testScheduler.currentTime },
        )
        healthSource.body = healthJson(100)
        repo.pollOnce()
        assertEquals(0L, repo.state.value.requestsPerMinute)
        advanceTimeBy(60_000)
        healthSource.body = healthJson(130)
        repo.pollOnce()
        assertEquals(30L, repo.state.value.requestsPerMinute)
    }

    @Test
    fun polls_every_two_seconds() = runTest {
        healthSource.body = healthJson(7)
        repository.startPolling(backgroundScope)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(healthSource.fetchCount in 2..3)
    }

    @Test
    fun zero_bind_polls_localhost() {
        store().save(GatewayConfigJson(bind = "0.0.0.0", port = 20128), AppMeta())
        val repo = GatewayRepository(bridge, healthSource, store(), serviceController)
        assertEquals("http://127.0.0.1:20128", repo.baseUrl())
    }
}
