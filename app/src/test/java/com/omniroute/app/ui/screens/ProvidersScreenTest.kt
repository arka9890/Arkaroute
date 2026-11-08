package com.omniroute.app.ui.screens

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.omniroute.app.R
import com.omniroute.app.config.AppMeta
import com.omniroute.app.config.ComboJson
import com.omniroute.app.config.GatewayConfigJson
import com.omniroute.app.config.ProviderConfigJson
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

@RunWith(RobolectricTestRunner::class)
class ProvidersScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun stateWith(
        providers: List<ProviderConfigJson>,
        healthProviders: List<ProviderStat> = providers.map { ProviderStat(id = it.id, healthy = true, tokensIn = 0, tokensOut = 0) },
        combos: List<String> = emptyList(),
        providerLimits: Map<String, Long> = emptyMap(),
    ): GatewayUiState {
        val coreCombos = combos.map { ComboJson(name = it, steps = emptyList()) }
        return GatewayUiState(
            running = true,
            health = HealthSnapshot(
                status = "ok",
                version = "0.1.0",
                uptimeSeconds = 10,
                providers = healthProviders,
                combos = combos,
            ),
            requestsPerMinute = 0,
            core = GatewayConfigJson(providers = providers, combos = coreCombos),
            app = AppMeta(providerLimits = providerLimits),
            lastError = null,
        )
    }

    private fun setScreen(state: GatewayUiState) {
        composeRule.setContent {
            ClayTheme(colors = clayColorsFor("oled"), depth = 85) {
                ProvidersScreen(state = state)
            }
        }
    }

    @Test fun summary_counts_connected_providers() {
        val providers = (1..3).map { i ->
            ProviderConfigJson(id = "p$i", kind = if (i % 2 == 0) "Anthropic" else "OpenAiCompatible")
        }
        val state = stateWith(providers = providers, combos = listOf("default"))
        setScreen(state)
        composeRule.onNodeWithText("3 Terhubung").assertIsDisplayed()
        composeRule.onNodeWithText("Aktif").assertIsDisplayed()
    }

    @Test fun quota_shows_dash_without_manual_limit() {
        val providers = listOf(ProviderConfigJson(id = "p1", kind = "OpenAiCompatible"))
        val health = listOf(ProviderStat(id = "p1", healthy = true, tokensIn = 100, tokensOut = 50))
        val state = stateWith(providers = providers, healthProviders = health, providerLimits = emptyMap())
        setScreen(state)
        val dashes = composeRule.onAllNodesWithText("—").fetchSemanticsNodes()
        assertTrue(dashes.isNotEmpty())
    }

    @Test fun quota_bar_shows_real_usage_with_limit() {
        val providers = listOf(ProviderConfigJson(id = "p1", kind = "OpenAiCompatible"))
        val health = listOf(ProviderStat(id = "p1", healthy = true, tokensIn = 100_000, tokensOut = 150_000))
        val state = stateWith(
            providers = providers,
            healthProviders = health,
            providerLimits = mapOf("p1" to 1_000_000L),
        )
        setScreen(state)
        assertEquals(2, composeRule.onAllNodesWithText("25%").fetchSemanticsNodes().size)
    }

    @Test fun kind_filter_chips_narrow_list() {
        val providers = listOf(
            ProviderConfigJson(id = "oai1", kind = "OpenAiCompatible"),
            ProviderConfigJson(id = "oai2", kind = "OpenAiCompatible"),
            ProviderConfigJson(id = "ant1", kind = "Anthropic"),
        )
        val state = stateWith(providers = providers)
        setScreen(state)
        // Semua: all visible
        assertTrue(composeRule.onAllNodesWithText("oai1").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("oai2").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("ant1").fetchSemanticsNodes().isNotEmpty())

        // OpenAI-compatible chip: disambiguate from kind badge via hasClickAction
        composeRule.onNode(hasText("OpenAI-compatible") and hasClickAction()).performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("oai1").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("oai2").fetchSemanticsNodes().isNotEmpty())
        assertEquals(0, composeRule.onAllNodesWithText("ant1").fetchSemanticsNodes().size)

        // Anthropic chip: same disambiguation
        composeRule.onNode(hasText("Anthropic") and hasClickAction()).performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("ant1").fetchSemanticsNodes().isNotEmpty())
        assertEquals(0, composeRule.onAllNodesWithText("oai1").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("oai2").fetchSemanticsNodes().size)

        // Back to Semua: all visible again
        composeRule.onNode(hasText("Semua") and hasClickAction()).performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("oai1").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("ant1").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun ping_button_absent_without_providers() {
        val state = stateWith(providers = emptyList())
        setScreen(state)
        assertEquals(0, composeRule.onAllNodesWithText("Uji").fetchSemanticsNodes().size)
    }
}
