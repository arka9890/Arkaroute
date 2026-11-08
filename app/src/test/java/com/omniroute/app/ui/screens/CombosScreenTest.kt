package com.omniroute.app.ui.screens

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import com.omniroute.app.R
import com.omniroute.app.config.AppMeta
import com.omniroute.app.config.ClientKey
import com.omniroute.app.config.ComboJson
import com.omniroute.app.config.ComboStepJson
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class CombosScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun stateWith(
        combos: List<ComboJson> = emptyList(),
        clientKeys: List<ClientKey> = emptyList(),
        gatewayKeys: List<String> = emptyList(),
    ): GatewayUiState {
        return GatewayUiState(
            running = true,
            health = HealthSnapshot(status = "ok", combos = combos.map { it.name }),
            requestsPerMinute = 0,
            core = GatewayConfigJson(combos = combos, gatewayKeys = gatewayKeys),
            app = AppMeta(clientKeys = clientKeys),
            lastError = null,
        )
    }

    private fun setScreen(
        state: GatewayUiState,
        baseUrl: String = "",
        onSave: (GatewayConfigJson, AppMeta) -> Unit = { _, _ -> },
        transport: (suspend (String, String) -> Triple<Int, String, String?>)? = null,
    ) {
        composeRule.setContent {
            ClayTheme(colors = clayColorsFor("oled"), depth = 85) {
                CombosScreen(state = state, baseUrl = baseUrl, onSaveConfigAndRestart = onSave, simulatorTransport = transport)
            }
        }
    }

    @Test fun segmented_switch_swaps_content() {
        val state = stateWith(combos = listOf(ComboJson(name = "combo-alpha", steps = emptyList())))
        setScreen(state)
        assertTrue(composeRule.onAllNodesWithText("combo-alpha").fetchSemanticsNodes().isNotEmpty())
        composeRule.onNode(hasText("Client API Keys") and hasClickAction()).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("combo-alpha").let { /* gone */ }
        assertEquals(0, composeRule.onAllNodesWithText("combo-alpha").fetchSemanticsNodes().size)
    }

    @Test fun combo_toggle_persists_enabled_flag() {
        val state = stateWith(combos = listOf(ComboJson(name = "c1", steps = emptyList(), enabled = true)))
        val captured = AtomicReference<Pair<GatewayConfigJson, AppMeta>?>(null)
        setScreen(state, onSave = { core, app -> captured.set(core to app) })
        composeRule.onNodeWithContentDescription("toggle enabled").performClick()
        composeRule.waitForIdle()
        val saved = captured.get()
        assertEquals(false, saved!!.first.combos[0].enabled)
    }

    @Test fun key_add_updates_gateway_keys_and_labels() {
        val state = stateWith(clientKeys = emptyList(), gatewayKeys = emptyList())
        val captured = AtomicReference<Pair<GatewayConfigJson, AppMeta>?>(null)
        setScreen(state, onSave = { core, app -> captured.set(core to app) })
        composeRule.onNode(hasText("Client API Keys") and hasClickAction()).performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasText("+ Tambah Key") and hasClickAction()).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Label").performTextInput("phone")
        composeRule.onNodeWithText("Secret").performTextInput("secret123")
        composeRule.onNodeWithText("Simpan").performClick()
        composeRule.waitForIdle()
        val saved = captured.get()
        assertTrue(saved!!.first.gatewayKeys.contains("secret123"))
        assertTrue(saved.second.clientKeys.any { it.label == "phone" && it.secret == "secret123" })
    }

    private fun startServer(responseCode: Int, body: String): Int {
        val socket = ServerSocket(0)
        val port = socket.localPort
        Thread {
            val client = socket.accept()
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            // consume request
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val out = client.getOutputStream()
            val statusText = if (responseCode == 200) "OK" else "Bad Request"
            val resp = "HTTP/1.1 $responseCode $statusText\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
            out.write(resp.toByteArray())
            out.flush()
            client.close()
            socket.close()
        }.start()
        return port
    }

    @Test fun simulator_disabled_without_combos() {
        val state = stateWith(combos = emptyList())
        setScreen(state)
        // switch to combos (default) already
        composeRule.onNodeWithText("Kirim Uji").assertIsNotEnabled()
        assertTrue(composeRule.onAllNodesWithText("Tidak ada combo tersedia").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun simulator_reports_real_result() {
        val transport: suspend (String, String) -> Triple<Int, String, String?> = { _, _ ->
            Triple(200, """{"choices":[{"index":0,"message":{"role":"assistant","content":"Halo"}}]}""", null)
        }
        val state = stateWith(combos = listOf(ComboJson(name = "c1", steps = emptyList())))
        setScreen(state, transport = transport)
        // SimulatorCard sits below the fold — scroll it into view so the
        // injected touch actually lands on the button (performTextInput works
        // off-screen, performClick does not).
        composeRule.onNodeWithText("Kirim Uji").performScrollTo()
        composeRule.onNodeWithText("Prompt").performTextInput("hi")
        composeRule.onNodeWithText("Kirim Uji").performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("Halo").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun simulator_surfaces_gateway_error() {
        val transport: suspend (String, String) -> Triple<Int, String, String?> = { _, _ ->
            Triple(400, """{"error":{"message":"model not found"}}""", null)
        }
        val state = stateWith(combos = listOf(ComboJson(name = "c1", steps = emptyList())))
        setScreen(state, transport = transport)
        composeRule.onNodeWithText("Kirim Uji").performScrollTo()
        composeRule.onNodeWithText("Prompt").performTextInput("hi")
        composeRule.onNodeWithText("Kirim Uji").performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("Gagal").fetchSemanticsNodes().isNotEmpty())
    }
}
