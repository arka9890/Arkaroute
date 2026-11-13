package com.omniroute.app.data

import android.content.Context
import android.content.Intent
import com.omniroute.app.bridge.NativeGateway
import com.omniroute.app.config.AppMeta
import com.omniroute.app.config.ConfigJson
import com.omniroute.app.config.ConfigStore
import com.omniroute.app.config.GatewayConfigJson
import com.omniroute.app.service.GatewayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString

/** Gateway lifecycle control. Tests use a fake; the app uses [JniGatewayBridge]. */
interface GatewayBridge {
    fun startServer(configJson: String): String
    fun stopServer(): String
    fun isRunning(): Boolean
}

/** Delegates to [NativeGateway] — the ONLY place touching it. */
object JniGatewayBridge : GatewayBridge {
    override fun startServer(configJson: String): String = NativeGateway.startServer(configJson)
    override fun stopServer(): String = NativeGateway.stopServer()
    override fun isRunning(): Boolean = NativeGateway.isRunning()
}

/** Daemon dispatch so tests avoid Android services. */
interface ServiceController {
    fun startService()
    fun stopService()
}

/** Production [ServiceController] dispatching ACTION_START/STOP to [GatewayService]. */
class AndroidServiceController(private val context: Context) : ServiceController {
    override fun startService() {
        val intent = Intent(context, GatewayService::class.java).setAction(GatewayService.ACTION_START)
        context.startForegroundService(intent)
    }

    override fun stopService() {
        val intent = Intent(context, GatewayService::class.java).setAction(GatewayService.ACTION_STOP)
        context.startService(intent)
    }
}

class GatewayRepository(
    private val bridge: GatewayBridge,
    private val healthSource: HealthSource,
    private val store: ConfigStore,
    private val serviceController: ServiceController,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val restartScheduler: RestartScheduler = DefaultRestartScheduler(),
) {
    private val _state: MutableStateFlow<GatewayUiState>

    init {
        val (initialCore, initialApp) = store.load()
        _state = MutableStateFlow(
            GatewayUiState(
                running = false,
                health = null,
                requestsPerMinute = 0,
                core = initialCore,
                app = initialApp,
                lastError = null,
            )
        )
    }
    val state: StateFlow<GatewayUiState> = _state.asStateFlow()

    /** Last (requestsTotal, timestampMs) sample used for the per-minute rate. */
    private var lastSample: Pair<Long, Long>? = null

    fun baseUrl(): String {
        val core = _state.value.core
        val host = if (core.bind == "0.0.0.0") "127.0.0.1" else core.bind
        return "http://$host:${core.port}"
    }

    /**
     * Starts the 2s polling loop in [scope]. The fetch is blocking, so pass a
     * scope dispatching off the main thread (e.g. a Dispatchers.IO scope).
     */
    fun startPolling(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                pollOnce()
                delay(2_000)
            }
        }
    }

    /** One health fetch + state update. Never throws. */
    internal fun pollOnce() {
        val body = try {
            healthSource.fetch(baseUrl())
        } catch (e: Exception) {
            null
        }
        if (body == null) {
            lastSample = null
            _state.update { it.copy(running = false, health = null, requestsPerMinute = 0) }
            return
        }
        val snapshot = try {
            ConfigJson.decodeFromString<HealthSnapshot>(body)
        } catch (e: Exception) {
            lastSample = null
            _state.update {
                it.copy(running = false, health = null, requestsPerMinute = 0, lastError = e.message)
            }
            return
        }
        val now = nowMs()
        val prev = lastSample
        val rpm = if (prev != null) {
            val elapsedSeconds = (now - prev.second) / 1000
            val delta = snapshot.requestsTotal - prev.first
            if (elapsedSeconds > 0 && delta >= 0) (delta * 60) / elapsedSeconds else 0L
        } else {
            0L
        }
        lastSample = snapshot.requestsTotal to now
        _state.update {
            it.copy(running = true, health = snapshot, requestsPerMinute = rpm, lastError = null)
        }
    }

    /** Dispatches ACTION_START (on) or ACTION_STOP (off) via the [serviceController]. */
    fun requestDaemon(on: Boolean) {
        if (on) serviceController.startService() else serviceController.stopService()
    }

    /**
     * Persists the new config, then restarts the daemon (STOP, gap, START).
     * Returns the toast message on success, or the error message on failure.
     *
     * The start is dispatched via [restartScheduler] so the calling thread
     * (Main, in Compose) is not blocked for the restart gap. Production
     * [GatewayRepository] uses [DefaultRestartScheduler] (background thread
     * with 300ms sleep); unit tests inject a synchronous fake so the
     * stop→start ordering is deterministic and observable from the test.
     */
    fun saveConfigAndRestart(core: GatewayConfigJson, app: AppMeta): String? {
        try {
            store.save(core, app)
        } catch (e: Exception) {
            _state.update { it.copy(lastError = e.message) }
            return e.message
        }
        _state.update { it.copy(core = core, app = app, lastError = null) }
        serviceController.stopService()
        restartScheduler.scheduleStart(serviceController::startService)
        return "Gateway dimulai ulang"
    }

    /** Persists AppMeta only — no server restart (core ignores AppMeta). */
    fun saveAppMeta(app: AppMeta) {
        store.save(_state.value.core, app)
        _state.update { it.copy(app = app) }
    }
}

/**
 * Schedules the gateway start after a restart gap. Production implementation
 * offloads to a background thread so the calling (Main) thread is not
 * blocked; tests inject a synchronous implementation to observe the
 * stop→start ordering deterministically.
 */
interface RestartScheduler {
    fun scheduleStart(start: () -> Unit)
}

/**
 * Background-thread scheduler with a 300ms gap. The start is fire-and-forget:
 * if the service fails to start, [GatewayService] surfaces it via its own
 * notification.
 */
internal class DefaultRestartScheduler : RestartScheduler {
    override fun scheduleStart(start: () -> Unit) {
        kotlin.concurrent.thread(name = "omniroute-restart-gap") {
            Thread.sleep(300)
            start()
        }
    }
}

