package com.omniroute.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun mainActivity_shows_title() {
        compose.onNodeWithText("OmniRoute Gateway").assertIsDisplayed()
    }
}
