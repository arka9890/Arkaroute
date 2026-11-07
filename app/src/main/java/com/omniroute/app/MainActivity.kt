package com.omniroute.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.omniroute.app.config.ConfigStore
import com.omniroute.app.config.KeystoreCryptoEngine
import com.omniroute.app.service.GatewayService
import com.omniroute.app.ui.shell.AppShell
import com.omniroute.app.ui.shell.Tab
import com.omniroute.app.ui.theme.ClayTheme
import com.omniroute.app.ui.theme.clayColorsFor

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startIntent = Intent(this, GatewayService::class.java).apply {
            action = GatewayService.ACTION_START
        }
        startForegroundService(startIntent)
        setContent {
            val store = remember { ConfigStore(filesDir, KeystoreCryptoEngine()) }
            val (core, app) = remember(store) { store.load() }
            ClayTheme(colors = clayColorsFor(app.themeMode, app.highContrast), depth = app.clayDepth) {
                PermissionsRequest()
                AppShell(port = core.port) { tab ->
                    when (tab) {
                        Tab.SERVICE -> ServiceScreen()
                        Tab.WEBVIEW, Tab.PROVIDERS, Tab.COMBOS, Tab.ROUTES ->
                            // Placeholder — each tab gets its own screen in a later task.
                            Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.service_title),
            color = ClayTheme.colors.onSurface,
        )
    }
}

@Composable
fun PermissionsRequest() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { /* tanpa aksi */ }
    )
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
