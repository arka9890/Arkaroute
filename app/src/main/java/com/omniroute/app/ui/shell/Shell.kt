package com.omniroute.app.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniroute.app.R
import com.omniroute.app.data.GatewayUiState
import com.omniroute.app.config.AppMeta
import com.omniroute.app.config.GatewayConfigJson
import com.omniroute.app.ui.components.ClayCard
import com.omniroute.app.ui.screens.SettingsScreen
import com.omniroute.app.ui.theme.ClayTheme

enum class Tab { SERVICE, WEBVIEW, PROVIDERS, COMBOS, ROUTES }

private fun tabIcon(tab: Tab): ImageVector = when (tab) {
    Tab.SERVICE -> Icons.Filled.Dns
    Tab.WEBVIEW -> Icons.Filled.Monitor
    Tab.PROVIDERS -> Icons.Filled.ModelTraining
    Tab.COMBOS -> Icons.Filled.AltRoute
    Tab.ROUTES -> Icons.Filled.Route
}

private fun tabLabelRes(tab: Tab): Int = when (tab) {
    Tab.SERVICE -> R.string.tab_service
    Tab.WEBVIEW -> R.string.tab_webview
    Tab.PROVIDERS -> R.string.tab_providers
    Tab.COMBOS -> R.string.tab_combos
    Tab.ROUTES -> R.string.tab_routes
}

/**
 * Shell owns [selectedTab] + [showSettings], the fixed header (icon tile +
 * "OmniRoute"/"AI GATEWAY" + port pill + theme icon + avatar), the 5-tab
 * bottom bar, [BackHandler] closing Settings first, and the Settings
 * placeholder behind the avatar.
 */
@Composable
fun AppShell(
    startTab: Tab = Tab.SERVICE,
    port: Int,
    state: GatewayUiState = GatewayUiState(
        running = false,
        health = null,
        requestsPerMinute = 0,
        core = GatewayConfigJson(),
        app = AppMeta(),
        lastError = null,
    ),
    onSaveConfigAndRestart: (GatewayConfigJson, AppMeta) -> Unit = { _, _ -> },
    onSaveAppMeta: (AppMeta) -> Unit = {},
    onToast: (String) -> Unit = {},
    // Routes CTAs set this to a combo name ("" = new combo); the shell
    // switches to the Combos tab so CombosScreen can open the editor.
    pendingComboEditor: String? = null,
    content: @Composable (Tab) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(startTab) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(pendingComboEditor) {
        if (pendingComboEditor != null) {
            selectedTab = Tab.COMBOS
            showSettings = false
        }
    }

    BackHandler(enabled = showSettings) { showSettings = false }

    val colors = ClayTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Header(
            port = port,
            systemTheme = state.app.themeMode == "system",
            onThemeClick = {
                val nextTheme = when (state.app.themeMode) {
                    "oled" -> "soft"
                    "soft" -> "system"
                    else -> "oled"
                }
                onSaveAppMeta(state.app.copy(themeMode = nextTheme))
            },
            onAvatarClick = { showSettings = true },
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (showSettings) {
                SettingsScreen(
                    state = state,
                    onSaveConfigAndRestart = onSaveConfigAndRestart,
                    onSaveAppMeta = onSaveAppMeta,
                    onToast = onToast,
                )
            } else {
                content(selectedTab)
            }
        }
        BottomBar(
            selectedTab = selectedTab,
            onSelect = {
                selectedTab = it
                showSettings = false
            },
        )
    }
}

@Composable
private fun Header(
    port: Int,
    systemTheme: Boolean,
    onThemeClick: () -> Unit,
    onAvatarClick: () -> Unit,
) {
    val colors = ClayTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon tile (44dp clay)
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Route,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.app_title),
                color = colors.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            Text(
                text = stringResource(R.string.app_subtitle),
                color = colors.primary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
            )
        }
        // Port pill
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(colors.surface)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = stringResource(R.string.port_pill_format, port),
                color = colors.onSurface,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        // Theme icon cycling oled -> soft -> system; persisted via onSaveAppMeta.
        IconButton(onClick = onThemeClick) {
            Icon(
                imageVector = if (systemTheme) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                contentDescription = stringResource(R.string.theme_desc),
                tint = colors.onSurface,
            )
        }
        // Avatar — cyan circle, opens Settings
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.primary)
                .clickable(onClick = onAvatarClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = stringResource(R.string.avatar_desc),
                tint = Color(0xFF06222B),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun BottomBar(selectedTab: Tab, onSelect: (Tab) -> Unit) {
    val colors = ClayTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { tab ->
            val selected = tab == selectedTab
            val chipModifier = if (selected) {
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.primary.copy(alpha = 0.16f))
            } else {
                Modifier
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(chipModifier)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = tabIcon(tab),
                    contentDescription = null,
                    tint = if (selected) colors.primary else colors.onSurface.copy(alpha = 0.6f),
                )
                Text(
                    text = stringResource(tabLabelRes(tab)),
                    color = if (selected) colors.primary else colors.onSurface.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** Placeholder body behind the avatar; Task 8 fills the real Settings screen. Routes screen wired in MainActivity. */
@Composable
fun SettingsPlaceholder(modifier: Modifier = Modifier) {
    val colors = ClayTheme.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        ClayCard {
            Text(
                text = stringResource(R.string.settings_title),
                color = colors.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_placeholder),
                color = colors.onSurface.copy(alpha = 0.7f),
                fontSize = 14.sp,
            )
        }
    }
}
