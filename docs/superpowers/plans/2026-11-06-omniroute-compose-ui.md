# OmniRoute Android — Compose UI (6 Screens, Claymorphism) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The five tab screens + Settings exactly in the claymorphism mockup layouts, Indonesian copy, showing only REAL gateway metrics — wired to the running in-process gateway.

**Architecture:** A single `GatewayRepository` (app-scoped `StateFlow`) polls `GET /health` over loopback HTTP and reads/writes the encrypted `ConfigStore`; screens are stateless composables collecting that flow. Daemon control goes through `GatewayService` actions; config saves go through `saveConfigAndRestart()` (core loads config only at start). The WebView tab loads the local dashboard HTML (built in Task 9) from the core's own server.

**Tech Stack:** Jetpack Compose + Material3 (existing scaffold), kotlinx-serialization, Robolectric + `ui-test-junit4` (JVM tests — the ONLY test tier; no emulator), `androidx.webkit`, `HttpURLConnection` (no new HTTP dep).

**Spec:** `docs/superpowers/specs/2026-11-04-omniroute-android-design.md` (binding). Mockup sources: `/root/workspace/stitch_omniroute_android_glass_dashboard/*_claymorphism/` (+ `omniroute_rute_node` for the Routes LAYOUT only, re-skinned to clay).

## Global Constraints

- Screens keep mockup layouts; **every number must be a real gateway metric** — where the mockup shows data we don't have, render `—` (spec decision 2), never an invented figure. Where a control can't work in v1, it renders **disabled with a visible "segera" label** — never an on-appearing switch (spec decision 4).
- All user-facing copy Indonesian, in `res/values/strings.xml` (tests assert via `context.getString(R.string.…)`); code identifiers English. Tab bar order (spec 1A): **Service · WebView · Providers · Combos · Routes**; **Settings opens from the avatar icon** in the top-right header (there is no Settings tab — this intentionally differs from the mockup chrome).
- Every Android change verified by CI only (two-push TDD: test → CI red → impl → CI green; poll command as in `2026-11-06-omniroute-android-bridge.md` Global Constraints). Rust changes (Tasks 5, 6) verified locally: `source $HOME/.cargo/env && cargo test --manifest-path core/Cargo.toml` (`CARGO_INCREMENTAL=0` on sandbox noise).
- Branch `dev`, conventional commits, push `origin dev`, never `main`. No new Gradle dependencies beyond those each task pins. No CDN/external requests anywhere in the app or dashboard (spec decision 5).
- Pinned deps to ADD (only these): `androidx.webkit:webkit:1.11.0` (Task 4), `androidx.lifecycle:lifecycle-runtime-compose:2.8.7` + `org.jetbrains.kotlinx:kotlinx-coroutines-test` (test-only, Task 2). Reuse Compose BOM 2024.10.01 / Robolectric 4.14.1 / kotlinx-serialization from the bridge plan.
- Colors (from mockups): background `#0B1326`, primary cyan `#4CD7F6`, secondary indigo `#D0BCFF`, tertiary mint `#4EDEA3`; clay = large drop shadow + inset light/dark bevels on cards.
- Config-change semantics: core reads config only at start → any saved config change MUST be followed by service STOP→START (`repository.saveConfigAndRestart()`); UI toasts `Gateway dimulai ulang` (Review Focus 2).

## Review Focus

1. **Server not running / process restarted** → screens show stopped/offline state, never stale numbers → Task 2 (`health_unavailable_marks_gateway_stopped`).
2. **Config edit while server runs** (port, LAN, providers, combos) → save must restart the service or the edit silently does nothing → Task 2 (`save_config_restarts_service_stop_then_start`), toast asserted in Task 8.
3. **WebView tab opened before the server is up** → OFFLINE chip + retry affordance, no white screen → Task 4 (`offline_chip_when_health_null`).
4. **Enabling LAN with zero gateway keys** → Settings must force key creation first; core `validate()` would reject the start otherwise → Task 8 (`lan_enable_without_keys_prompts_creation`).
5. **Theme change at runtime** → recomposes without restart and persists across activity recreation → Task 1 (`theme_selection_persists_and_recomposes`).
6. **Simulator/Providers actions with empty config** (no combos / no providers) → buttons disabled with Indonesian hint, no crash → Tasks 5-6 (`simulator_disabled_without_combos`, `ping_button_hidden_or_disabled_without_providers`).
7. **Exported backup contains secrets** → confirm dialog before writing → Task 8 (`export_confirms_before_writing_secrets`).

---

### Task 1: Clay design system + 5-tab shell + Settings navigation

**Files:**
- Create: `app/src/main/java/com/omniroute/app/ui/theme/ClayTheme.kt`, `ui/components/Clay.kt`, `ui/shell/Shell.kt`
- Modify: `app/src/main/java/com/omniroute/app/config/Models.kt` (AppMeta extensions), `MainActivity.kt`, `res/values/strings.xml`
- Test: `app/src/test/java/com/omniroute/app/ui/shell/ShellTest.kt`

