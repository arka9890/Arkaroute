package com.omniroute.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.omniroute.app.config.ConfigStore
import com.omniroute.app.config.KeystoreCryptoEngine
import com.omniroute.app.data.AndroidServiceController
import com.omniroute.app.data.GatewayRepository
import com.omniroute.app.data.HttpHealthSource
import com.omniroute.app.data.JniGatewayBridge
import com.omniroute.app.service.GatewayService
import com.omniroute.app.ui.screens.ServiceScreen
import com.omniroute.app.ui.screens.WebViewScreen
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
            val context = LocalContext.current
            val store = remember { ConfigStore(filesDir, KeystoreCryptoEngine()) }
            val (core, app) = remember(store) { store.load() }
            val repository = remember(store) {
                GatewayRepository(
                    JniGatewayBridge,
                    HttpHealthSource(),
                    store,
                    AndroidServiceController(this@MainActivity),
                )
            }
            val pollScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
            DisposableEffect(pollScope) { onDispose { pollScope.cancel() } }
            LaunchedEffect(repository) { repository.startPolling(pollScope) }
            val state by repository.state.collectAsState()
            ClayTheme(colors = clayColorsFor(app.themeMode, app.highContrast), depth = app.clayDepth) {
                PermissionsRequest()
                AppShell(port = core.port) { tab ->
                    when (tab) {
                        Tab.SERVICE -> ServiceScreen(
                            state = state,
                            onDaemon = { on -> repository.requestDaemon(on) },
                            onRestart = {
                                repository.requestDaemon(false)
                                pollScope.launch {
                                    delay(300)
                                    repository.requestDaemon(true)
                                }
                            },
                            onClearCache = {
                                AppCache.clear(context)
                                Toast.makeText(context, R.string.cache_cleared_toast, Toast.LENGTH_SHORT).show()
                            },
                            onCopyLog = { text ->
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("logs", text))
                                Toast.makeText(context, R.string.logs_copied_toast, Toast.LENGTH_SHORT).show()
                            },
                        )
                        Tab.WEBVIEW -> WebViewScreen(
                            url = state.app.webviewUrl,
                            online = state.health != null,
                            // AppMeta only (webviewUrl) — no server restart; also force
                            // an immediate health fetch so the offline retry clears.
                            onRefreshUrl = { newUrl ->
                                repository.saveAppMeta(state.app.copy(webviewUrl = newUrl))
                                pollScope.launch { repository.pollOnce() }
                            },
                            onOpenExternally = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(state.app.webviewUrl))
                                )
                            },
                        )
                        Tab.PROVIDERS, Tab.COMBOS, Tab.ROUTES ->
                            // Placeholder — each tab gets its own screen in a later task.
                            Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
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
