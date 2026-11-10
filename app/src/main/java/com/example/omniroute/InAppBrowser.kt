package com.example.omniroute

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

private val BrowserPill = RoundedCornerShape(999.dp)
private val BrowserCard = RoundedCornerShape(20.dp)

/** Satu tab browser in-app — tiap tab punya WebView sendiri agar state halaman terjaga. */
class BrowserTab(val id: Int, initialUrl: String) {
    var url by mutableStateOf(initialUrl)
    var title by mutableStateOf("")
    var progress by mutableStateOf(0)
    var groupId by mutableStateOf<String?>(null)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    lateinit var webView: WebView
}

/** Grup tab (mirip tab group di Chrome): nama + warna yang membedakan. */
class TabGroup(val id: String, val name: String, val color: Color)

/**
 * State browser in-app: daftar tab, grup, tab aktif, dan dialog menu.
 * Di-hoist di OmniRouteApp supaya tab tetap hidup saat pindah bottom-nav.
 */
class BrowserState(private val context: Context) {
    companion object {
        const val MAX_TABS = 10
    }

    val tabs = mutableStateListOf<BrowserTab>()
    val groups = mutableStateListOf<TabGroup>()
    var activeId by mutableStateOf(-1)
    var overviewOpen by mutableStateOf(false)
    var menuFor by mutableStateOf<Int?>(null)
    var pickerFor by mutableStateOf<Int?>(null)
    var newGroupName by mutableStateOf("")

    private var nextId = 1
    private var nextGroupId = 1
    private var container: FrameLayout? = null

    private val groupColors = listOf(
        Color(0xFFE8B74A), Color(0xFF60A5FA), Color(0xFFA78BFA),
        Color(0xFF34D399), Color(0xFFF472B6), Color(0xFF2DD4BF),
    )

    val active: BrowserTab? get() = tabs.firstOrNull { it.id == activeId }

    /** Dipanggil AndroidView saat container siap; pasang tab yang sudah ada. */
    fun attach(c: FrameLayout) {
        container = c
        tabs.forEach { t -> if (t.webView.parent == null) c.addView(t.webView, frameParams()) }
    }

    private fun frameParams() =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    fun newTab(url: String, groupId: String? = null, activate: Boolean = true): BrowserTab? {
        if (tabs.size >= MAX_TABS) {
            AppLog.log("WEB", "batas tab ($MAX_TABS) tercapai — tutup tab dulu")
            return null
        }
        val tab = BrowserTab(nextId++, url)
        tab.groupId = groupId
        tab.webView = createWebView(tab)
        container?.addView(tab.webView, frameParams())
        tabs += tab
        if (activate) activate(tab.id)
        tab.webView.loadUrl(url)
        AppLog.log("WEB", "tab baru: $url")
        return tab
    }

    fun activate(id: Int) {
        if (tabs.none { it.id == id }) return
        activeId = id
        tabs.forEach { t -> t.webView.visibility = if (t.id == id) View.VISIBLE else View.GONE }
    }

    fun closeTab(id: Int) {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        val tab = tabs[idx]
        runCatching {
            (tab.webView.parent as? FrameLayout)?.removeView(tab.webView)
            tab.webView.destroy()
        }
        tabs.removeAt(idx)
        if (menuFor == id) menuFor = null
        if (pickerFor == id) pickerFor = null
        if (activeId == id) {
            activeId = -1
            tabs.lastOrNull()?.let { activate(it.id) }
        }
        AppLog.log("WEB", "tab ditutup: ${tab.url}")
    }

    fun closeOthers(id: Int) {
        tabs.filter { it.id != id }.forEach { closeTab(it.id) }
        activate(id)
    }

    fun closeAll() {
        tabs.toList().forEach { closeTab(it.id) }
        activeId = -1
    }

    fun createGroup(name: String): TabGroup? {
        val n = name.trim()
        if (n.isEmpty()) return null
        val g = TabGroup("g$nextGroupId", n, groupColors[(nextGroupId - 1) % groupColors.size])
        nextGroupId++
        groups += g
        AppLog.log("WEB", "grup tab dibuat: $n")
        return g
    }

    fun assignGroup(tabId: Int, groupId: String?) {
        tabs.firstOrNull { it.id == tabId }?.groupId = groupId
    }

    fun destroyAll() {
        tabs.forEach { t ->
            runCatching {
                (t.webView.parent as? FrameLayout)?.removeView(t.webView)
                t.webView.destroy()
            }
        }
        tabs.clear()
        groups.clear()
        activeId = -1
        container = null
    }