**Interfaces:**
- Produces: `ClayTheme(colors: ClayColors, depth: Int, content: @Composable () -> Unit)`; `ClayColors` (data class: background, surface, primary, secondary, tertiary, onSurface — built from `ThemeMode`); `@Composable ClayCard(modifier, content)`, `ClayToggle(checked, onCheckedChange)`, `ClayButton`, `ClayChip(selected, onClick, label)`, `RecessedWell(content)` (inset container); `Shell.kt` holds `enum class Tab { SERVICE, WEBVIEW, PROVIDERS, COMBOS, ROUTES }` and `@Composable AppShell(startTab: Tab = Tab.SERVICE, port: Int, content: @Composable (Tab) -> Unit)` — `port` feeds the header pill (MainActivity passes it from `ConfigStore`; the repository re-wires it in Task 2). The shell owns `selectedTab` + `showSettings` (`rememberSaveable`), the fixed header (icon tile + "OmniRoute"/"AI GATEWAY" + port pill + theme icon + avatar), the 5-tab bottom bar, `BackHandler` closing Settings first, and a `SettingsScreen` placeholder behind the avatar. Later tasks replace each tab's placeholder body by supplying `content` (each screen composable added in its own task).
- AppMeta gains (all with defaults; Task 4's existing tests stay green): `themeMode: String = "oled"` (`"oled" | "soft" | "system"`), `clayDepth: Int = 85` (10..100), `highContrast: Boolean = false`, `providerLimits: Map<String, Long> = emptyMap()` (providerId → manual token limit).

- [ ] **Step 1: Write the failing tests** (`ShellTest.kt`, Robolectric + `createComposeRule`, strings via `context.getString`)

```kotlin
@Test fun shell_shows_five_tabs_in_spec_order();   // SERVICE, WEBVIEW, PROVIDERS, COMBOS, ROUTES labels all displayed, in that left-to-right order (assert via onAllNodes with merged tree + bounds order, or assert each exists — order check by x-coordinates)
@Test fun avatar_opens_settings();                 // click avatar (contentDescription = R.string.avatar_desc) → "Pengaturan Gateway" displayed
@Test fun back_from_settings_returns_to_tab();     // performBackNavigation() → settings gone, tab bar visible
@Test fun theme_selection_persists_and_recomposes(); // temp-dir ConfigStore (identity crypto): save themeMode="soft" → ClayTheme renders soft background; save "oled" → background semantics change; reload from store → still "oled"  (Review Focus 5) — drive ClayTheme directly, NOT through AppShell
```

- [ ] **Step 2: Push test → CI RED**

- [ ] **Step 3: Implement**

`ClayTheme.kt`: `ThemeMode` → `ClayColors` (oled = pure `#0B1326` bg; soft = lighter navy `#131C33`; system = follows `isSystemInDarkTheme()` pick), `highContrast` bumps text contrast, `depth` maps 10..100 → elevation/bevel px (0.4f..2.4f scale). Provide via `CompositionLocal`.
`Clay.kt`: `ClayCard` = `Modifier.clip(RoundedCornerShape(24.dp)).shadow(elevation, shape, ambientColor=Color.Black.copy(0.5f)).background(surface).border(bevel)` — implement the inset-bevel look with two `border`s (top light `+12% white`, bottom dark `+30% black`) or a `drawBehind` if the reviewer finds borders insufficient; the test only pins behavior, look is reviewed from code + later screenshots. `ClayToggle` = custom `Switch` with cyan track/thumb per mockup (reuse Material3 `Switch` colors — do not hand-roll gesture code).
`Shell.kt`: header `h-80dp` (icon tile 44dp clay + 2-line title mono uppercase, right: port pill from `ConfigStore` port, theme icon button cycling `themeMode` oled↔system, avatar cyan circle); bottom bar 5 equal tabs icon+label (Material icons: `Dns`, `Monitor`, `ModelTraining`, `AltRoute`, `Route`), active = cyan text + recessed chip; `showSettings` swaps content to `SettingsPlaceholder()` ("Pengaturan Gateway — menyusul" is NOT ok — placeholder text: use `R.string.settings_placeholder` = `Pengaturan Gateway` with a `LinearProgressIndicator`? No — Task 8 fills it; for now a `ClayCard(Text(stringResource(R.string.settings_placeholder)))` where placeholder = `Bagian pengaturan akan diisi di tugas berikutnya` — internal scaffolding copy, acceptable pre-release).
`MainActivity`: wrap content in `ClayTheme` reading `ConfigStore.load()` (via `remember`), then `AppShell(port = loadedPort) { tab -> … }` — **keep the bridge-plan Task 5 logic already in MainActivity** (ACTION_START on create + the guarded POST_NOTIFICATIONS request) intact when rewrapping.
All tab labels/section strings → `strings.xml`.

- [ ] **Step 4: Push impl → CI GREEN**

- [ ] **Step 5: Commit** `feat(app): clay design system with five-tab shell and avatar settings entry`

---

### Task 2: Data layer — repository, health polling, service control

**Files:**
- Create: `app/src/main/java/com/omniroute/app/data/Models.kt`, `HealthSource.kt`, `GatewayRepository.kt`, `OmniRouteApplication.kt`
- Modify: `AndroidManifest.xml` (`android:name=".OmniRouteApplication"`), `build.gradle.kts` (add `lifecycle-runtime-compose:2.8.7`)
- Test: `app/src/test/java/com/omniroute/app/data/GatewayRepositoryTest.kt`

**Interfaces:**
- Consumes: `ConfigStore`/`AppMeta` (bridge plan Task 4), `NativeGateway` behind a new interface.
- Produces: `interface GatewayBridge { fun startServer(configJson: String): String; fun stopServer(): String; fun isRunning(): Boolean }` + `object JniGatewayBridge : GatewayBridge` (delegates to `NativeGateway`; the ONLY place touching it); `interface HealthSource { fun fetch(baseUrl: String): String? }` + `HttpHealthSource` (HttpURLConnection GET, 1500ms timeouts, returns body or null); `data class HealthSnapshot(status, version, uptimeSeconds, lanEnabled, requestsTotal, requestsFailed, tokensIn, tokensOut, providers: List<ProviderStat>, combos: List<String>, logs: List<LogLine>)` with `@SerialName` snake_case matching `StatsSnapshot`; `data class GatewayUiState(running: Boolean, health: HealthSnapshot?, requestsPerMinute: Long, core: GatewayConfigJson, app: AppMeta, lastError: String?)` — `requestsPerMinute` computed by the repository from consecutive `requestsTotal` samples (delta × 60 ÷ elapsed seconds; 0 until two samples exist); `class GatewayRepository(bridge, healthSource, store, serviceController)` with `val state: StateFlow<GatewayUiState>`, `fun startPolling(scope: CoroutineScope)` (2s loop), `fun requestDaemon(on: Boolean)` (dispatches `GatewayService.ACTION_START/STOP` via `serviceController` — an interface so tests avoid Android), `fun saveConfigAndRestart(core, app): String?` (save → STOP → 300ms delay → START → returns toast message or error), `fun saveAppMeta(app: AppMeta)` (persist AppMeta only — NO server restart), `fun baseUrl(): String` = `http://{core.bind}:{core.port}` — note bind may be `0.0.0.0` → poll `127.0.0.1` instead (pin: `val host = if (core.bind == "0.0.0.0") "127.0.0.1" else core.bind`).

- [ ] **Step 1: Write the failing tests** (`GatewayRepositoryTest.kt`; fakes: `FakeBridge` recording calls, `FakeHealthSource` returning canned JSON or null, `FakeServiceController` recording actions, temp-dir `ConfigStore` with identity crypto)

```kotlin
@Test fun parses_health_snapshot();               // canned /health JSON → state.health has uptimeSeconds=105, providers size 2, logs size 1 (snake_case mapping)
@Test fun health_unavailable_marks_gateway_stopped(); // source returns null → running=false, health=null  (Review Focus 1)
@Test fun save_config_restarts_service_stop_then_start(); // saveConfigAndRestart → serviceController recorded [STOP, START] in order AND store file updated  (Review Focus 2)
@Test fun computes_requests_per_minute();         // samples requestsTotal 100 at t=0 and 130 at t=60s (virtual) → state.requestsPerMinute == 30; single sample → 0
@Test fun polls_every_two_seconds();              // with fake source counting calls, advance virtual time 5s → 2..3 fetches (use kotlinx-coroutines-test `runTest` + `virtualTimeDispatcher`)
@Test fun zero_bind_polls_localhost();            // core.bind="0.0.0.0" → baseUrl uses 127.0.0.1
```

- [ ] **Step 2: Push test → CI RED**

- [ ] **Step 3: Implement** per the Interfaces block; `OmniRouteApplication` creates the singletons (`ConfigStore(FilesDir, KeystoreCryptoEngine())`, `HttpHealthSource`, `JniGatewayBridge`, `GatewayRepository`) in `onCreate` and exposes them via `companion object { lateinit var graph: Graph }` (manual DI — no Hilt). `AndroidManifest`: `<application android:name=".OmniRouteApplication" …>`.

- [ ] **Step 4: Push impl → CI GREEN**

- [ ] **Step 5: Commit** `feat(app): gateway repository with health polling and restart-on-save`

---

### Task 3: Service screen (mockup `omniroute_service_controller_claymorphism`)

**Files:**
- Create: `app/src/main/java/com/omniroute/app/ui/screens/ServiceScreen.kt`, `app/src/main/java/com/omniroute/app/AppCache.kt`
- Test: `app/src/test/java/com/omniroute/app/ui/screens/ServiceScreenTest.kt`
- Modify: `Shell.kt` (replace `Tab.SERVICE` placeholder), `strings.xml`

**Interfaces:**
- Consumes: `GatewayRepository.state` (Task 2), shell chrome (Task 1).
- Produces: `object AppCache { fun clear(context: Context): Long; fun size(context: Context): Long }` — WebView cache-dir file sizes in bytes, cleared/reported for the Service screen's `Hapus Cache` and reused by Settings (Task 8). Keep it free of `NativeGateway` references so JVM tests never trigger `loadLibrary`.

- [ ] **Step 1: Write the failing tests** (fake repo state injected via a `GatewayRepository` test double or by passing `GatewayUiState` into `ServiceScreen(state, onDaemon, onRestart, onCopyLog, …)` — pin the screen as a pure function of state + callbacks so tests need no Android services)

```kotlin
@Test fun shows_real_uptime_and_host();          // uptimeSeconds=14905 → "04:08:25"; host well shows "127.0.0.1:20128/v1"
@Test fun daemon_toggle_dispatches_stop_when_running(); // running=true → toggle click → onDaemon(false)
@Test fun telemetry_shows_real_tokens_and_health();     // tokensIn=1000,tokensOut=2000 → "3.0K tok"; providers 3/4 healthy → "75%"; requestsFailed=2 → footer mentions "2"
@Test fun req_per_minute_computed_from_samples();       // state.requestsPerMinute=30 → card shows "30 req/mnt" (computation itself is pinned in Task 2)
@Test fun stopped_state_shows_layanan_berhenti();       // running=false → pill text R.string.status_stopped, values hidden behind "—"
```

- [ ] **Step 2: Push test → CI RED**

- [ ] **Step 3: Implement** — layout top→bottom exactly per mockup: hero card (status pill + version chip `snapshot.version`, H1 `OmniRoute Gateway`, subtitle `Daemon pengarah AI multi-penyedia Android`, daemon toggle; bottom2-col wells: Host Lokal Port + Waktu Aktif), telemetry 2×2 bento, "Pipa Tingkatan Routing" card (pill `Otomatis-Failover`; 4 badges = first 4 steps of the default/first combo: `provider • model` + real health line — NOT the mockup's Langganan/Kunci API/Ekonomis/Gratis tiers), "Log Aliran SSE Langsung" card (mono recessed well from `snapshot.logs`, `Salin` → clipboard), 2-col buttons `Mulai Ulang` (daemon off→on via callbacks) / `Hapus Cache` (callback → `AppCache.clear()` + toast).
Metric mapping (pin): `Permintaan` = req/min computed between successive snapshots, footer = `+{requestsTotal} total`; `Total Token` = `tokensIn+tokensOut` compact-formatted, footer = `{tokensIn} masuk · {tokensOut} keluar` (drop mockup's fake "kompresi %"); `Latensi` = max `last_latency_ms` across providers (label `Latensi Tertinggi (ms)`, footer = that provider's id; `—` when no data); `Kesehatan` = healthy/total %, footer = `{requestsFailed} permintaan gagal`. No `p95`, no invented percentages.

- [ ] **Step 4: Push impl → CI GREEN**

- [ ] **Step 5: Commit** `feat(ui): Service screen with live gateway telemetry`

---

### Task 4: WebView screen (mockup `omniroute_webview_dashboard_claymorphism`)

**Files:**
- Create: `app/src/main/java/com/omniroute/app/ui/screens/WebViewScreen.kt`
- Modify: `app/build.gradle.kts` (webkit 1.11.0), `Shell.kt`, `strings.xml`, `app/src/main/java/com/omniroute/app/data/GatewayRepository.kt` (add `saveAppMeta`)
- Test: `app/src/test/java/com/omniroute/app/ui/screens/WebViewScreenTest.kt`

**Interfaces:**
- Consumes: `state.health` for the status chip, `state.app.webviewUrl` for the URL.
- Produces: `@Composable WebViewScreen(url, online, onRefreshUrl(newUrl), onOpenExternally())` — the WebView itself renders the dashboard from Task 9; URL edits persist via the new `GatewayRepository.saveAppMeta()` (AppMeta only — no server restart).

**Mapping note (pin):** the mockup's *inner* frame content (topology, MCP/A2A cards, token slider, IPC ticker) is the **dashboard HTML** — it lives in `dashboard/index.html` (Task 9) and is displayed by the WebView, not rebuilt in Compose. This screen owns: URL bar, control buttons, CF-TUNNEL toggle (disabled + "segera"), status chip, and the recessed frame around the WebView.

- [ ] **Step 1: Write the failing tests** (NO WebView content assertions — Robolectric has no real renderer; pin chrome only)

```kotlin
@Test fun url_bar_shows_pref();                  // webviewUrl pref → address well text matches
@Test fun offline_chip_when_health_null();       // health=null → chip shows R.string.status_offline, not "200 ONLINE"  (Review Focus 3)
@Test fun online_chip_when_healthy();            // health.status="ok" → chip R.string.status_online
@Test fun cf_tunnel_disabled_labeled_segera();   // toggle exists, isEnabled=false, sibling label contains "segera"
```

- [ ] **Step 2: Push test → CI RED**

- [ ] **Step 3: Implement**: address well (mono, selectable, lock icon) + chip; row of 3 clay icon buttons — refresh (`state.refresh` → `webView.reload()`), salin URL (clipboard), buka di browser (`Intent(ACTION_VIEW, uri)` via callback); CF TUNNEL switch disabled with `segera` chip; recessed frame hosting `AndroidView { WebView(it).apply { settings.javaScriptEnabled = true; settings.domStorageEnabled = true; loadUrl(url) } }` (androidx.webkit `WebSettingsCompat` not required for v1). On URL edit → persist via `saveConfigAndRestart`? NO — `webviewUrl` is AppMeta only → `store.save(core, app.copy(webviewUrl=…))` (no server restart — pin: helper `repository.saveAppMeta()`), reload. Offline (health=null): overlay `R.string.webview_offline_hint` + retry button that forces an immediate health fetch.

- [ ] **Step 4: Push impl → CI GREEN**

- [ ] **Step 5: Commit** `feat(ui): WebView tab loading the local gateway dashboard`

---

### Task 5: Providers screen + core `POST /v1/providers/{id}/ping`

**Files (core, local TDD first):**
- Create: `core/tests/providers_ping_test.rs`
- Modify: `core/src/server.rs` (route + handler)

**Files (app):**
- Create: `app/src/main/java/com/omniroute/app/ui/screens/ProvidersScreen.kt`, `ProviderEditorDialog.kt`
- Test: `app/src/test/java/com/omniroute/app/ui/screens/ProvidersScreenTest.kt`
- Modify: `Shell.kt`, `strings.xml`

**Interfaces:**
- Produces (core): `POST /v1/providers/{id}/ping` → standard auth (`authorize`), unknown id → 404; handler performs `GET {base_url}/models` with the provider's auth header (`Authorization: Bearer` for openai_compat, `x-api-key` for anthropic — mirror whatever header the existing adapter sends; check `core/src/providers/*` and reuse its header construction if exported, else duplicate one line), measures elapsed ms; ANY HTTP response → `200 {"ok":true,"latency_ms":N,"status":C}`; connect error/timeout → `{"ok":false,"error":"..."}` (502). Timeout = `min(request_timeout_ms, 5000)`.
- Produces (app): `@Composable ProvidersScreen(state, callbacks…)`; ping via `HttpURLConnection` POST to `baseUrl() + "/providers/" + id + "/ping"` on `Dispatchers.IO`.

- [ ] **Step 1: Core failing test** (`providers_ping_test.rs`): `ping_returns_latency_for_reachable_provider` (mock upstream 200 → `ok:true`, `latency_ms` present, `status:200`), `ping_unreachable_returns_ok_false` (bind-then-drop port → `ok:false`), `ping_unknown_provider_is_404`, `ping_requires_auth_when_remote` (call `authorize` path with `PeerAddr` non-loopback + keys configured, no header → 401).
- [ ] **Step 2: Run → FAIL** (`cargo test --manifest-path core/Cargo.toml --test providers_ping_test`)
- [ ] **Step 3: Implement the handler** (route line beside the existing ones in `build_router`; use `tokio::time::timeout(Duration::from_millis(5000), …)`; reqwest client from `AppState`).
- [ ] **Step 4: Run → PASS + full suite green** → `commit -m "feat(core): provider ping endpoint with reachability latency"`.
- [ ] **Step 5: App failing tests** (`ProvidersScreenTest.kt`):

```kotlin
@Test fun summary_counts_connected_providers();  // 3 providers → "3 Terhubung" (from strings), combos>0 → "Aktif"
@Test fun quota_shows_dash_without_manual_limit();// providerLimits empty → bar hidden, value "—"  (spec decision 2 — never invent)
@Test fun quota_bar_shows_real_usage_with_limit();// limit=1_000_000, tokensIn+Out=250_000 → 25%
@Test fun kind_filter_chips_narrow_list();       // chips Semua/OpenAI-compat/Anthropic → filtered card counts
@Test fun ping_button_disabled_without_providers();// empty providers → ping CTA not displayed  (Review Focus 6)
```

- [ ] **Step 6: Push app test → CI RED**
- [ ] **Step 7: Implement** — banner (`+ Provider` opens `ProviderEditorDialog`: id, kind dropdown [OpenAI-compatible/Anthropic], base_url, api_key password field, models comma-separated; validation: id/base_url non-empty, id unique → save via `saveConfigAndRestart`), 3-pill summary (TERHUBUNG = `core.providers.size`, KUOTA TOTAL = aggregate bar only if ≥1 manual limit else `—`, FALLBACK = `Aktif`/`—` by `combos.isNotEmpty()`), filter chips (kind-based — v1 has no OAuth, so the mockup's "Langganan (OAuth)" chip is replaced; deviation noted), provider cards (kind badge, status pill from `providers[].healthy`, recessed quota block with manual-limit bar or `—`, weekly/other mockup meters replaced by real `requests`/`tokens_in`/`tokens_out`/`last_latency_ms`, `Uji` button → ping → toast `Latensi: {n} ms` or `Tidak terjangkau: {error}`, `Rotasi Sesi` button DISABLED + `segera` — no OAuth in v1, deviation noted), "Pohon Fallback Otomatis" card = default combo's numbered steps (real).
- [ ] **Step 8: Push impl → CI GREEN** → `feat(ui): Providers screen with real quota and provider ping`

---

### Task 6: Combos screen + core `enabled` flag + failover simulator

**Files (core, local TDD):**
- Create: `core/tests/combo_enabled_test.rs`
- Modify: `core/src/config.rs` (`Combo.enabled`), `core/src/routing.rs` (skip disabled), `core/src/server.rs` (models list filter)

**Files (app):**
- Create: `app/src/main/java/com/omniroute/app/ui/screens/CombosScreen.kt`, `ComboEditorScreen.kt`, `ClientKeysScreen.kt`, `SimulatorCard.kt`
- Test: `app/src/test/java/com/omniroute/app/ui/screens/CombosScreenTest.kt`
- Modify: `Shell.kt`, `strings.xml`

**Interfaces:**
- Produces (core): `Combo.enabled: bool` with `#[serde(default = "default_true")]` (existing configs stay enabled); disabled combo → model resolution returns `UnknownModel` (same as an unknown name); `/v1/models` omits disabled combo names.
- Produces (app): `CombosScreen(state, callbacks…)` with segmented switch `Model Combos | Client API Keys`; `ComboEditorScreen` (name + ordered steps: provider dropdown, model input, add/remove/reorder-up-down) reached by header `+` button AND by the Routes CTA (Task 7); client-keys list from `app.clientKeys` with add/delete/copy; `SimulatorCard` POSTs to `/v1/chat/completions`.

- [ ] **Step 1: Core failing tests**: `disabled_combo_not_in_models`, `disabled_combo_resolves_to_unknown_model` (routing test), `enabled_flag_defaults_true_for_old_config` (deserialize `{"name":"x","steps":[]}` → `enabled==true`).
- [ ] **Step 2: Run → FAIL → implement → Run → PASS + full suite** → `commit -m "feat(core): combo enabled flag with routing and models filtering"`.
- [ ] **Step 3: App failing tests**:

```kotlin
@Test fun segmented_switch_swaps_content();      // "Client API Keys" click → key list shown, combo cards gone
@Test fun combo_toggle_persists_enabled_flag();  // toggle → saved config.combos[0].enabled == false (via fake store)
@Test fun key_add_updates_gateway_keys_and_labels(); // add(label, secret) → core.gateway_keys contains secret AND app.clientKeys contains pair
@Test fun simulator_disabled_without_combos();   // combos empty → "Kirim Uji" disabled + hint  (Review Focus 6)
@Test fun simulator_reports_real_result();       // fake transport returns choices[0].message.content="Halo" → result card shows "Halo"
@Test fun simulator_surfaces_gateway_error();    // transport returns 400 body {"error":{...}} → Indonesian error text shown, not a crash
```

- [ ] **Step 4: Push app test → CI RED**
- [ ] **Step 5: Implement** — combo cards per mockup: name, badge (step count / `Default`), `enabled` toggle, vertical numbered step chain (`provider • model` + real health line), tap → `ComboEditorScreen`; section header `Rantai Failover Cerdas` + counter `{n} Aktif`; simulator card: combo dropdown (from `core.combos`), prompt input, `Kirim Uji` → `HttpURLConnection` POST non-stream `{model: name, messages:[{role:"user",content:prompt}], stream:false}` with 30s timeout → render `choices[0].message.content` or the gateway's error `message`; states per mockup (`Memvalidasi Rantai...` → `Sukses: Terlayani` / failure). Client API Keys segment: rows `{label} · ••••{last4}` + `Salin` (clipboard) + delete (removes from BOTH lists via `saveConfigAndRestart`); `+ Tambah Key` dialog (label + secret, secret masked). Footer pill `Enkripsi AES-256-GCM Hardware Android Keystore` (true — Task 4 store).
- [ ] **Step 6: Push impl → CI GREEN** → `feat(ui): Combos and client API keys with working failover simulator`

---

### Task 7: Routes screen (glass `omniroute_rute_node` layout, clay skin, real data)

**Files:**
- Create: `app/src/main/java/com/omniroute/app/ui/screens/RoutesScreen.kt`
- Test: `app/src/test/java/com/omniroute/app/ui/screens/RoutesScreenTest.kt`
- Modify: `Shell.kt`, `strings.xml`

**Interfaces:**
- Consumes: `state.core.combos/providers`, `state.health.providers` (health per id), ping (Task 5 endpoint), `ComboEditorScreen` (Task 6) via shell navigation callback `onOpenComboEditor(comboName)`.

**Mapping (pin):** each **combo step** is a route card. Badge = `PRIMER` when the step belongs to `default_combo` (or first combo when no default), else `CADANGAN`. The per-card enable toggle controls the **owning combo's** `enabled` (cards of the same combo share state — reality, documented deviation from the mockup's per-route toggle). Mockup's IP/iface/loss/traffic columns → real: Latensi (`last_latency_ms`), Status (`Sehat` / `Gagal — {last_error}`), Permintaan (`requests`). The misleading CTA copy (`Konfigurasi WireGuard, SOCKS5, atau Direct CIDR`) → `Susun rantai fallback provider & model`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun search_filters_steps_by_provider_or_model();
@Test fun chips_filter_by_default_and_other_combos();   // Semua / Default / Cadangan
@Test fun card_shows_real_health_and_latency();         // provider healthy, latency 42 → "Sehat", "42 ms"
@Test fun toggle_dims_cards_of_disabled_combo();        // combo.enabled=false → card semantics show disabled + badge "Nonaktif"
@Test fun cta_opens_combo_editor();                     // "Tambah Rute Baru" click → onOpenComboEditor invoked
@Test fun request_log_tail_shown();                     // health.logs last entries displayed in "Log Request Terakhir" card (spec scope: Routes includes log request)
```

- [ ] **Step 2: Push test → CI RED**
- [ ] **Step 3: Implement** top→bottom: header card (icon + `{n} rantai · {m} provider sehat`, right badge `TERSINKRON` — real), search field (`Cari provider atau model...`), chip row, CTA banner (gradient clay, `+ Tambah Rute Baru` → editor), route cards (icon by kind, title `provider • model`, toggle, 3-col diagnostics, footer: badge + `Ping` (POST ping → toast latency) + edit (opens editor) + delete (confirm dialog → remove step → `saveConfigAndRestart`)), then `Log Request Terakhir` card (last 5 `snapshot.logs`, mono, recessed). Toasts on actions (`Rute diperbarui`, `Node diaktifkan` style — Indonesian).
- [ ] **Step 4: Push impl → CI GREEN**
- [ ] **Step 5: Commit** `feat(ui): Routes screen with real fallback-chain observability`

---

### Task 8: Settings screen (mockup `omniroute_pengaturan_tema_claymorphism` + spec additions)

**Files:**
- Create: `app/src/main/java/com/omniroute/app/ui/screens/SettingsScreen.kt`
- Test: `app/src/test/java/com/omniroute/app/ui/screens/SettingsScreenTest.kt`
- Modify: `Shell.kt` (Settings placeholder → `SettingsScreen`), `strings.xml`

**Interfaces:**
- Consumes: everything (repository `saveConfigAndRestart`, `saveAppMeta`, `AppCache`, theme from Task 1, SAF callbacks via `rememberLauncherForActivityResult` — tests pass fakes).

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun header_shows_real_uptime_and_success_rate(); // uptime formatted; routing = (total-failed)/total → "99.9%"; daemon tile = version
@Test fun theme_buttons_and_depth_slider_write_meta(); // "Soft Putty" click → app.themeMode=="soft"; slider → clayDepth updated
@Test fun lan_enable_without_keys_prompts_creation();  // lan off→on with gatewayKeys empty → key-creation dialog shown, config NOT yet saved with lan=true  (Review Focus 4)
@Test fun lan_enable_with_keys_saves_and_restarts();   // keys present → saveConfigAndRestart called
@Test fun port_change_validates();                     // "abc" or 0 → error text, no save; "3000" → save + restart
@Test fun timeout_change_validates_and_saves();        // request-timeout input "0"/"abc" → error, no save; "15" (minutes) → request_timeout_ms==900000 saved + restart  (spec Settings scope: timeout)
@Test fun segera_controls_are_disabled();              // Cloudflare Quick Tunnel, Periksa Update, Hibernate: isEnabled=false AND label contains "segera"
@Test fun export_confirms_before_writing_secrets();    // "Ekspor Cadangan" → confirm dialog appears BEFORE SAF picker  (Review Focus 7)
@Test fun restore_rejects_invalid_json();              // fake picker returns garbage → error toast, config unchanged
```

- [ ] **Step 2: Push test → CI RED**
- [ ] **Step 3: Implement** — sections per mockup: header card (`Pengaturan Gateway`, real `Layanan Aktif/Matil`, version pill; 3 quick tiles: UPTIME real, ROUTING real success %, DAEMON = version); `Tema & Estetika 3D Clay` (3-button `OLED Clay / Soft Putty / Sistem OS`, `Kedalaman Efek Clay` slider 10–100 with `%` badge, `High Contrast OLED Clay` toggle — all write AppMeta, theme applies live); `Daemon & Background Android` (rows: `Jalankan Saat Boot Android` → bootStart; `Wake Lock Android` → wakeLock; `Port Gateway HTTP/gRPC` numeric input + `Ubah` (validate 1..65535, save+restart); `Timeout Permintaan` numeric input in minutes + `Ubah` (validate ≥1 min → `request_timeout_ms = minutes × 60000`, save+restart — spec Settings scope); `Akses Jaringan Lokal (LAN)` → the key-gated flow: enabling with no keys opens `Buat Gateway API Key` dialog (auto-generate `sk-omni-` + 24 random chars, save keys+lan together, show once with `Salin`), with keys → save+restart; `Cloudflare Quick Tunnel` disabled + `segera`); `Pemeliharaan & Data` (pill rows: `Bersihkan Cache Token & Web` with real `AppCache.size()` → clear → toast; `Ekspor Cadangan Konfigurasi` → confirm dialog → SAF create-document → write wrapper JSON; `Pulihkan dari Cadangan` (spec addition, absent in mockup) → SAF open → parse+`validate()` → save → prompt restart; `Periksa Update OmniRoute` disabled + `segera`; `Hibernate` disabled + `segera`); toast `Pengaturan berhasil diperbarui` on any successful save. All copy in `strings.xml`.
- [ ] **Step 4: Push impl → CI GREEN**
- [ ] **Step 5: Commit** `feat(ui): Settings screen with theme, LAN key gating and backup`

---

### Task 9: Local claymorphism dashboard HTML (served to the WebView tab)

**Files:**
- Modify: `dashboard/index.html` (replace the Task-13 placeholder)
- Test: `core/tests/dashboard_ui_test.rs`

**Interfaces:**
- Consumes: `GET /health` (loopback, no key — dashboard rule from core Task 13).
- Produces: a single self-contained page — dark `#0B1326` clay style (hand-written inline `<style>`, no Tailwind/CDN), Indonesian labels, live values filled by `fetch('/health')`.

- [ ] **Step 1: Write the failing test** (`dashboard_ui_test.rs`)

```rust
#[tokio::test] async fn dashboard_is_self_contained();   // GET /dashboard/ → 200; body has NO "src=\"http", NO "href=\"http", NO "cdn." (spec decision 5: bundled, no CDN)
#[tokio::test] async fn dashboard_reads_health_endpoint(); // body contains exactly the string fetch('/health') and "uptime_seconds"
#[tokio::test] async fn dashboard_copy_is_indonesian();   // body contains "Permintaan" and "Token" (labels)
```

- [ ] **Step 2: Run → FAIL** (`cargo test --manifest-path core/Cargo.toml --test dashboard_ui_test`)
- [ ] **Step 3: Implement `dashboard/index.html`** — one file: keep `<!DOCTYPE html>` (the existing `dashboard_test` from the core plan asserts it), `<style>` with clay classes (rounded cards, inset bevels, cyan accents, JetBrains-Mono-ish `ui-monospace` stack — NO webfont download), sections mirroring the webview mockup's inner content that makes sense as a dashboard: status header (`Layanan Aktif` + version + uptime), metric tiles (Permintaan, Total Token, Latensi, Kesehatan — same formulas as the Service screen: req/min needs two samples → JS keeps `lastTotal` + `lastTs`), provider grid (name, sehat/gagal, latensi, permintaan), and the combo list. JS: `fetch('/health')` every 2s, render `—` for missing data. No external requests of any kind. **No live-log section** — spec decision 5 puts live logs on the Service screen (native), not the dashboard.
- [ ] **Step 4: Run → PASS + full suite** → `commit -m "feat(dashboard): self-contained claymorphism dashboard reading live health"`.
- [ ] **Step 5: Push → CI green (both workflows).**

---

## Definition of Done (this plan)

1. All 5 tabs + Settings render in claymorphism with Indonesian copy, verified by Robolectric tests in CI; Rust additions green locally and in `core.yml`.
2. Every displayed figure comes from `/health`, config, or an explicit manual limit — `—` otherwise; every non-functional control shows "segera" and stays off.
3. End-to-end (manual, on device): daemon toggle works from Service; WebView shows the live dashboard; provider ping returns real latency; simulator completes a real completion; LAN toggle forces key creation; theme/depth/high-contrast apply live; backup export/restore round-trips.
4. Whole-branch review (subagent-driven-development) passes before this branch is merged.
