# OmniRoute Android — Scaffold, Bridge, Service & APK CI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An installable arm64 APK built on GitHub Actions that runs the real Rust gateway in-process (JNI bridge), as a foreground service with boot start, with a signed release path.

**Architecture:** Gradle/Compose app module (`app/`) hosts a thin JNI bridge crate (`bridge/`, cdylib) that drives a new `core::lifecycle::ServerHandle` (thread + tokio runtime + graceful shutdown). The app owns an encrypted config file (Android Keystore AES-256-GCM) and passes its `core` JSON to the bridge at start. All Android compilation/tests run in CI — this machine (Alpine ARM64) has no JDK/SDK and cannot build Android.

**Tech Stack:** Kotlin 2.0.21, AGP 8.7.3, Gradle 8.9 (CI-installed, no wrapper jar committed), Compose BOM 2024.10.01, Robolectric 4.14.1, kotlinx-serialization-json 1.7.3, Rust (existing `core/`) + `jni = "=0.21.1"` + cargo-ndk + NDK r26c.

**Spec:** `docs/superpowers/specs/2026-11-04-omniroute-android-design.md` (binding — the plan argues from it)

## Global Constraints

- minSdk **26**, targetSdk **35**, applicationId **`com.omniroute.app`**, versionCode 1, versionName `0.1.0`; **arm64-v8a only** in v1.
- UI copy in Indonesian (`res/values/strings.xml`); code, keys, commit messages in English.
- **No local Android build is possible** on this machine. Rust code is verified locally with `cargo test` only; every Kotlin/Gradle change is verified **by CI** (two-push TDD: push test → confirm CI red → push impl → confirm CI green).
- Kotlin TDD RED/GREEN command (poll until `status: completed`):
  `curl -s -H "Authorization: token $(sed -n 's|.*x-access-token:\([^@]*\)@.*|\1|p' ~/.git-credentials)" "https://api.github.com/repos/arka9890/Arkaroute/actions/runs?head_sha=$(git rev-parse HEAD)"`
- Local Rust: `source $HOME/.cargo/env` first; on incremental-cache ICE/GC noise re-run with `CARGO_INCREMENTAL=0` and say so; if `/etc/ld-musl-aarch64.path` gets overwritten (missing-.so errors), restore it to `/lib`, `/usr/local/lib`, `/usr/lib`, `/opt/arkacode/lib` (one per line).
- Branch `dev` only, conventional commits, push `origin dev`, never `main`. No third-party analytics/telemetry.
- Pinned toolchain versions (do not drift): Gradle **8.9** via `gradle/actions/setup-gradle@v4` with `gradle-version: "8.9"` (NO gradle wrapper jar committed), AGP **8.7.3**, Kotlin **2.0.21**, Compose BOM **2024.10.01**, Robolectric **4.14.1**, kotlinx-serialization-json **1.7.3**, `jni = "=0.21.1"`, NDK **r26c** via `nttld/setup-ndk@v1`, `cargo install cargo-ndk --locked`.
- Core crate gains **no new dependencies**; `bridge/` carries its own.
- Security invariants (spec 1A): default bind `127.0.0.1`; `lan_enabled=true` requires ≥1 gateway key — `GatewayConfig::validate()` already rejects this, and the bridge must run `validate()` on every start (defense in depth).

## Review Focus

Failure modes the spec implies that no happy-path test covers — each pinned to the task that owns it:

1. **Port already in use when the service starts** (stale socket / second instance) → start must return a structured error the service shows in a notification, never a crash → pinned in Task 3 (`start_on_busy_port_returns_error`).
2. **Config file corrupt or undecryptable** (Keystore key lost after app-data clear/restore) → load returns defaults plus a `.corrupt` backup file; boot must not crash-loop → Task 4 (`corrupt_config_backed_up_and_reset_to_defaults`).
3. **Concurrent double-start** (BootReceiver + MainActivity both firing) → bridge singleton under a `Mutex`; second start returns `already_running` → Task 3 (`start_twice_returns_already_running`).
4. **Rapid daemon off→on toggle** → shutdown must join the server thread so the port is free for immediate rebind → Task 2 (`restart_after_shutdown_rebinds_same_port`).
5. **JNI panics crossing FFI** (abort on device, no stack trace) → every exported fn catches unwind and returns `{"ok":false,...}`; no `unwrap` on external input → enforced by review of Task 3's diff + `invalid_json_returns_error`.
6. **LAN enabled without a gateway key** reaching the server → Task 3 (`lan_without_key_rejected`) pins the bridge-level rejection.