    /** WebView baru dengan client yang menjaga semua navigasi tetap di dalam app. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(tab: BrowserTab): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                return if (scheme == "http" || scheme == "https") {
                    false // muat di tab yang sama
                } else {
                    // jangan pernah lompat ke browser eksternal
                    AppLog.log("WEB", "navigasi non-http diblokir: ${request.url}")
                    true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                tab.url = url ?: tab.url
                tab.progress = 0
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                tab.url = url ?: tab.url
                tab.progress = 100
                tab.canGoBack = view?.canGoBack() == true
                tab.canGoForward = view?.canGoForward() == true
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                tab.url = url ?: tab.url
                tab.canGoBack = view?.canGoBack() == true
                tab.canGoForward = view?.canGoForward() == true
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) tab.title = title
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                tab.progress = newProgress
            }

            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
            ): Boolean {
                // target=_blank → tab baru di dalam app
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val created = newTab("about:blank", groupId = tab.groupId) ?: return false
                transport.webView = created.webView
                resultMsg.sendToTarget()
                return true
            }
        }
    }
}

private fun hostOf(url: String): String = try {
    Uri.parse(url).host ?: url
} catch (_: Exception) {
    url
}

private fun normalizeUrl(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty()) return t
    return if (t.startsWith("http://") || t.startsWith("https://")) t else "http://$t"
}

/**
 * Dashboard = browser in-app penuh: strip tab (+ grup), address bar,
 * WebView full-bleed, dan overlay "Semua Tab". Tidak ada tombol browser eksternal.
 */
@Composable
fun InAppBrowser(
    state: BrowserState,
    homeUrl: String,
    palette: ThemePalette.Palette,
    context: Context,
) {
    LaunchedEffect(Unit) {
        if (state.tabs.isEmpty()) state.newTab(homeUrl)
    }

    val active = state.active
    BackHandler(enabled = active?.canGoBack == true) { active?.webView?.goBack() }

    Box(modifier = Modifier.fillMaxSize().background(palette.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabStrip(state = state, palette = palette, context = context)
            AddressRow(state = state, homeUrl = homeUrl, palette = palette, context = context)

            // progress tipis di bawah address bar
            Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(palette.surface)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(((active?.progress ?: 0).coerceIn(0, 100)) / 100f)
                        .height(2.dp)
                        .background(if (active != null) palette.gold else Color.Transparent)
                )
            }

            if (active == null) {
                EmptyBrowser(state = state, homeUrl = homeUrl, palette = palette, context = context)
            } else {
                AndroidView(
                    factory = { ctx -> FrameLayout(ctx).also { state.attach(it) } },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
        }

        if (state.overviewOpen) {
            TabOverview(state = state, palette = palette, context = context)
        }
    }

    state.menuFor?.let { tabId ->
        val tab = state.tabs.firstOrNull { it.id == tabId }
        if (tab != null) TabMenuDialog(tab = tab, state = state, palette = palette, context = context)
    }
    state.pickerFor?.let { tabId ->
        val tab = state.tabs.firstOrNull { it.id == tabId }
        if (tab != null) GroupPickerDialog(tab = tab, state = state, palette = palette, context = context)
    }
}

@Composable
private fun TabStrip(state: BrowserState, palette: ThemePalette.Palette, context: Context) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface.copy(alpha = 0.55f))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // tab yang masuk grup: dibungkus border berwarna
        state.groups.forEach { group ->
            val members = state.tabs.filter { it.groupId == group.id }
            if (members.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .clip(BrowserPill)
                        .border(1.5.dp, group.color, BrowserPill)
                        .padding(horizontal = 5.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        group.name,
                        color = group.color,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 72.dp)
                    )
                    members.forEach { tab -> TabChip(tab, state, palette, context) }
                }
            }
        }

        state.tabs.filter { it.groupId == null }.forEach { tab ->
            TabChip(tab, state, palette, context)
        }

        BrowserPillButton(
            label = "+",
            palette = palette,
            onClick = { state.newTab(homeUrlOf(context)) }
        )
        BrowserPillButton(
            label = "⊞ ${state.tabs.size}",
            palette = palette,
            onClick = { state.overviewOpen = true }
        )
    }
}

