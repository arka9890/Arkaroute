package com.omniroute.app.ui.screens

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.omniroute.app.R
import com.omniroute.app.config.AppMeta
import com.omniroute.app.config.GatewayConfigJson
import com.omniroute.app.data.GatewayUiState
import com.omniroute.app.data.HealthSnapshot
import com.omniroute.app.data.ProviderStat
import com.omniroute.app.ui.theme.ClayTheme
import com.omniroute.app.ui.theme.clayColorsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Pins [ServiceScreen] as a pure function of [GatewayUiState] + callbacks:
 * no Android services or JNI are needed — state and callbacks are injected.
 */
@RunWith(RobolectricTestRunner::class)
class ServiceScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun runningState(): GatewayUiState = GatewayUiState(
        running = true,
        health = HealthSnapshot(
            status = "ok",
            version = "0.1.0",
            uptimeSeconds = 14905,
            lanEnabled = false,
            requestsTotal = 42,
            requestsFailed = 2,
            tokensIn = 1000,
            tokensOut = 2000,
            providers = listOf(
                ProviderStat(id = "p1", healthy = true, lastLatencyMs = 120),
                ProviderStat(id = "p2", healthy = true, lastLatencyMs = 450),
                ProviderStat(id = "p3", healthy = true, lastLatencyMs = 200),
                ProviderStat(id = "p4", healthy = false, lastLatencyMs = 300),
            ),
            combos = listOf("combo-a"),
            logs = emptyList(),
        ),
        requestsPerMinute = 30,
        core = GatewayConfigJson(bind = "127.0.0.1", port = 20128),
        app = AppMeta(),
        lastError = null,
    )

    private fun stoppedState(): GatewayUiState =
        runningState().copy(running = false, health = null, requestsPerMinute = 0)

    private fun setScreen(
        state: GatewayUiState,
        onDaemon: (Boolean) -> Unit = {},
        onRestart: () -> Unit = {},
        onClearCache: () -> Unit = {},
        onCopyLog: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            ClayTheme(colors = clayColorsFor("oled"), depth = 85) {
                ServiceScreen(
                    state = state,
                    onDaemon = onDaemon,
                    onRestart = onRestart,
                    onClearCache = onClearCache,
                    onCopyLog = onCopyLog,
                )
            }
        }
    }

    @Test fun shows_real_uptime_and_host() {
        setScreen(runningState())
        composeRule.onNodeWithText("04:08:25").assertIsDisplayed()
        composeRule.onNodeWithText("127.0.0.1:20128/v1").assertIsDisplayed()
    }

    @Test fun daemon_toggle_dispatches_stop_when_running() {
        var captured: Boolean? = null
        setScreen(runningState(), onDaemon = { captured = it })
        composeRule.onNodeWithContentDescription(context.getString(R.string.daemon_desc)).performClick()
        assertEquals(false, captured)
    }

    @Test fun telemetry_shows_real_tokens_and_health() {
        setScreen(runningState())
        // tokensIn=1000 + tokensOut=2000 = 3000 → compact "3.0K tok"
        composeRule.onNodeWithText("3.0K tok").assertIsDisplayed()
        // 3 of 4 providers healthy → "75%"
        composeRule.onNodeWithText("75%").assertIsDisplayed()
        composeRule.onNodeWithText("2 permintaan gagal", substring = true).assertIsDisplayed()
    }

    @Test fun req_per_minute_computed_from_samples() {
        setScreen(runningState())
        composeRule.onNodeWithText("30 req/mnt").assertIsDisplayed()
    }

    @Test fun stopped_state_shows_layanan_berhenti() {
        setScreen(stoppedState())
        composeRule.onNodeWithText(context.getString(R.string.status_stopped)).assertIsDisplayed()
        val placeholders = composeRule.onAllNodesWithText("—").fetchSemanticsNodes()
        assertTrue(placeholders.isNotEmpty())
    }
}
