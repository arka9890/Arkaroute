package com.omniroute.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.omniroute.app.R
import com.omniroute.app.ui.components.ClayButton
import com.omniroute.app.ui.components.RecessedWell
import com.omniroute.app.ui.theme.ClayTheme

/**
 * WebView tab: URL bar, control buttons, CF-TUNNEL toggle (disabled + "segera"),
 * status chip, and the recessed frame around the WebView (hosted via [WebViewHost]).
 *
 * Pure function of [url]/[online] + callbacks for JVM tests (no Android services,
 * no JNI). The WebView itself renders the dashboard from Task 9
 * (`dashboard/index.html`); this screen owns only the chrome around it.
 * WebView content is never asserted in Robolectric tests (no real renderer).
 *
 * - Offline ([online] == false, i.e. `state.health == null`): recessed frame shows
 *   [R.string.webview_offline_hint] + retry that reloads and calls
 *   [onRefreshUrl] (host forces an immediate health fetch).
 * - URL edits ([TextField] Done) call [onRefreshUrl]; the host persists via
 *   `GatewayRepository.saveAppMeta()` (AppMeta only — no server restart) and the
 *   WebView reloads via [LaunchedEffect].
 */
@Composable
fun WebViewScreen(
    url: String,
    online: Boolean,
    onRefreshUrl: (String) -> Unit,
    onOpenExternally: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClayTheme.colors
    val context = LocalContext.current
    // View (not WebView): the Kotlin compiler cannot resolve android.webkit.WebView
    // in this toolchain — all WebView touchpoints go through WebViewHost (Java).
    var webViewRef by remember { mutableStateOf<View?>(null) }
    var draft by remember(url) { mutableStateOf(url) }

    LaunchedEffect(url) {
        // Factory already loaded the initial URL; this covers later edits.
        // No-op under Robolectric when the fallback placeholder View is used.
        WebViewHost.loadUrl(webViewRef, url)
    }

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Top address tray: lock + editable mono URL well + status chip.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RecessedWell(modifier = Modifier.weight(1f)) {
                TextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        color = colors.onSurface,
                        fontSize = 13.sp,
                    ),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = stringResource(R.string.webview_lock_desc),
                            tint = colors.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            onRefreshUrl(draft)
                            WebViewHost.loadUrl(webViewRef, draft)
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // HTTP status chip (Review Focus 3: offline never shows "200 ONLINE").
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (online) colors.primary.copy(alpha = 0.16f)
                        else colors.onSurface.copy(alpha = 0.12f),
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stringResource(if (online) R.string.status_online else R.string.status_offline),
                    color = if (online) colors.primary else colors.onSurface.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Quick-action row: 3 clay icon buttons.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = { WebViewHost.reload(webViewRef) }) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.webview_refresh_desc),
                    tint = colors.primary,
                )
            }
            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("url", draft))
                    Toast.makeText(context, R.string.webview_copied_toast, Toast.LENGTH_SHORT).show()
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.webview_copy_desc),
                    tint = colors.onSurface,
                )
            }
            IconButton(onClick = onOpenExternally) {
                Icon(
                    imageVector = Icons.Filled.OpenInNew,
                    contentDescription = stringResource(R.string.webview_open_desc),
                    tint = colors.onSurface,
                )
            }
        }

        // Disabled CF TUNNEL toggle + "segera" chip (v1: no tunnel control yet).
        val cfTunnelLabel = stringResource(R.string.cf_tunnel_label)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = cfTunnelLabel,
                color = colors.onSurface.copy(alpha = 0.6f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            Switch(
                checked = false,
                onCheckedChange = {},
                enabled = false,
                modifier = Modifier.semantics { contentDescription = cfTunnelLabel },
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.surface)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.cf_tunnel_soon),
                    color = colors.onSurface.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // Recessed frame hosting the WebView (+ offline overlay).
        RecessedWell(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        try {
                            WebViewHost.create(ctx, url).also { webViewRef = it }
                        } catch (e: Throwable) {
                            // Robolectric has no WebView provider; placeholder keeps
                            // chrome tests green. Content assertions are out of scope.
                            View(ctx)
                        }
                    },
                    update = { view -> webViewRef = view },
                    modifier = Modifier.fillMaxSize(),
                )
                if (!online) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colors.surface.copy(alpha = 0.85f))
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.webview_offline_hint),
                                color = colors.onSurface,
                                fontSize = 14.sp,
                            )
                            ClayButton(
                                onClick = {
                                    WebViewHost.reload(webViewRef)
                                    onRefreshUrl(url)
                                },
                            ) {
                                Text(text = stringResource(R.string.webview_retry))
                            }
                        }
                    }
                }
            }
        }
    }
}
