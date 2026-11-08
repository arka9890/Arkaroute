package com.omniroute.app.ui.screens

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.omniroute.app.R
import com.omniroute.app.config.AppMeta
import com.omniroute.app.config.ComboJson
import com.omniroute.app.config.ComboStepJson
import com.omniroute.app.config.GatewayConfigJson
import com.omniroute.app.config.ProviderConfigJson
import com.omniroute.app.data.GatewayUiState
import com.omniroute.app.data.HealthSnapshot
import com.omniroute.app.data.LogLine
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
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class RoutesScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun stateWith(
        combos: List<ComboJson> = emptyList(),
        providers: List<ProviderConfigJson> = combos.flatMap { it.steps }.map { ProviderConfigJson(id = it.provider, kind = "OpenAiCompatible") }.distinctBy { it.id },
        healthProviders: List<ProviderStat> = providers.map { ProviderStat(id = it.id, healthy = true, lastLatencyMs = 0, requests = 0) },
        logs: List<LogLine> = emptyList(),
        defaultCombo: String? = combos.firstOrNull()?.name,
    ): GatewayUiState {
        return GatewayUiState(
            running = true,
            health = HealthSnapshot(status = "ok", providers = healthProviders, logs = logs),
            requestsPerMinute = 0,
            core = GatewayConfigJson(combos = combos, providers = providers, defaultCombo = defaultCombo),
            app = AppMeta(),
            lastError = null,
        )
    }

    private fun setScreen(
        state: GatewayUiState,
        baseUrl: String = "",
        onSave: (GatewayConfigJson, AppMeta) -> Unit = { _, _ -> },
        onOpenComboEditor: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            ClayTheme(colors = clayColorsFor("oled"), depth = 85) {
                RoutesScreen(state = state, baseUrl = baseUrl, onSaveConfigAndRestart = onSave, onOpenComboEditor = onOpenComboEditor)
            }
        }
    }

    @Test fun search_filters_steps_by_provider_or_model() {
        val combos = listOf(
            ComboJson(name = "default", steps = listOf(
                ComboStepJson(provider = "provA", model = "mA"),
                ComboStepJson(provider = "provB", model = "mB"),
            ))
        )
        val providers = listOf(
            ProviderConfigJson(id = "provA", kind = "OpenAiCompatible"),
            ProviderConfigJson(id = "provB", kind = "OpenAiCompatible"),
        )
        val health = listOf(
            ProviderStat(id = "provA", healthy = true),
            ProviderStat(id = "provB", healthy = true),
        )
        val state = stateWith(combos = combos, providers = providers, healthProviders = health, defaultCombo = "default")
        setScreen(state)
        assertTrue(composeRule.onAllNodesWithText("provA • mA").fetchSemanticsNodes().isNotEmpty())
        composeRule.onNodeWithText("Cari provider atau model...").performTextInput("provB")
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("provA • mA").fetchSemanticsNodes().size)
        assertTrue(composeRule.onAllNodesWithText("provB • mB").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun chips_filter_by_default_and_other_combos() {
        val combos = listOf(
            ComboJson(name = "default", steps = listOf(ComboStepJson(provider = "pDefault", model = "m"))),
            ComboJson(name = "backup", steps = listOf(ComboStepJson(provider = "pBackup", model = "m2"))),
        )
        val state = stateWith(combos = combos, defaultCombo = "default")
        setScreen(state)
        composeRule.onNode(hasText("Default") and hasClickAction()).performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("pDefault • m").fetchSemanticsNodes().isNotEmpty())
        assertEquals(0, composeRule.onAllNodesWithText("pBackup • m2").fetchSemanticsNodes().size)

        composeRule.onNode(hasText("Cadangan") and hasClickAction()).performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("pBackup • m2").fetchSemanticsNodes().isNotEmpty())
        assertEquals(0, composeRule.onAllNodesWithText("pDefault • m").fetchSemanticsNodes().size)

        composeRule.onNode(hasText("Semua") and hasClickAction()).performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("pDefault • m").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("pBackup • m2").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun card_shows_real_health_and_latency() {
        val combos = listOf(ComboJson(name = "default", steps = listOf(ComboStepJson(provider = "p1", model = "m1"))))
        val providers = listOf(ProviderConfigJson(id = "p1", kind = "OpenAiCompatible"))
        val health = listOf(ProviderStat(id = "p1", healthy = true, lastLatencyMs = 42, requests = 5))
        val state = stateWith(combos = combos, providers = providers, healthProviders = health, defaultCombo = "default")
        setScreen(state)
        assertTrue(composeRule.onAllNodesWithText("Sehat").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("42 ms").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun toggle_dims_cards_of_disabled_combo() {
        val combos = listOf(ComboJson(name = "c1", steps = listOf(ComboStepJson(provider = "p1", model = "m1")), enabled = false))
        val providers = listOf(ProviderConfigJson(id = "p1", kind = "OpenAiCompatible"))
        val health = listOf(ProviderStat(id = "p1", healthy = true))
        val state = stateWith(combos = combos, providers = providers, healthProviders = health, defaultCombo = "c1")
        setScreen(state)
        assertTrue(composeRule.onAllNodesWithText("Nonaktif").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("p1 • m1").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun cta_opens_combo_editor() {
        val state = stateWith(combos = emptyList())
        val opened = AtomicReference<String?>(null)
        setScreen(state, onOpenComboEditor = { opened.set(it) })
        composeRule.onNode(hasText("+ Tambah Rute Baru") and hasClickAction()).performClick()
        composeRule.waitForIdle()
        assertTrue(opened.get() != null)
    }

    @Test fun request_log_tail_shown() {
        val logs = (1..7).map { LogLine(message = "log$it") }
        val state = stateWith(combos = emptyList(), logs = logs)
        setScreen(state)
        assertTrue(composeRule.onAllNodesWithText("log7").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("log3").fetchSemanticsNodes().isNotEmpty())
        assertEquals(0, composeRule.onAllNodesWithText("log2").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("log1").fetchSemanticsNodes().size)
    }
}
