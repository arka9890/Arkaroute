package com.omniroute.app.ui.screens

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.omniroute.app.R
import com.omniroute.app.config.AppMeta
import com.omniroute.app.ui.theme.ClayTheme
import com.omniroute.app.ui.theme.clayColorsFor
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Pins [WebViewScreen] chrome only (URL bar, status chip, CF TUNNEL toggle) as a
 * pure function of [url]/[online] + callbacks. NO WebView content assertions —
 * Robolectric has no real renderer; the dashboard HTML lives in Task 9.
 */
@RunWith(RobolectricTestRunner::class)
class WebViewScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun setScreen(
        url: String = AppMeta().webviewUrl,
        online: Boolean,
        onRefreshUrl: (String) -> Unit = {},
        onOpenExternally: () -> Unit = {},
    ) {
        composeRule.setContent {
            ClayTheme(colors = clayColorsFor("oled"), depth = 85) {
                WebViewScreen(
                    url = url,
                    online = online,
                    onRefreshUrl = onRefreshUrl,
                    onOpenExternally = onOpenExternally,
                )
            }
        }
    }

    @Test fun url_bar_shows_pref() {
        val prefUrl = AppMeta(webviewUrl = "http://127.0.0.1:5555/dash-test").webviewUrl
        setScreen(url = prefUrl, online = true)
        composeRule.onNodeWithText(prefUrl).assertIsDisplayed()
    }

    @Test fun offline_chip_when_health_null() {
        setScreen(online = false)
        composeRule.onNodeWithText(context.getString(R.string.status_offline)).assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("200 ONLINE").fetchSemanticsNodes().isEmpty())
    }

    @Test fun online_chip_when_healthy() {
        setScreen(online = true)
        composeRule.onNodeWithText(context.getString(R.string.status_online)).assertIsDisplayed()
    }

    @Test fun cf_tunnel_disabled_labeled_segera() {
        setScreen(online = true)
        composeRule.onNodeWithContentDescription(context.getString(R.string.cf_tunnel_label)).assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.cf_tunnel_soon), substring = true).assertIsDisplayed()
    }
}
