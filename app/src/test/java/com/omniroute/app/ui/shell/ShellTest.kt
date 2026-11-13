package com.omniroute.app.ui.shell

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.omniroute.app.R
import com.omniroute.app.config.AppMeta
import com.omniroute.app.config.ConfigStore
import com.omniroute.app.config.CryptoEngine
import com.omniroute.app.config.GatewayConfigJson
import com.omniroute.app.ui.theme.ClayTheme
import com.omniroute.app.ui.theme.clayColorsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

private class IdentityCryptoEngine : CryptoEngine {
    override fun encrypt(plain: ByteArray): ByteArray = plain
    override fun decrypt(blob: ByteArray): ByteArray = blob
}

@RunWith(RobolectricTestRunner::class)
class ShellTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun tabLabels(): List<String> = listOf(
        context.getString(R.string.tab_service),
        context.getString(R.string.tab_dashboard),
        context.getString(R.string.tab_providers),
        context.getString(R.string.tab_combos),
        context.getString(R.string.tab_routes),
    )

    @Test fun shell_shows_five_tabs_in_spec_order() {
        composeRule.setContent {
            AppShell(port = 20128) { Box(Modifier.fillMaxSize()) }
        }
        val labels = tabLabels()
        labels.forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
        // left-to-right order by x-coordinates
        val lefts = labels.map {
            composeRule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.left
        }
        for (i in 0 until lefts.size - 1) {
            assertTrue("tab $i must be left of tab ${i + 1}", lefts[i] < lefts[i + 1])
        }
    }

    @Test fun avatar_opens_settings() {
        composeRule.setContent {
            AppShell(port = 20128) { Box(Modifier.fillMaxSize()) }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.avatar_desc)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.settings_title)).assertIsDisplayed()
    }

    @Test fun back_from_settings_returns_to_tab() {
        composeRule.setContent {
            AppShell(port = 20128) { Box(Modifier.fillMaxSize()) }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.avatar_desc)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.settings_title)).assertIsDisplayed()
        // performBackNavigation() does not exist in Compose 1.7.3 (BOM 2024.10.01); dispatch directly —
        // equivalent effect: routes the back press through the activity's OnBackPressedDispatcher,
        // which is what AppShell's BackHandler listens to.
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText(context.getString(R.string.settings_title)).fetchSemanticsNodes().size)
        composeRule.onNodeWithText(context.getString(R.string.tab_service)).assertIsDisplayed()
    }

    @Test fun theme_selection_persists_and_recomposes() {
        val dir = File(context.filesDir, "shell-theme-test-${System.nanoTime()}")
        dir.mkdirs()
        val store = ConfigStore(dir, IdentityCryptoEngine())

        store.save(GatewayConfigJson(), AppMeta(themeMode = "soft"))
        val softSaved = store.load().second
        assertEquals("soft", softSaved.themeMode)

        var mode by mutableStateOf(softSaved.themeMode)
        val captured = mutableStateOf(Color.Unspecified)
        composeRule.setContent {
            ClayTheme(colors = clayColorsFor(mode), depth = 85) {
                Box(Modifier.fillMaxSize().background(ClayTheme.colors.background))
                captured.value = ClayTheme.colors.background
            }
        }
        composeRule.waitForIdle()
        val softBackground = captured.value
        assertTrue(softBackground != Color.Unspecified)

        composeRule.runOnUiThread { mode = "oled" }
        composeRule.waitForIdle()
        val oledBackground = captured.value
        assertNotEquals(softBackground, oledBackground)

        store.save(GatewayConfigJson(), AppMeta(themeMode = mode))
        val reloaded = store.load().second
        assertEquals("oled", reloaded.themeMode)
    }
}
