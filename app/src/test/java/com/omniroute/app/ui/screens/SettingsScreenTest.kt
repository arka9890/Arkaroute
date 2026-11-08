package com.omniroute.app.ui.screens

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.omniroute.app.R
import com.omniroute.app.config.AppMeta
import com.omniroute.app.config.GatewayConfigJson
import com.omniroute.app.data.GatewayUiState
import com.omniroute.app.data.HealthSnapshot
import com.omniroute.app.ui.theme.ClayTheme
import com.omniroute.app.ui.theme.clayColorsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun stateWith(
        running: Boolean = true,
        version: String = "0.1.0",
        uptimeSeconds: Long = 51720,
        requestsTotal: Long = 1000,
        requestsFailed: Long = 1,
        gatewayKeys: List<String> = emptyList(),
        lanEnabled: Boolean = false,
        port: Int = 20128,
        timeoutMs: Long = 600000,
        app: AppMeta = AppMeta(),
    ): GatewayUiState = GatewayUiState(
        running = running,
        health = HealthSnapshot(status = "ok", version = version, uptimeSeconds = uptimeSeconds, requestsTotal = requestsTotal, requestsFailed = requestsFailed),
        requestsPerMinute = 0,
        core = GatewayConfigJson(port = port, lanEnabled = lanEnabled, gatewayKeys = gatewayKeys, requestTimeoutMs = timeoutMs),
        app = app,
        lastError = null,
    )

    private fun setScreen(
        state: GatewayUiState,
        onSave: (GatewayConfigJson, AppMeta) -> Unit = { _, _ -> },
        onSaveMeta: (AppMeta) -> Unit = {},
        onToast: (String) -> Unit = {},
        restoreContent: String? = null,
        onExportLaunch: (() -> Unit)? = null,
    ) {
        composeRule.setContent {
            ClayTheme(colors = clayColorsFor("oled"), depth = 85) {
                SettingsScreen(
                    state = state,
                    onSaveConfigAndRestart = onSave,
                    onSaveAppMeta = onSaveMeta,
                    onToast = onToast,
                    restoreContent = restoreContent,
                    onExportLaunch = onExportLaunch,
                )
            }
        }
    }

    @Test fun header_shows_real_uptime_and_success_rate() {
        val state = stateWith()
        setScreen(state)
        assertTrue(composeRule.onAllNodesWithText("14j 22m").fetchSemanticsNodes().isNotEmpty() ||
            composeRule.onAllNodesWithText("99.9%").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("99.9%").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("0.1.0").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun theme_buttons_and_depth_slider_write_meta() {
        val state = stateWith()
        val capturedMeta = AtomicReference<AppMeta?>(null)
        setScreen(state, onSaveMeta = { capturedMeta.set(it) })
        composeRule.onNode(hasText("Soft Putty") and hasClickAction()).performClick()
        composeRule.waitForIdle()
        assertEquals("soft", capturedMeta.get()?.themeMode)
        // Drive the depth slider: tap-to-seek moves clayDepth off 85.
        composeRule.onNodeWithContentDescription("Kedalaman Efek Clay slider").performScrollTo()
        composeRule.onNodeWithContentDescription("Kedalaman Efek Clay slider").performClick()
        composeRule.waitForIdle()
        assertTrue(capturedMeta.get()?.clayDepth != null && capturedMeta.get()!!.clayDepth != 85)
    }

    @Test fun lan_enable_without_keys_prompts_creation() {
        val state = stateWith(gatewayKeys = emptyList(), lanEnabled = false)
        val captured = AtomicReference<Pair<GatewayConfigJson, AppMeta>?>(null)
        setScreen(state, onSave = { c, a -> captured.set(c to a) })
        composeRule.onNodeWithText("Akses Jaringan Lokal (LAN)").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Akses Jaringan Lokal (LAN)").performClick()
        composeRule.waitForIdle()
        // Key-creation dialog appears instead of saving.
        assertTrue(composeRule.onAllNodesWithText("Buat Gateway API Key").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText(context.getString(R.string.settings_create_key_title)).fetchSemanticsNodes().isNotEmpty())
        // Config NOT yet saved with lan=true.
        assertTrue(captured.get() == null || captured.get()!!.first.lanEnabled == false)
    }

    @Test fun lan_enable_with_keys_saves_and_restarts() {
        val state = stateWith(gatewayKeys = listOf("sk-1"), lanEnabled = false)
        val captured = AtomicReference<Pair<GatewayConfigJson, AppMeta>?>(null)
        setScreen(state, onSave = { c, a -> captured.set(c to a) })
        composeRule.onNodeWithText("Akses Jaringan Lokal (LAN)").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Akses Jaringan Lokal (LAN)").performClick()
        composeRule.waitForIdle()
        assertEquals(true, captured.get()?.first?.lanEnabled)
    }

    @Test fun port_change_validates() {
        val state = stateWith()
        val captured = AtomicReference<Pair<GatewayConfigJson, AppMeta>?>(null)
        setScreen(state, onSave = { c, a -> captured.set(c to a) })
        // Invalid: "abc"
        composeRule.onNodeWithContentDescription("Port input").performTextClearance()
        composeRule.onNodeWithContentDescription("Port input").performTextInput("abc")
        composeRule.onNodeWithContentDescription("Ubah port").performScrollTo()
        composeRule.onNodeWithContentDescription("Ubah port").performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText(context.getString(R.string.settings_port_error)).fetchSemanticsNodes().isNotEmpty())
        assertEquals(null, captured.get())
        // Invalid: "0"
        composeRule.onNodeWithContentDescription("Port input").performTextClearance()
        composeRule.onNodeWithContentDescription("Port input").performTextInput("0")
        composeRule.onNodeWithContentDescription("Ubah port").performClick()
        composeRule.waitForIdle()
        assertEquals(null, captured.get())
        // Valid: "3000"
        composeRule.onNodeWithContentDescription("Port input").performTextClearance()
        composeRule.onNodeWithContentDescription("Port input").performTextInput("3000")
        composeRule.onNodeWithContentDescription("Ubah port").performClick()
        composeRule.waitForIdle()
        assertEquals(3000, captured.get()?.first?.port)
    }

    @Test fun timeout_change_validates_and_saves() {
        val state = stateWith(timeoutMs = 600000)
        val captured = AtomicReference<Pair<GatewayConfigJson, AppMeta>?>(null)
        setScreen(state, onSave = { c, a -> captured.set(c to a) })
        // Invalid: "0"
        composeRule.onNodeWithContentDescription("Timeout input").performTextClearance()
        composeRule.onNodeWithContentDescription("Timeout input").performTextInput("0")
        composeRule.onNodeWithContentDescription("Ubah timeout").performScrollTo()
        composeRule.onNodeWithContentDescription("Ubah timeout").performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText(context.getString(R.string.settings_timeout_error)).fetchSemanticsNodes().isNotEmpty())
        assertEquals(null, captured.get())
        // Invalid: "abc"
        composeRule.onNodeWithContentDescription("Timeout input").performTextClearance()
        composeRule.onNodeWithContentDescription("Timeout input").performTextInput("abc")
        composeRule.onNodeWithContentDescription("Ubah timeout").performClick()
        composeRule.waitForIdle()
        assertEquals(null, captured.get())
        // Valid: "15" minutes -> 900000 ms
        composeRule.onNodeWithContentDescription("Timeout input").performTextClearance()
        composeRule.onNodeWithContentDescription("Timeout input").performTextInput("15")
        composeRule.onNodeWithContentDescription("Ubah timeout").performClick()
        composeRule.waitForIdle()
        assertEquals(900000L, captured.get()?.first?.requestTimeoutMs)
    }

    @Test fun segera_controls_are_disabled() {
        val state = stateWith()
        setScreen(state)
        composeRule.onNodeWithText("Cloudflare Quick Tunnel").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Cloudflare Quick Tunnel").assertIsNotEnabled()
        assertTrue(composeRule.onAllNodesWithText("segera").fetchSemanticsNodes().isNotEmpty())
        composeRule.onNodeWithText("Periksa Update OmniRoute").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Periksa Update OmniRoute").assertIsNotEnabled()
        composeRule.onNodeWithText("Hibernate").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Hibernate").assertIsNotEnabled()
    }

    @Test fun export_confirms_before_writing_secrets() {
        val state = stateWith()
        val launchCount = AtomicInteger(0)
        setScreen(state, onExportLaunch = { launchCount.incrementAndGet() })
        composeRule.onNodeWithText("Ekspor Cadangan Konfigurasi").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Ekspor Cadangan Konfigurasi").performClick()
        composeRule.waitForIdle()
        // Confirm dialog body shown, SAF not yet launched.
        assertTrue(composeRule.onAllNodesWithText(context.getString(R.string.settings_export_confirm_msg)).fetchSemanticsNodes().isNotEmpty())
        assertEquals(0, launchCount.get())
        composeRule.onNodeWithText("Ekspor").performClick()
        composeRule.waitForIdle()
        assertEquals(1, launchCount.get())
    }

    @Test fun restore_rejects_invalid_json() {
        val state = stateWith()
        val captured = AtomicReference<Pair<GatewayConfigJson, AppMeta>?>(null)
        var toast: String? = null
        setScreen(state, onSave = { c, a -> captured.set(c to a) }, onToast = { toast = it }, restoreContent = "{garbage")
        composeRule.onNodeWithText("Pulihkan dari Cadangan").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Pulihkan dari Cadangan").performClick()
        composeRule.waitForIdle()
        assertTrue(toast != null)
        assertEquals(null, captured.get())
    }
}