---

### Task 1: Gradle scaffold + first APK CI

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `.gitignore`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/omniroute/app/MainActivity.kt`
- Create: `app/src/test/java/com/omniroute/app/MainActivityTest.kt`
- Create: `.github/workflows/apk.yml`

**Interfaces:**
- Produces: `MainActivity` (class `com.omniroute.app.MainActivity`, currently showing the placeholder title); CI workflow `apk` running `:app:testDebugUnitTest` + `:app:assembleDebug` and uploading `app-debug.apk`.

- [ ] **Step 1: Write the failing test** (`app/src/test/java/com/omniroute/app/MainActivityTest.kt`)

```kotlin
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    @get:Rule val compose = createComposeRule()
    @Test fun mainActivity_shows_title() {
        compose.setContent { MainActivity() }
        compose.onNodeWithText("OmniRoute Gateway").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Push the test alone; confirm CI RED**

Commit `test: scaffold smoke test for MainActivity`, push, poll the CI command from Global Constraints. Expected: run for that SHA **failed** at `:app:testDebugUnitTest` (module does not exist yet — the whole workflow may also fail at the Gradle step; RED is any red before the impl exists). Record the run id as RED evidence.

- [ ] **Step 3: Create the Gradle project**

`settings.gradle.kts`: `rootProject.name = "Arkaroute"`; `include(":app")`; `pluginManagement`/`dependencyResolutionManagement` repos `google()`, `mavenCentral()`, `gradlePluginPortal()` (FAIL_ON_PROJECT_REPOS mode).
Root `build.gradle.kts`: plugins `com.android.application` 8.7.3, `org.jetbrains.kotlin.android` 2.0.21, `org.jetbrains.kotlin.plugin.compose` 2.0.21, `org.jetbrains.kotlin.plugin.serialization` 2.0.21 — all `apply false`.
`app/build.gradle.kts`: plugins `com.android.application`, `org.jetbrains.kotlin.android`, `org.jetbrains.kotlin.plugin.compose`, `org.jetbrains.kotlin.plugin.serialization` (all from the root's `apply false` declarations), namespace/applicationId `com.omniroute.app`, compileSdk 35, minSdk 26, targetSdk 35, `versionCode = 1`, `versionName = "0.1.0"`, `ndk { abiFilters += "arm64-v8a" }`, JVM target 17 (compileOptions + kotlinOptions), `buildFeatures { compose = true }`, dependencies: `androidx.activity:activity-compose`, `androidx.compose:compose-bom:2024.10.01` (ui, material3, ui-tooling-preview + debug), `androidx.lifecycle:lifecycle-runtime-ktx`, `org.robolectric:robolectric:4.14.1`, `androidx.compose.ui:ui-test-junit4`, `junit:junit:4.13.2`, and `testOptions { unitTests { isIncludeAndroidResources = true } }`.
`gradle.properties`: `android.useAndroidX=true`, `org.gradle.jvmargs=-Xmx2g`.
Root `.gitignore`: `.gradle/`, `app/build/`, `local.properties`, `app/src/main/jniLibs/`, `.kotlin/`.
Manifest: `<application android:label="@string/app_name" android:theme="@android:style/Theme.Material.NoActionBar">` with `MainActivity` exported + launcher intent; `res/values/strings.xml` with `app_name` = `OmniRoute` (Indonesian copy grows in later tasks).
`MainActivity`: `class MainActivity : ComponentActivity() { onCreate { setContent { Text("OmniRoute Gateway") } } }` — placeholder only; the shell comes from the UI plan.

- [ ] **Step 4: Create `.github/workflows/apk.yml`**

```yaml
name: apk
on: { push: {}, pull_request: {} }
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "17" }
      - uses: gradle/actions/setup-gradle@v4
        with: { gradle-version: "8.9" }   # wrapper-less pin; if this action rejects the input, download https://services.gradle.org/distributions/gradle-8.9-bin.zip directly and add its bin/ to $GITHUB_PATH
      - run: gradle :app:testDebugUnitTest :app:assembleDebug
      - uses: actions/upload-artifact@v4
        with: { name: app-debug, path: app/build/outputs/apk/debug/*.apk }
```

- [ ] **Step 5: Push impl; confirm CI GREEN**

Commit `feat(app): Gradle Compose scaffold with APK CI`, push, poll CI: run must be **success**, artifact `app-debug` present (`GET .../actions/artifacts` shows it). This is GREEN evidence for Step 1's test.

- [ ] **Step 6: Commit** (already pushed in Step 5; if split, ensure conventional messages).

---

### Task 2: `core::lifecycle` server handle (host TDD)

**Files:**
- Create: `core/src/lifecycle.rs`, `core/tests/lifecycle_test.rs`
- Modify: `core/src/lib.rs` (add `pub mod lifecycle;`)

**Interfaces:**
- Produces: `pub struct ServerHandle`; `ServerHandle::start(cfg: GatewayConfig) -> Result<ServerHandle, LifecycleError>` (binds and serves BEFORE returning; `Err` if bind/addr fails); `handle.local_addr() -> SocketAddr`; `handle.shutdown(self)` (blocks until the listener thread exits — port guaranteed free on return). `LifecycleError` via `thiserror`: `Bind(String)`.

- [ ] **Step 1: Write the failing tests** (`core/tests/lifecycle_test.rs`)

```rust
#[test] fn start_binds_and_serves_health();
// cfg: bind "127.0.0.1", port 41011 → start → GET http://127.0.0.1:41011/health → 200, body contains "status"
#[test] fn shutdown_stops_listener();
// same port 41012 → after shutdown() returns, a new GET must fail (connection refused)
#[test] fn restart_after_shutdown_rebinds_same_port();
// port 41013: start → shutdown → start again with same cfg → Ok, health200 (Review Focus #4)
#[test] fn invalid_bind_address_errors();
// cfg.bind = "not-an-ip" → start returns Err(LifecycleError::Bind(_))
```

Use `reqwest::blocking`-free pattern consistent with the repo: these are sync `#[test]`s; run the GETs on a tiny `tokio::runtime::Runtime` you create inside the test, or use `std::net::TcpStream` + manual HTTP/1.1 `GET /health HTTP/1.1` — pick whichever the repo's existing tests already do (check `core/tests/` first).

- [ ] **Step 2: Run to verify failure**

Run: `source $HOME/.cargo/env && cargo test --manifest-path core/Cargo.toml --test lifecycle_test`
Expected: FAIL — `lifecycle` module not found.

- [ ] **Step 3: Implement `core/src/lifecycle.rs`**

Approach (the one choice the signature leaves open — thread + shutdown wiring):
```rust
pub fn start(cfg: GatewayConfig) -> Result<ServerHandle, LifecycleError> {
    let addr: SocketAddr = format!("{}:{}", cfg.bind, cfg.port)
        .parse().map_err(|e| LifecycleError::Bind(e.to_string()))?;
    let (tx, rx) = std::sync::mpsc::channel();
    let notify = tokio::sync::Notify::new();
    let thread = std::thread::spawn(move || {
        let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        rt.block_on(async move {
            let listener = match tokio::net::TcpListener::bind(addr).await { Ok(l) => l, Err(e) => { let _ = tx.send(Err(e.to_string())); return; } };
            let _ = tx.send(Ok(listener.local_addr().unwrap()));
            let router = crate::server::build_router(make_state(&cfg));   // state built from cfg like main.rs does
            let _ = axum::serve(listener, router.into_make_service_with_connect_info::<SocketAddr>())
                .with_graceful_shutdown(async move { notify.notified().await }).await;
        });
    });
    let bound = rx.recv().map_err(|_| LifecycleError::Bind("server thread died".into()))?
        .map_err(LifecycleError::Bind)?;
    Ok(ServerHandle { notify, thread: Some(thread), local_addr: bound })
}
```
`shutdown(mut self)` calls `notify.notify_one()` then `thread.join()` (unwrap the Option once). Build `AppState` exactly as `main.rs` does (config RwLock + `Stats::new()` + reqwest client with `request_timeout_ms` timeout). Do NOT touch `main.rs`.

- [ ] **Step 4: Run to verify pass**

Run: same as Step 2, then full `cargo test --manifest-path core/Cargo.toml` (with `CARGO_INCREMENTAL=0` on sandbox noise).
Expected: all pass, existing 18+ binaries still green.

- [ ] **Step 5: Commit**

```bash
git add core/src/lifecycle.rs core/src/lib.rs core/tests/lifecycle_test.rs
git commit -m "feat(core): ServerHandle lifecycle with graceful shutdown"
```

---

### Task 3: JNI bridge crate + Kotlin `NativeGateway` + CI packaging checks

**Files:**
- Create: `bridge/Cargo.toml`, `bridge/src/api.rs`, `bridge/src/lib.rs`
- Create: `app/src/main/java/com/omniroute/app/bridge/NativeGateway.kt`
- Modify: `.github/workflows/apk.yml` (Rust .so build + symbol/packaging checks), `.github/workflows/core.yml` (also test `bridge/`)

**Interfaces:**
- Consumes: `core::lifecycle::ServerHandle` (Task 2), `GatewayConfig::{load,validate,Default}`.
- Produces (Kotlin): `object NativeGateway { external fun startServer(configJson: String): String; external fun stopServer(): String; external fun isRunning(): Boolean }` — results are JSON strings `{"ok":true,...}` / `{"ok":false,"error":"..."}`; load via `System.loadLibrary("omniroute_bridge")` in its `init`. Kotlin must NOT touch this object in unit tests (JVM has no .so) — later tasks inject a `GatewayBridge` interface.

- [ ] **Step 1: Write the failing host tests** (`bridge/src/api.rs`, `#[cfg(test)] mod tests`)

```rust
fn start_twice_returns_already_running();      // valid cfg port 41021 → start ok; second start → Err containing "already_running"
fn stop_reports_was_running();                 // start → stop → {"was_running":true}; stop again → {"was_running":false}
fn invalid_json_returns_error();               // start("{not json") → Err containing "invalid_config"   (Review Focus #5)
fn lan_without_key_rejected();                 // start(r#"{"lan_enabled":true}"#) → Err containing "LAN"  (Review Focus #6)
fn start_on_busy_port_returns_error();         // std::net::TcpListener::bind("127.0.0.1:41022") first → start → Err containing "in use" or "Bind"  (Review Focus #1)
fn health_after_start_is_ok();                 // start port 41023 → GET /health → "status"
```
Give each test its own port (41021-41026). These tests share the process-wide `SERVER` singleton, so they must NOT run concurrently: guard every one of them with `let _g = TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner());` where `static TEST_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());` (the poison-recovery pattern matters — a failed test must not cascade). `Cargo.toml`: `crate-type = ["cdylib"]`, deps `omniroute-core = { path = "../core" }`, `jni = "=0.21.1"`, `serde_json = "1"`.

- [ ] **Step 2: Run to verify failure**

Run: `source $HOME/.cargo/env && cargo test --manifest-path bridge/Cargo.toml`
Expected: FAIL — crate/files do not exist yet.

- [ ] **Step 3: Implement `bridge/src/api.rs` (host-testable core)**

```rust
pub fn start(config_json: &str) -> Result<(), String>  // parse GatewayConfig (serde_json) → validate() → map err to "invalid_config: …"/"LAN …" strings → lock singleton Mutex<Option<ServerHandle>> → if Some → Err("already_running") → ServerHandle::start → store
pub fn stop() -> Result<bool, String>                   // take() from singleton → shutdown() → Ok(was_running)
pub fn is_running() -> bool                             // singleton is_some()
```
Singleton: `static SERVER: std::sync::Mutex<Option<ServerHandle>> = std::sync::Mutex::new(None);` (const-init works for `Mutex<Option<T>>` with `const fn new` — if not, use `OnceLock<Mutex<…>>`). A poisoned-lock must not panic the JNI layer: recover with `lock().unwrap_or_else(|p| p.into_inner())`.

- [ ] **Step 4: Implement `bridge/src/lib.rs` (thin JNI shim)**

Three `#[no_mangle] pub extern "system" fn` exports with exact names:
`Java_com_omniroute_app_bridge_NativeGateway_startServer(env: JNIEnv, _class: JClass, config_json: JString) -> jstring`, `..._stopServer(env, _class) -> jstring`, `..._isRunning(env, _class) -> jboolean`.
Each body: read the JString → call `api::…` → serialize result with `serde_json::json!` → `env.new_string(...)`; **every path** wrapped so a panic becomes `{"ok":false,"error":"internal"}` (catch at the boundary — `std::panic::catch_unwind` around the api call, remembering `JNIEnv` is not `UnwindSafe`: use `AssertUnwindSafe`). No `unwrap`/`expect` on any external input.

- [ ] **Step 5: Kotlin `NativeGateway.kt`**

```kotlin
package com.omniroute.app.bridge
object NativeGateway {
    init { System.loadLibrary("omniroute_bridge") }
    external fun startServer(configJson: String): String
    external fun stopServer(): String
    external fun isRunning(): Boolean
}
```

- [ ] **Step 6: Run host tests to verify pass**

Run: `cargo test --manifest-path bridge/Cargo.toml` (6/6), then `cargo test --manifest-path core/Cargo.toml` full.

- [ ] **Step 7: Extend CI — commit RED first is NOT needed for native packaging (compile is the test); push and verify GREEN**

Append to `apk.yml` build job (before the Gradle step):
```yaml
      - uses: dtolnay/rust-toolchain@stable
        with: { targets: aarch64-linux-android }
      - uses: nttld/setup-ndk@v1
        with: { ndk-version: r26c }
      - run: cargo install cargo-ndk --locked
      - run: cargo ndk -t arm64-v8a -o app/src/main/jniLibs build --manifest-path bridge/Cargo.toml --release
      - name: Check JNI symbols
        run: nm -D app/src/main/jniLibs/arm64-v8a/libomniroute_bridge.so | grep -E 'Java_com_omniroute_app_bridge_NativeGateway_(startServer|stopServer|isRunning)'
      - name: Check .so packaged in APK
        run: unzip -l app/build/outputs/apk/debug/*.apk | grep 'lib/arm64-v8a/libomniroute_bridge.so'
```
(Keep `gradle :app:testDebugUnitTest :app:assembleDebug` after these; `unzip` runs after assemble — order: rust steps → gradle → unzip check → upload.) Add to `core.yml` test job: a second step `cargo test --manifest-path bridge/Cargo.toml`.
Push; poll CI: **success**. The `nm` grep must print exactly 3 symbols; the `unzip` grep must find the `.so`. Record both outputs as evidence.

- [ ] **Step 8: Commit**

```bash
git add bridge/ app/src/main/java/com/omniroute/app/bridge/ .github/workflows/
git commit -m "feat(bridge): JNI bridge for gateway lifecycle with CI packaging checks"
```

---

### Task 4: Encrypted config store (Keystore AES-256-GCM)

**Files:**
- Create: `app/src/main/java/com/omniroute/app/config/CryptoEngine.kt`, `KeystoreCryptoEngine.kt`, `ConfigStore.kt`, `Models.kt`
- Create: `app/src/test/java/com/omniroute/app/config/ConfigStoreTest.kt`

**Interfaces:**
- Produces: `interface CryptoEngine { fun encrypt(plain: ByteArray): ByteArray; fun decrypt(blob: ByteArray): ByteArray }`; `class KeystoreCryptoEngine : CryptoEngine` (alias `omniroute_config`, AES/GCM/NoPadding, random 12-byte IV prepended to ciphertext, key generated on first use in `AndroidKeyStore`); `data class AppMeta(var bootStart: Boolean = true, var wakeLock: Boolean = true, var webviewUrl: String = "http://127.0.0.1:20128/dashboard", var clientKeys: List<ClientKey> = emptyList())`; `data class ClientKey(val label: String, val secret: String)`; typed core mirror `GatewayConfigJson` with `@SerialName` matching core's snake_case exactly (bind, port, lan_enabled, gateway_keys, default_combo, providers, combos, request_timeout_ms, stream_idle_timeout_ms; `ProviderConfigJson(id, kind, base_url, api_key, models)`; `ComboJson(name, steps: List<ComboStepJson(provider, model)>)`); `class ConfigStore(private val filesDir: File, private val crypto: CryptoEngine)` with `fun load(): Pair<GatewayConfigJson, AppMeta>`, `fun save(core: GatewayConfigJson, app: AppMeta)`, `fun coreJson(): String` (serialized `core` object — what the bridge receives). File: `filesDir/config.enc` (wrapper JSON `{"core":{…},"app":{…}}`, encrypted whole), corrupt backup `filesDir/config.corrupt`.

- [ ] **Step 1: Write the failing tests** (`ConfigStoreTest.kt`, Robolectric, `FakeCryptoEngine` = identity/`xor 0x5A` — NEVER the Keystore in tests)

```kotlin
@Test fun missing_file_loads_defaults();        // core defaults: bind 127.0.0.1, port 20128, lan false, empty lists; app defaults per AppMeta; second load is stable
@Test fun save_then_load_roundtrips();          // save(custom port 3000, 1 provider, 1 combo, 1 clientKey) → load equals (JSON roundtrip through kotlinx.serialization)
@Test fun corrupt_config_backed_up_and_reset_to_defaults();  // write garbage bytes to config.enc → load → defaults AND File("config.corrupt") exists with those bytes  (Review Focus #2)
@Test fun coreJson_matches_wire_names();        // coreJson() contains "lan_enabled" and "request_timeout_ms" (snake_case), NOT "lanEnabled"
```

- [ ] **Step 2: Push test alone → CI RED** (same poll command; commit `test(config): encrypted config store tests`)

- [ ] **Step 3: Implement** `Models.kt` (serializers via `kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false }`), `CryptoEngine.kt`, `KeystoreCryptoEngine.kt` (standard `KeyGenParameterSpec` + `Cipher.getInstance("AES/GCM/NoPadding")`, 128-bit tag, IV = first 12 bytes), `ConfigStore.kt` (load: read file → decrypt → parse wrapper → on ANY failure: write raw bytes to `config.corrupt`, return defaults; save: serialize wrapper → encrypt → write).

- [ ] **Step 4: Push impl → CI GREEN**; record run id.

- [ ] **Step 5: Commit** `feat(app): Keystore-encrypted config store with corrupt-file recovery`

---

### Task 5: Foreground service + boot receiver

**Files:**
- Create: `app/src/main/java/com/omniroute/app/service/GatewayService.kt`, `BootReceiver.kt`
- Create: `app/src/test/java/com/omniroute/app/service/BootDecisionTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`, `MainActivity.kt`, `res/values/strings.xml`

**Interfaces:**
- Consumes: `NativeGateway` (Task 3), `ConfigStore` (Task 4).
- Produces: `GatewayService` with actions `ACTION_START` / `ACTION_STOP` (companion consts), started via `Context.startForegroundService`; `BootReceiver`; notification channel id `omniroute_status`. Later tasks (UI plan) start/stop the daemon ONLY through these actions.

- [ ] **Step 1: Write the failing test** (`BootDecisionTest.kt` — pure logic, no native/Android calls)

```kotlin
@Test fun boot_starts_when_enabled();   // BootDecision.shouldStart(meta with bootStart=true) == true
@Test fun boot_skipped_when_disabled(); // bootStart=false == false
```
`BootDecision` is a tiny `object` in `BootReceiver.kt` — keep it free of `NativeGateway` references so JVM tests never trigger `loadLibrary`.

- [ ] **Step 2: Push test → CI RED**

- [ ] **Step 3: Implement**

Manifest additions: permissions `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`; `<service android:name=".service.GatewayService" android:foregroundServiceType="specialUse" android:exported="false">` with `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="local_ai_gateway" />`; `<receiver android:name=".service.BootReceiver" android:exported="false">` with `BOOT_COMPLETED` intent-filter.
`GatewayService`: `onStartCommand` → when action is START: `startForeground(NOTIF_ID, buildNotification("…memulai…"))`, then on a background `ExecutorService` thread call `NativeGateway.startServer(store.coreJson())`; parse result: ok → notification `OmniRoute aktif — {bind}:{port}` (Indonesian, from `strings.xml`); `{"ok":false}` → notification `Gagal memulai: {error}` and `stopForeground(STOP_FOREGROUND_REMOVE)` + `stopSelf()` — never throw out of `onStartCommand`. ACTION STOP → `NativeGateway.stopServer()` on the executor, then remove notification + `stopSelf()`. Return `START_STICKY`. `onDestroy` → best-effort `stopServer()`. Notification channel created in `onCreate` (importance LOW, no sound).
`BootReceiver`: `if (BootDecision.shouldStart(meta)) context.startForegroundService(startIntent)`.
`MainActivity.onCreate`: if `!NativeGateway.isRunning()` → send `ACTION_START` (auto-start on open); on SDK ≥ 33 request `POST_NOTIFICATIONS` via `rememberLauncherForActivityResult` — put the permission request in a `@Composable PermissionsRequest()` called from `setContent` so the test from Task 1 still passes (it only sets content — guard the request so Robolectric doesn't choke: request only when `Build.VERSION.SDK_INT >= 33 && !permission granted`).
`strings.xml`: all service/notification strings in Indonesian.

- [ ] **Step 4: Push impl → CI GREEN**

- [ ] **Step 5: Commit** `feat(app): foreground gateway service with boot start`

⚠️ Runtime behavior (notification appears, boot fires, .so actually loads on device) cannot be proven in CI — it is verified when the human installs the APK (final step of this plan).

---

### Task 6: Release signing + README

**Files:**
- Create: `.github/workflows/keystore.yml`
- Modify: `.github/workflows/apk.yml` (release job), `app/build.gradle.kts` (conditional signing), `README.md`

**Interfaces:**
- Consumes: Tasks 1-5 (a green debug APK).
- Produces: GitHub secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` (spec decision 6); CI artifact `omniroute-release.apk` signed with a stable key.

- [ ] **Step 1: One-shot keystore workflow** (`keystore.yml`, `on: workflow_dispatch`)

Job: setup-java 17 → generate a random 24-char password → `keytool -genkeypair -keystore keystore.jks -alias omniroute -keyalg RSA -keysize 2048 -validity 10000 -storepass "$PW" -dname "CN=OmniRoute"` → `base64 -w0 keystore.jks > keystore.b64` → `upload-artifact` with `keystore.b64` + `passwords.txt` (password + alias) → **do not** echo the password into the job log.
Push, dispatch the workflow via API, download the artifact, then create the three repo secrets via `PUT /repos/arka9890/Arkaroute/actions/secrets/{name}` using the stored PAT. If the PAT lacks secret-write permission: stop and report DONE_WITH_CONCERNS with the exact curl the human must run to add the secrets manually (the artifact holds the values). ⚠️ Run this workflow **once** — re-running rotates the key and breaks update-in-place.

- [ ] **Step 2: Conditional release signing** (`app/build.gradle.kts`)

```kotlin
val ksB64 = System.getenv("KEYSTORE_BASE64")
android.signingConfigs.create("release") {
    if (ksB64 != null) {
        storeFile = file(createTempFile("ks", ".jks").apply { writeBytes(Base64.getDecoder().decode(ksB64)) })
        storePassword = System.getenv("KEYSTORE_PASSWORD"); keyAlias = System.getenv("KEY_ALIAS")
    }
}
buildTypes.release { isMinifyEnabled = false; signingConfig = if (ksB64 != null) signingConfigs.getByName("release") else null }
```
(`java.util.Base64` import; if Gradle's config cache complains about the temp file, write to `layout.buildDirectory` instead.)

- [ ] **Step 3: Release job in `apk.yml`**

Second job `release` (needs: build, `if: github.event_name == 'push' && github.ref == 'refs/heads/dev'`): checkout → java → setup-gradle → rust/ndk/cargo-ndk steps (same block as Task 3) → `gradle :app:assembleRelease` with `env: KEYSTORE_BASE64/KEYSTORE_PASSWORD/KEY_ALIAS` from secrets → upload artifact `omniroute-release` (`app/build/outputs/apk/release/*.apk`). When secrets are absent the build yields an unsigned release APK — acceptable; note it in the run summary.

- [ ] **Step 4: Push → CI GREEN** (release job runs; artifact exists — verify via the artifacts API).

- [ ] **Step 5: README**

Add an `## Android (APK)` section: what it does (runs the gateway on-device), where to get the APK (Actions artifacts), install note (arm64-only), first-run behavior (service auto-starts, config encrypted at `config.enc`), and how CI builds it. Keep the existing core sections intact.

- [ ] **Step 6: Commit** `ci: signed release APK pipeline and Android README section`

---

## Definition of Done (this plan)

1. CI green on `dev` for both workflows; `app-debug` and `omniroute-release` artifacts exist.
2. All Rust tests green locally (core + bridge).
3. The human installs `omniroute-release.apk` on an arm64 phone: app opens, foreground notification shows the gateway running, `http://<phone-ip>:20128/health` answers from a PC on the same LAN only after LAN+key are configured (UI plan), and reboot restarts the service.
4. UI screens are intentionally NOT in this plan — they are the second plan (`2026-11-06-omniroute-compose-ui.md`), which consumes `GatewayService` actions, `ConfigStore`, and `NativeGateway` exactly as pinned above.