/** URL home default: port dari prefs. */
private fun homeUrlOf(context: Context): String =
    "http://127.0.0.1:${AppPrefs.snapshot(context).port}"

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabChip(tab: BrowserTab, state: BrowserState, palette: ThemePalette.Palette, context: Context) {
    val active = tab.id == state.activeId
    val groupColor = state.groups.firstOrNull { it.id == tab.groupId }?.color
    Row(
        modifier = Modifier
            .clip(BrowserPill)
            .background(if (active) palette.gold.copy(alpha = 0.22f) else palette.card.copy(alpha = 0.9f))
            .combinedClickable(
                onClick = { state.activate(tab.id) },
                onLongClick = { state.menuFor = tab.id }
            )
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (groupColor != null) {
            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(groupColor))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            tab.title.ifBlank { hostOf(tab.url) },
            color = if (active) palette.textHi else palette.textLo,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 110.dp)
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(palette.inactive.copy(alpha = 0.35f))
                .clickable { state.closeTab(tab.id) },
            contentAlignment = Alignment.Center
        ) {
            Text("✕", color = palette.textHi, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AddressRow(
    state: BrowserState,
    homeUrl: String,
    palette: ThemePalette.Palette,
    context: Context,
) {
    val active = state.active
    var draft by androidx.compose.runtime.remember(active?.id) { mutableStateOf(active?.url ?: "") }
    LaunchedEffect(active?.id, active?.url) {
        if (active != null && !active.url.startsWith("about:")) draft = active.url
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.background)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavIcon("←", enabled = active?.canGoBack == true, palette = palette) { active?.webView?.goBack() }
        NavIcon("→", enabled = active?.canGoForward == true, palette = palette) { active?.webView?.goForward() }
        NavIcon("⟳", enabled = active != null, palette = palette) { active?.webView?.reload() }
        NavIcon("⌂", enabled = active != null, palette = palette) {
            active?.webView?.loadUrl(homeUrl)
        }

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            modifier = Modifier.weight(1f),
            placeholder = { Text(homeUrl, color = palette.textLo, fontSize = 12.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(color = palette.textHi, fontSize = 13.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = {
                val u = normalizeUrl(draft)
                if (u.isNotEmpty()) {
                    if (active == null) state.newTab(u) else {
                        active.url = u
                        active.webView.loadUrl(u)
                    }
                }
            })
        )
    }
}

@Composable
private fun NavIcon(label: String, enabled: Boolean, palette: ThemePalette.Palette, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(BrowserCard)
            .background(palette.card.copy(alpha = if (enabled) 0.9f else 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (enabled) palette.textHi else palette.inactive,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun EmptyBrowser(state: BrowserState, homeUrl: String, palette: ThemePalette.Palette, context: Context) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(context.str("wb_no_tabs"), color = palette.textLo, fontSize = 14.sp)
            BrowserPillButton(label = context.str("wb_new_tab"), palette = palette, primary = true) {
                state.newTab(homeUrl)
            }
        }
    }
}

/** Overlay daftar semua tab, dikelompokkan per grup. */
@Composable
private fun TabOverview(state: BrowserState, palette: ThemePalette.Palette, context: Context) {
    val rows = buildList<Any> {
        val ungrouped = state.tabs.filter { it.groupId == null }
        if (ungrouped.isNotEmpty()) {
            add(context.str("wb_ungrouped"))
            addAll(ungrouped)
        }
        state.groups.forEach { g ->
            val members = state.tabs.filter { it.groupId == g.id }
            if (members.isNotEmpty()) {
                add(g)
                addAll(members)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background.copy(alpha = 0.98f))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${context.str("wb_all_tabs")} (${state.tabs.size})",
                color = palette.textHi, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(BrowserCard)
                    .background(palette.card)
                    .clickable { state.overviewOpen = false },
                contentAlignment = Alignment.Center
            ) {
                Text("✕", color = palette.textHi, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(10.dp))

        if (state.tabs.isEmpty()) {
            Text(context.str("wb_no_tabs"), color = palette.textLo, fontSize = 13.sp)
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(rows) { row ->
                    when (row) {
                        is TabGroup -> OverviewGroupHeader(row, state, palette, context)
                        is BrowserTab -> OverviewTabCard(row, state, palette, context)
                        else -> Text(
                            row as String,
                            color = palette.textLo,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BrowserPillButton(
                label = context.str("wb_new_tab"),
                modifier = Modifier.weight(1f),
                primary = true,
                palette = palette
            ) {
                state.newTab(homeUrlOf(context))
                state.overviewOpen = false
            }
            BrowserPillButton(
                label = context.str("wb_close_all"),
                modifier = Modifier.weight(1f),
                palette = palette
            ) { state.closeAll() }
        }
    }
}

@Composable
private fun OverviewGroupHeader(group: TabGroup, state: BrowserState, palette: ThemePalette.Palette, context: Context) {
    val count = state.tabs.count { it.groupId == group.id }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(group.color))
        Spacer(Modifier.width(8.dp))
        Text(group.name, color = palette.textHi, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text("$count tab", color = palette.textLo, fontSize = 11.sp)
    }
}

@Composable
private fun OverviewTabCard(tab: BrowserTab, state: BrowserState, palette: ThemePalette.Palette, context: Context) {
    val group = state.groups.firstOrNull { it.id == tab.groupId }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BrowserCard)
            .background(if (tab.id == state.activeId) palette.gold.copy(alpha = 0.14f) else palette.card)
            .clickable {
                state.activate(tab.id)
                state.overviewOpen = false
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (group != null) {
            Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(group.color))
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                tab.title.ifBlank { hostOf(tab.url) },
                color = palette.textHi, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                tab.url,
                color = palette.textLo, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(BrowserPill)
                .background(palette.inactive.copy(alpha = 0.3f))
                .clickable { state.closeTab(tab.id) },
            contentAlignment = Alignment.Center
        ) {
            Text("✕", color = palette.textHi, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Menu saat tab di-tahan lama: tutup, pindah grup, dll. */
@Composable
private fun TabMenuDialog(tab: BrowserTab, state: BrowserState, palette: ThemePalette.Palette, context: Context) {
    AlertDialog(
        onDismissRequest = { state.menuFor = null },
        containerColor = palette.card,
        title = { Text(tab.title.ifBlank { hostOf(tab.url) }, color = palette.textHi, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DialogAction(context.str("wb_move_group"), palette) {
                    state.pickerFor = tab.id
                    state.menuFor = null
                    state.newGroupName = ""
                }
                if (tab.groupId != null) {
                    DialogAction(context.str("wb_ungroup"), palette) {
                        state.assignGroup(tab.id, null)
                        state.menuFor = null
                    }
                }
                HorizontalDivider(color = palette.accentBorder)
                DialogAction(context.str("wb_close_tab"), palette) {
                    state.closeTab(tab.id)
                }
                DialogAction(context.str("wb_close_others"), palette) {
                    state.closeOthers(tab.id)
                    state.menuFor = null
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { state.menuFor = null }) {
                Text(context.str("wb_cancel"), color = palette.gold)
            }
        }
    )
}

/** Pilih / buat grup untuk sebuah tab. */
@Composable
private fun GroupPickerDialog(tab: BrowserTab, state: BrowserState, palette: ThemePalette.Palette, context: Context) {
    AlertDialog(
        onDismissRequest = { state.pickerFor = null },
        containerColor = palette.card,
        title = { Text(context.str("wb_move_group"), color = palette.textHi, fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.groups.isEmpty()) {
                    Text(context.str("wb_no_groups"), color = palette.textLo, fontSize = 12.sp)
                }
                state.groups.forEach { g ->
                    val count = state.tabs.count { it.groupId == g.id }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(BrowserCard)
                            .background(palette.surface)
                            .clickable {
                                state.assignGroup(tab.id, g.id)
                                state.pickerFor = null
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(g.color))
                        Spacer(Modifier.width(10.dp))
                        Text(g.name, color = palette.textHi, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("$count tab", color = palette.textLo, fontSize = 11.sp)
                    }
                }

                HorizontalDivider(color = palette.accentBorder)

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.newGroupName,
                        onValueChange = { state.newGroupName = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(context.str("wb_group_name"), color = palette.textLo, fontSize = 12.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(color = palette.textHi, fontSize = 13.sp)
                    )
                    BrowserPillButton(label = context.str("wb_create"), palette = palette, primary = true) {
                        state.createGroup(state.newGroupName)?.let { g ->
                            state.assignGroup(tab.id, g.id)
                        }
                        state.newGroupName = ""
                        state.pickerFor = null
                    }
                }

                if (tab.groupId != null) {
                    DialogAction(context.str("wb_ungroup"), palette) {
                        state.assignGroup(tab.id, null)
                        state.pickerFor = null
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { state.pickerFor = null }) {
                Text(context.str("wb_cancel"), color = palette.gold)
            }
        }
    )
}

@Composable
private fun DialogAction(label: String, palette: ThemePalette.Palette, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = palette.textHi, fontSize = 14.sp)
    }
}

@Composable
private fun BrowserPillButton(
    label: String,
    palette: ThemePalette.Palette,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(BrowserPill)
            .background(if (primary) palette.gold else palette.card.copy(alpha = 0.9f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (primary) Color(0xFF1F2937) else palette.textHi,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}
