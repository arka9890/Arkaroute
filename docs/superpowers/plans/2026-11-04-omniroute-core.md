# OmniRoute Core Gateway — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `omniroute-core`, a standalone Rust crate that runs a real OpenAI+Anthropic-compatible AI gateway with combo fallback, API-key auth, quota stats and a dashboard endpoint — runnable with `cargo run` and fully tested with `cargo test`, so later plans can embed it in the Android app.

**Architecture:** One Rust library crate plus a thin CLI binary. Incoming requests are normalized into a **canonical OpenAI-shaped chat request**; provider adapters turn that into whatever the upstream speaks (OpenAI-compatible passthrough, or Anthropic `/v1/messages` with request+stream translation), and emit canonical events back. The Anthropic `/v1/messages` endpoint is an edge translation in the other direction. Routing resolves a model name into a plan of attempts and walks the fallback chain under a strict "never re-run after the client has seen bytes" rule.

**Tech Stack:** Rust 1.99 / edition 2021, axum 0.8, tokio, reqwest (rustls only), serde/serde_json, async-trait, async-stream, futures-util, thiserror, tracing, rust-embed 8.

**Spec:** `docs/superpowers/specs/2026-11-04-omniroute-android-design.md` — read it before starting; this plan argues from it.

## Global Constraints

- Crate lives at `core/`, package name `omniroute-core`, binary name `omniroute`, edition 2021.
- **TLS = rustls only.** `reqwest` is built with `default-features = false, features = ["json", "stream", "rustls", "http2"]` (reqwest 0.13 names the feature `rustls`, not `rustls-tls`). Never add openssl/native-tls (Android has no OpenSSL).
- Defaults: `bind = "127.0.0.1"`, `port = 20128`, `lan_enabled = false`.
- **Auth rule (exact):** if `gateway_keys` is non-empty, a valid `Authorization: Bearer <key>` is required on `/v1/*` and `/health` **regardless of peer**. If `gateway_keys` is empty, those routes are allowed **only** from a loopback peer, otherwise 401. `/dashboard/*` is always allowed from loopback; from a non-loopback peer it follows the same rule as `/v1/*`. `GatewayConfig::validate()` errors when `lan_enabled && gateway_keys.is_empty()`.
- Canonical internal format = OpenAI chat completion. Both public endpoints exist: `POST /v1/chat/completions` and `POST /v1/messages`.
- Unknown request/response fields are preserved with `#[serde(flatten)] extra: serde_json::Map<String, serde_json::Value>` so passthrough never drops provider-specific parameters.
- Timeouts: total request `request_timeout_ms` default `600_000`; stream idle (gap between upstream SSE chunks) `stream_idle_timeout_ms` default `60_000`.
- Anthropic upstream requires `max_tokens`: default `4096` when the canonical request omits it.
- **Fallback rule:** advance to the next attempt only for `Timeout`, `Network`, HTTP 429 or HTTP 5xx, **and only before the first byte has been written to the client**. 400/401/404 and any mid-stream failure surface immediately, no second provider.
- Local verification is `cargo test --manifest-path core/Cargo.toml` only. APK builds happen in CI in a later plan.
- Conventional commits; push at the end of every task (credentials already stored in `~/.git-credentials`).

## Review Focus

Inputs/conditions the spec implies but no single happy-path test covers. Each line names the task that owns it and the test that pins it.

1. **Upstream accepts the request then stalls mid-stream** — a client must never hang forever. Owner: Task 8 (adapter) + Task 11 (endpoint). Test: mock upstream sends headers and one chunk then sleeps past `stream_idle_timeout_ms`; assert the client stream terminates with an error event within the timeout and the second combo step is not called.
2. **Provider returns 400 (bad request)** — retrying a malformed request against another provider wastes quota and hides the real error. Owner: Task 10. Test: step 1 returns 400 → client receives that 400 body, mock step 2 hit count stays 0.
3. **Upstream dies after emitting chunks** — restarting the chain would send the user a second, duplicated answer. Owner: Task 10. Test: step 1 streams 2 chunks then closes with error → client stream ends with `error`, mock step 2 hit count stays 0.
4. **LAN enabled without a key = open proxy to paid provider keys** — Owner: Task 5. Test: `authorize()` with `lan_enabled=true`, `gateway_keys=["k"]`, no header → 401; and `GatewayConfig::validate()` rejects `lan_enabled=true` with empty keys.
5. **Model-name ambiguity** — a combo named like a provider model, or a bare unknown name. Owner: Task 10. Test: precedence `combo/…` prefix > exact combo name > `provider/model` > `default_combo` > 404, asserted for all five cases.

---

## File Structure

```
core/
  Cargo.toml                  package omniroute-core, bin omniroute
  src/lib.rs                  module tree + pub const VERSION
  src/main.rs                 CLI: --bind/--port/--config, loads config, serves
  src/config.rs               GatewayConfig, ProviderConfig, Combo, load/save/validate
  src/error.rs                ConfigError, AuthError, ResolveError, RouteError, UpstreamError
  src/state.rs                AppState, Stats, StatsSnapshot, LogEntry, LogLevel
  src/auth.rs                 authorize()
  src/sse.rs                  SseEvent, incremental parser, formatter
  src/protocol/mod.rs         canonical OpenAI-shaped types
  src/protocol/anthropic.rs   Anthropic wire types (request + streaming events)
  src/translate/mod.rs        request mapping: o2a (outbound) and a2o (inbound)
  src/translate/stream.rs     stream mapping: a2o (upstream→canonical), o2a (canonical→client)
  src/providers/mod.rs        ProviderAdapter trait + registry
  src/providers/openai_compat.rs
  src/providers/anthropic.rs
  src/routing.rs              resolve_model, plan, execute (fallback walk)
  src/server.rs               build_router + handlers
  src/dashboard.rs            rust-embed asset serving
  tests/*.rs                  integration tests (real sockets, mock upstream axum servers)
dashboard/                    HTML assets served at /dashboard (placeholder in this plan)
.github/workflows/core.yml    cargo test on push/PR
```

---

### Task 1: Scaffold crate + CI

**Files:**
- Create: `core/Cargo.toml`, `core/src/lib.rs`, `core/src/main.rs`, `core/tests/smoke.rs`
- Create: `.github/workflows/core.yml`

**Interfaces:**
- Produces: `omniroute_core::VERSION: &str` (used by `/health` in Task 7); module declarations for every later task; `core/` as the crate root.

- [ ] **Step 1: Write the failing test**

`core/tests/smoke.rs`:
```rust
#[test]
fn reports_package_version() {
    assert_eq!(omniroute_core::VERSION, env!("CARGO_PKG_VERSION"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `. "$HOME/.cargo/env" && cargo test --manifest-path core/Cargo.toml`
Expected: FAIL — unresolved `omniroute_core` (crate does not exist yet).

- [ ] **Step 3: Create `core/Cargo.toml` and `core/src/lib.rs`**

`Cargo.toml`: name `omniroute-core`, version `0.1.0`, edition `2021`, `[lib] name = "omniroute_core"`, `[[bin]] name = "omniroute" path = "src/main.rs"`.
Dependencies (pinned): `axum = "0.8"`, `tokio = { version = "1", features = ["full"] }`, `reqwest = { version = "0.13", default-features = false, features = ["json", "stream", "rustls", "http2"] }`, `serde = { version = "1", features = ["derive"] }`, `serde_json = "1"`, `async-trait = "0.1"`, `async-stream = "0.3"`, `futures-util = "0.3"`, `thiserror = "2"`, `tracing = "0.1"`, `tracing-subscriber = { version = "0.3", features = ["env-filter"] }`, `rust-embed = "8"`, `bytes = "1"`.
`lib.rs`: `pub mod …` for every module in File Structure (empty stub files so the tree compiles), plus `pub const VERSION: &str = env!("CARGO_PKG_VERSION");`.
`main.rs`: prints `omniroute {VERSION}` for now.

- [ ] **Step 4: Run test to verify it passes**

Run: `cargo test --manifest-path core/Cargo.toml`
Expected: PASS `reports_package_version`.

- [ ] **Step 5: Add CI workflow and push**

`.github/workflows/core.yml`: on `push`/`pull_request` → `ubuntu-latest`, `dtolnay/rust-toolchain@stable`, `Swatinem/rust-cache@v2`, run `cargo test --manifest-path core/Cargo.toml`.
```bash
git add core .github && git commit -m "chore: scaffold omniroute-core crate with CI test workflow" && git push -u origin main
```

- [ ] **Step 6: Verify CI goes green**

Run: `curl -s -H "Authorization: token $(sed -n 's|.*x-access-token:\([^@]*\)@.*|\1|p' ~/.git-credentials)" "https://api.github.com/repos/arka9890/Arkaroute/actions/runs?per_page=1"`
Expected: a run exists for the pushed SHA; poll until `conclusion == "success"` (the token's `workflow` scope may be missing — if the API rejects it, confirm green by opening the Actions tab).

---

### Task 2: Config model, persistence, validation

**Files:**
- Create: `core/src/config.rs`, `core/src/error.rs`, `core/tests/config_test.rs`

**Interfaces:**
- Produces (relied on by every later task):
```rust
pub struct GatewayConfig { pub bind: String, pub port: u16, pub lan_enabled: bool,
    pub gateway_keys: Vec<String>, pub default_combo: Option<String>,
    pub providers: Vec<ProviderConfig>, pub combos: Vec<Combo>,
    pub request_timeout_ms: u64, pub stream_idle_timeout_ms: u64 }
pub struct ProviderConfig { pub id: String, pub kind: ProviderKind,
    pub base_url: String, pub api_key: String, pub models: Vec<String> }
pub enum ProviderKind { OpenAiCompatible, Anthropic }
pub struct Combo { pub name: String, pub steps: Vec<ComboStep> }
pub struct ComboStep { pub provider: String, pub model: String }
impl GatewayConfig {
    pub fn load(path: &Path) -> Result<Self, ConfigError>;
    pub fn save(&self, path: &Path) -> Result<(), ConfigError>;
    pub fn validate(&self) -> Result<(), ConfigError>;
    pub fn provider(&self, id: &str) -> Option<&ProviderConfig>;
    pub fn combo(&self, name: &str) -> Option<&Combo>;
}
```
`ConfigError` variants: `Io`, `Parse(serde_json::Error)`, `Invalid(String)`.

- [ ] **Step 1: Write the failing tests** (`core/tests/config_test.rs`)

```rust
#[test] fn default_config_is_valid_and_loopback();            // Default::default() → validate() Ok, bind "127.0.0.1", port 20128, lan_enabled false
#[test] fn load_save_roundtrip_preserves_combos();            // save() to temp file, load(), assert providers/combos/default_combo identical
#[test] fn validate_rejects_lan_without_keys();               // lan_enabled=true, gateway_keys=[] → Err(Invalid(_)) containing "gateway"
#[test] fn provider_and_combo_lookup_by_id();                 // known id → Some, unknown → None
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cargo test --manifest-path core/Cargo.toml --test config_test`
Expected: FAIL — `config` not found / missing functions.

- [ ] **Step 3: Implement `config.rs` and `error.rs`**

`Default for GatewayConfig` gives the pinned defaults above (timeouts `600_000` / `60_000`). `serde` derive with `#[serde(default)]` on every field so a partial config file loads. `validate()` returns `Invalid("LAN enabled but no gateway key configured")` when `lan_enabled && gateway_keys.is_empty()`; also rejects empty `bind`, `port == 0`, duplicate `providers[].id`, duplicate `combos[].name`, and a `ComboStep` naming a provider that does not exist.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cargo test --manifest-path core/Cargo.toml --test config_test`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/config.rs core/src/error.rs core/tests/config_test.rs core/src/lib.rs
git commit -m "feat(core): gateway config with validation and persistence"
```

---

### Task 3: Canonical protocol types

**Files:**
- Create: `core/src/protocol/mod.rs`, `core/tests/protocol_test.rs`

**Interfaces:**
- Produces (consumed by translate/, providers/, routing/, server/):
```rust
pub struct ChatCompletionRequest { pub model: String, pub messages: Vec<ChatMessage>,
    pub stream: bool, pub max_tokens: Option<u32>, pub temperature: Option<f64>,
    pub top_p: Option<f64>, pub tools: Option<Vec<Tool>>,
    pub tool_choice: Option<serde_json::Value>,
    #[serde(flatten)] pub extra: serde_json::Map<String, serde_json::Value> }
pub struct ChatMessage { pub role: Role, pub content: Option<serde_json::Value>,
    pub tool_calls: Option<Vec<ToolCall>>, pub tool_call_id: Option<String>,
    pub name: Option<String>,
    #[serde(flatten)] pub extra: serde_json::Map<String, serde_json::Value> }
pub enum Role { System, User, Assistant, Tool }
pub struct Tool { pub kind: String /*"function"*/, pub function: FunctionDef }
pub struct FunctionDef { pub name: String, pub description: Option<String>,
    pub parameters: Option<serde_json::Value> }
pub struct ToolCall { pub id: Option<String>, pub r#type: Option<String>,
    pub function: Option<FunctionDefCall> }
pub struct FunctionDefCall { pub name: Option<String>, pub arguments: Option<String> }
```
Plus `ChatCompletionResponse`, `ChatCompletionChunk`, `ApiErrorBody` (OpenAI shape) — plain serde structs with `flatten` extras.

- [ ] **Step 1: Write the failing tests** (`core/tests/protocol_test.rs`)

```rust
#[test] fn deserializes_openai_request_and_keeps_unknown_fields(); // body with "store":true,"metadata":{"a":1} → extra contains both, stream==false
#[test] fn serializes_roundtrip_without_losing_fields();           // parse → to_value → parse, deep-equal on the original value
#[test] fn message_content_may_be_string_or_array();               // content:"hi" and content:[{"type":"text","text":"hi"}] both parse as Value
#[test] fn tool_call_message_parses();                             // assistant message with tool_calls + tool message with tool_call_id
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cargo test --manifest-path core/Cargo.toml --test protocol_test`
Expected: FAIL — module missing.

- [ ] **Step 3: Implement `protocol/mod.rs`**

`Role` uses `#[serde(rename_all = "lowercase")]`. `content` stays `Option<Value>` by design (string or parts array). `ChatCompletionRequest::from_value(v: Value) -> Result<Self, serde_json::Error>` helper for the Anthropic inbound path.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cargo test --manifest-path core/Cargo.toml --test protocol_test`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/protocol core/tests/protocol_test.rs && git commit -m "feat(core): canonical OpenAI-shaped protocol types with lossless extras"
```

---

### Task 4: Stats, log buffer, `/health`

**Files:**
- Create: `core/src/state.rs`, `core/src/server.rs`, `core/tests/health_test.rs`

**Interfaces:**
- Consumes: `VERSION` (Task 1), `GatewayConfig` (Task 2).
- Produces:
```rust
pub struct AppState { pub config: std::sync::Arc<std::sync::RwLock<crate::config::GatewayConfig>>,
    pub stats: std::sync::Arc<Stats>, pub client: reqwest::Client }
pub struct Stats { /* atomics + Mutex, see impl */ }
pub struct StatsSnapshot { pub status: String, pub version: String, pub uptime_seconds: u64,
    pub lan_enabled: bool, pub requests_total: u64, pub requests_failed: u64,
    pub tokens_in: u64, pub tokens_out: u64,
    pub providers: Vec<ProviderSnapshot>, pub combos: Vec<String>,
    pub logs: Vec<LogEntry> }
pub struct ProviderSnapshot { pub id: String, pub healthy: bool, pub requests: u64,
    pub tokens_in: u64, pub tokens_out: u64, pub last_latency_ms: u64, pub last_error: Option<String> }
pub struct LogEntry { pub ts: String /* RFC3339 */, pub level: LogLevel, pub message: String }
impl Stats {
    pub fn record_request(&self); pub fn record_failure(&self, provider: &str, error: &str);
    pub fn record_success(&self, provider: &str, tokens_in: u64, tokens_out: u64, latency_ms: u64);
    pub fn log(&self, level: LogLevel, message: impl Into<String>);
    pub fn snapshot(&self, cfg: &GatewayConfig) -> StatsSnapshot;
}
pub fn build_router(state: AppState) -> axum::Router;   // mounts /health only in this task
```
`GET /health` → `200` JSON of `StatsSnapshot`; `logs` capped at the newest 100 entries.

- [ ] **Step 1: Write the failing test** (`core/tests/health_test.rs`)

Helper `spawn(cfg) -> (SocketAddr, JoinHandle)` binds `127.0.0.1:0`, serves `build_router` with `axum::serve(...).with_connect_info::<SocketAddr>()`.

```rust
#[tokio::test] async fn health_reports_snapshot_shape();
// default config → GET /health → 200; json has status "ok", version == VERSION,
// uptime_seconds >= 0, requests_total == 0, providers == [], lan_enabled == false
#[tokio::test] async fn health_counts_requests_and_logs();
// stats.record_request() x3, record_success("openai", 10, 20, 5), log(LogLevel::Info, "boot")
// → GET /health → requests_total == 3, providers[0].tokens_in == 10, logs[0].message == "boot"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cargo test --manifest-path core/Cargo.toml --test health_test`
Expected: FAIL — `build_router` not found.

- [ ] **Step 3: Implement `state.rs` and `server.rs`**

`Stats` uses `AtomicU64` for counters, `Mutex<HashMap<String, ProviderStats>>` per provider, `Mutex<VecDeque<LogEntry>>` (cap 100, `ts` from `chrono`-free RFC3339 via `std::time::SystemTime` formatted by hand or `time` crate — pick `time = "0.3"` with `formatting` feature if needed; otherwise store unix seconds as number and format in the snapshot). `record_success` also marks `healthy = true`, `record_failure` sets `healthy = false` and keeps `last_error`. `build_router` returns `Router::new().route("/health", get(health_handler))` and `.with_state(state)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cargo test --manifest-path core/Cargo.toml --test health_test`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/state.rs core/src/server.rs core/tests/health_test.rs && git commit -m "feat(core): stats snapshot and /health endpoint"
```

---

### Task 5: Auth

**Files:**
- Create: `core/src/auth.rs`, `core/tests/auth_test.rs`

**Interfaces:**
- Consumes: `GatewayConfig` (Task 2).
- Produces:
```rust
pub enum AuthError { MissingKey, InvalidKey, ForbiddenOrigin }
impl AuthError { pub fn status(&self) -> u16; /* 401, 401, 403 */ }
pub fn authorize(cfg: &GatewayConfig, headers: &axum::http::HeaderMap,
    peer_is_loopback: bool) -> Result<(), AuthError>;
```
Rules exactly as written in Global Constraints.

- [ ] **Step 1: Write the failing tests** (`core/tests/auth_test.rs`)

```rust
#[test] fn valid_bearer_key_is_accepted();
#[test] fn missing_key_with_keys_configured_is_401();          // keys=["k"], no header → Err(MissingKey)
#[test] fn wrong_key_is_401();                                  // bearer "nope" → Err(InvalidKey)
#[test] fn empty_keys_allow_loopback_only();                    // keys=[] + loopback → Ok; keys=[] + !loopback → Err(ForbiddenOrigin)
#[test] fn empty_keys_reject_remote_even_when_lan_off();        // regression: open-proxy guard
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cargo test --manifest-path core/Cargo.toml --test auth_test`
Expected: FAIL — `auth` not found.

- [ ] **Step 3: Implement `auth.rs`**

Case-insensitive `Bearer` scheme compare; key comparison over `gateway_keys` (plain `==` is fine for v1 — keys live in an encrypted DB on device).

- [ ] **Step 4: Run tests to verify they pass**

Run: `cargo test --manifest-path core/Cargo.toml --test auth_test`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/auth.rs core/tests/auth_test.rs && git commit -m "feat(core): gateway key authorization rules"
```

---

### Task 6: SSE parser and formatter

**Files:**
- Create: `core/src/sse.rs`, `core/tests/sse_test.rs`

**Interfaces:**
- Produces (used by every streaming task):
```rust
pub struct SseEvent { pub event: Option<String>, pub data: String }
pub struct SseParser { /* internal buffer */ }
impl SseParser {
    pub fn new() -> Self;
    pub fn push(&mut self, bytes: &[u8]) -> Vec<SseEvent>;   // incremental: handles split lines
    pub fn finish(&mut self) -> Vec<SseEvent>;               // flush trailing event without blank line
}
pub fn format_event(ev: &SseEvent) -> String;                // "event: x\ndata: y\n\n"
pub fn data_only(data: &str) -> String;                      // "data: y\n\n"
pub const DONE: &str = "data: [DONE]\n\n";
```

- [ ] **Step 1: Write the failing tests** (`core/tests/sse_test.rs`)

```rust
#[test] fn parses_single_event();
#[test] fn parses_event_split_across_arbitrary_chunk_boundaries(); // "data: {\"a\":1}\n\n" fed byte-by-byte → 1 event with data {"a":1}
#[test] fn parses_named_event();                                    // "event: message_start\ndata: {...}\n\n" → event Some("message_start")
#[test] fn joins_multi_line_data_with_newline();                    // "data: line1\ndata: line2\n\n" → "line1\nline2"
#[test] fn finish_emits_incomplete_trailing_event();
#[test] fn format_roundtrips();                                     // parse(format_event(e)) == e
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cargo test --manifest-path core/Cargo.toml --test sse_test`
Expected: FAIL — `sse` not found.

- [ ] **Step 3: Implement `sse.rs`**

Split on `\n`, strip a single trailing `\r`, ignore comment lines starting with `:`, blank line dispatches the accumulated event, field syntax `name: value` with one optional leading space removed from value.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cargo test --manifest-path core/Cargo.toml --test sse_test`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/sse.rs core/tests/sse_test.rs && git commit -m "feat(core): incremental SSE parser and formatter"
```

---

### Task 7: Provider adapter trait + OpenAI-compatible adapter

**Files:**
- Create: `core/src/providers/mod.rs`, `core/src/providers/openai_compat.rs`, `core/tests/openai_compat_test.rs`

**Interfaces:**
- Consumes: `ChatCompletionRequest` (Task 3), `SseParser`/`format_event` (Task 6), `GatewayConfig`/`ProviderConfig` (Task 2).
- Produces:
```rust
pub enum UpstreamFailure { Http { status: u16, body: String }, Timeout, Network(String) }
pub struct UpstreamError { pub provider: String, pub failure: UpstreamFailure }
impl UpstreamError { pub fn status(&self) -> u16; /* Http→status, Timeout→504, Network→502 */
                     pub fn is_fallbackable(&self) -> bool; }
pub enum UpstreamOutcome {
    Json(serde_json::Value),                                   // canonical OpenAI response body
    Stream(futures_util::stream::BoxStream<'static,
           Result<crate::sse::SseEvent, UpstreamError>>) }      // canonical events, caller appends DONE
#[async_trait::async_trait]
pub trait ProviderAdapter: Send + Sync {
    async fn chat(&self, req: &ChatCompletionRequest) -> Result<UpstreamOutcome, UpstreamError>;
}
pub fn adapter_for(cfg: &ProviderConfig, client: reqwest::Client, request_timeout_ms: u64) -> Box<dyn ProviderAdapter>;
```
`openai_compat` posts to `{base_url}/chat/completions` with `Authorization: Bearer {api_key}`, `Content-Type: application/json`.

- [ ] **Step 1: Write the failing test** (`core/tests/openai_compat_test.rs`)

Shared helper: spawn a mock axum upstream on `127.0.0.1:0` recording every request body into a `Mutex<Vec<Value>>` and returning a scripted response.

```rust
#[tokio::test] async fn json_response_is_returned_verbatim();
// upstream 200 {"id":"x","choices":[...],"usage":{"prompt_tokens":7,"completion_tokens":3}}
// → Outcome::Json, usage values intact, recorded upstream body.model == requested model
#[tokio::test] async fn stream_is_replayed_as_canonical_events();
// upstream text/event-stream with 2 chunks + "data: [DONE]"
// → collect stream → events [chunk1, chunk2], [DONE] stripped (caller re-adds it)
#[tokio::test] async fn stream_injects_include_usage_and_retries_once_on_400();
// request stream=true, upstream first answers 400 then 200
// → upstream received body with stream_options.include_usage==true first, plain body second, outcome Ok
#[tokio::test] async fn upstream_429_maps_to_fallbackable_error();
#[tokio::test] async fn connection_refused_maps_to_network_failure();  // port with no listener → Network, is_fallbackable()
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cargo test --manifest-path core/Cargo.toml --test openai_compat_test`
Expected: FAIL — `providers` not found.

- [ ] **Step 3: Implement the trait, registry and `openai_compat.rs`**

`adapter_for` matches `ProviderKind`: `OpenAiCompatible → OpenAiCompatAdapter`, `Anthropic → crate::providers::anthropic::AnthropicAdapter` (Task 9; for now that module can return `unimplemented!()` only if Task 9 has not landed — it has not, so match arm 2 is added in Task 9 instead).
Stream path: `reqwest` `bytes_stream()` → `SseParser::push` per chunk → map each `SseEvent` whose `data == "[DONE]"` to end-of-stream (stop, do not forward) → yield events. Wrap the whole call in `tokio::time::timeout(request_timeout_ms)`.
Usage injection: when `req.stream` and the body has no `stream_options`, add `{"include_usage": true}`; if the upstream then answers HTTP 400 **and no event has been yielded yet**, retry once with the injected field removed.

- [ ] **Step 4: Run test to verify it passes**

Run: `cargo test --manifest-path core/Cargo.toml --test openai_compat_test`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/providers core/tests/openai_compat_test.rs && git commit -m "feat(core): OpenAI-compatible provider adapter with SSE passthrough"
```

---

### Task 8: Anthropic outbound translation (canonical → Anthropic, and stream back)

**Files:**
- Create: `core/src/protocol/anthropic.rs`, `core/src/translate/mod.rs`, `core/src/translate/stream.rs`, `core/tests/anthropic_translate_test.rs`

**Interfaces:**
- Consumes: canonical types (Task 3), `SseEvent` (Task 6).
- Produces:
```rust
// translate/mod.rs
pub fn o2a_request(req: &ChatCompletionRequest, model: &str) -> serde_json::Value;
pub fn a2o_response(body: serde_json::Value, model: &str) -> serde_json::Value;
// translate/stream.rs
pub fn a2o_stream(events: Vec<crate::sse::SseEvent>, model: &str)
    -> Vec<crate::sse::SseEvent>;   // Anthropic events → canonical chunks + [DONE]
pub fn o2a_stream(events: Vec<crate::sse::SseEvent>, model: &str)
    -> Vec<crate::sse::SseEvent>;   // canonical chunks → Anthropic events (used by Task 12)
```
(Both stream functions take a `Vec` for testability; the adapters wrap them over an async stream with the same logic per event — see Step 3.)

- [ ] **Step 1: Write the failing tests** (`core/tests/anthropic_translate_test.rs`)

```rust
#[test] fn system_messages_become_system_param();          // [system "be nice", user "hi"] → top-level "system":"be nice", messages only user
#[test] fn missing_max_tokens_defaults_to_4096();
#[test] fn tools_map_to_anthropic_tools_schema();          // tools[{function:{name,description,parameters}}] → tools[{name,description,input_schema}]
#[test] fn tool_result_message_maps_to_user_tool_result(); // role "tool" → user message content [{type:"tool_result",tool_use_id,content}]
#[test] fn assistant_tool_calls_map_to_tool_use_blocks();  // tool_calls → content blocks type "tool_use" with input parsed from JSON string
#[test] fn a2o_stream_emits_canonical_text_chunks();       // message_start + text_delta x2 + message_delta + message_stop → chunks with delta.content, final finish_reason "stop", then [DONE]
#[test] fn a2o_stream_maps_tool_use_to_tool_call_chunks();// content_block_start(tool_use) + input_json_delta → delta.tool_calls[{index,id,function:{name,arguments}}], finish_reason "tool_calls"
#[test] fn a2o_stream_captures_usage_from_message_events();// message_start.usage.input_tokens + message_delta.usage.output_tokens → usage chunk before [DONE]
#[test] fn o2a_stream_emits_anthropic_event_sequence();    // canonical chunks → message_start, content_block_start, content_block_delta, content_block_stop, message_delta, message_stop
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cargo test --manifest-path core/Cargo.toml --test anthropic_translate_test`
Expected: FAIL — `translate` not found.

- [ ] **Step 3: Implement `protocol/anthropic.rs` and `translate/`**

Pinned wire formats are in the **Wire Formats** appendix below — implement exactly those shapes. Stream functions are pure `Vec<SseEvent> → Vec<SseEvent>` so tests stay synchronous; the adapters in Task 9 feed them incrementally by keeping a small state machine (`pending_block_index`, `accumulated_arguments`, `input_tokens`).

- [ ] **Step 4: Run tests to verify it passes**

Run: `cargo test --manifest-path core/Cargo.toml --test anthropic_translate_test`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/protocol/anthropic.rs core/src/translate core/tests/anthropic_translate_test.rs
git commit -m "feat(core): Anthropic request/stream translation with tool calling"
```

---

### Task 9: Anthropic provider adapter

**Files:**
- Create: `core/src/providers/anthropic.rs`
- Modify: `core/src/providers/mod.rs` (`adapter_for` second match arm)
- Test: `core/tests/anthropic_provider_test.rs`

**Interfaces:**
- Consumes: `o2a_request`, `a2o_response`, `a2o_stream` (Task 8), `UpstreamError`/`UpstreamOutcome` (Task 7).
- Produces: `AnthropicAdapter` implementing `ProviderAdapter`, returning canonical `UpstreamOutcome`.

- [ ] **Step 1: Write the failing test** (`core/tests/anthropic_provider_test.rs`)

Mock upstream serving `{base_url}/v1/messages` (note: `base_url` already contains `/v1` for Anthropic providers — pin: Anthropic `base_url` ends with `/v1`, adapter appends `/messages`).

```rust
#[tokio::test] async fn request_body_is_o2a_translated_and_headers_set();
// recorded body has "max_tokens", "system", messages without system role;
// headers: x-api-key, anthropic-version: 2023-06-01, content-type: application/json
#[tokio::test] async fn json_response_becomes_canonical_response();
#[tokio::test] async fn sse_response_becomes_canonical_chunks();   // real Anthropic event fixture in → chunks + [DONE] out
#[tokio::test] async fn upstream_429_is_fallbackable();
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cargo test --manifest-path core/Cargo.toml --test anthropic_provider_test`
Expected: FAIL — `AnthropicAdapter` missing.

- [ ] **Step 3: Implement `providers/anthropic.rs` + registry arm**

Same timeout/failure mapping as Task 7. Response `Content-Type: application/json` → `a2o_response`; `text/event-stream` → parse all events, `a2o_stream`, yield.

- [ ] **Step 4: Run test to verify it passes**

Run: `cargo test --manifest-path core/Cargo.toml --test anthropic_provider_test`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/providers && git commit -m "feat(core): Anthropic provider adapter"
```

---

### Task 10: Routing engine — resolve, plan, execute with fallback

**Files:**
- Create: `core/src/routing.rs`, `core/tests/routing_test.rs`

**Interfaces:**
- Consumes: `GatewayConfig` (Task 2), adapters (Tasks 7, 9), `Stats` (Task 4).
- Produces:
```rust
pub enum ResolvedModel { Combo(String), Direct { provider: String, model: String } }
pub enum ResolveError { UnknownModel(String), UnknownCombo(String), UnknownProvider(String) }
pub fn resolve_model(model: &str, cfg: &GatewayConfig) -> Result<ResolvedModel, ResolveError>;
pub struct Attempt { pub provider: String, pub model: String }
pub fn plan(model: &str, cfg: &GatewayConfig) -> Result<Vec<Attempt>, ResolveError>;
pub struct RouteOutcome { pub response: UpstreamOutcome, pub provider: String,
    pub model: String, pub attempts: Vec<String> /* provider ids tried, in order */ }
pub enum RouteError { Resolve(ResolveError), AllFailed(Vec<UpstreamError>),
    Upstream(UpstreamError) }
pub async fn execute(state: &AppState, req: ChatCompletionRequest)
    -> Result<RouteOutcome, RouteError>;
```

- [ ] **Step 1: Write the failing tests** (`core/tests/routing_test.rs`)

Resolution (pure, no server):
```rust
#[test] fn combo_prefix_wins();                 // "combo/free" with combo "free" → Combo
#[test] fn exact_combo_name_wins_over_provider(); // combo named "fast" + provider model "fast" → Combo
#[test] fn provider_slash_model_resolves_direct(); // "openai/gpt-4o" → Direct
#[test] fn unknown_name_falls_back_to_default_combo(); // default_combo=Some("d") → Combo("d")
#[test] fn unknown_name_without_default_is_404();     // → Err(UnknownModel)
```
Execution with mock upstreams:
```rust
#[tokio::test] async fn combo_walks_to_second_step_on_500();   // step1 → 500, step2 → 200 → Ok, outcome.attempts == ["p1","p2"], stats show p1 failure
#[tokio::test] async fn combo_stops_on_400();                  // step1 → 400 → Err(Upstream), step2 hit count == 0   (Review Focus #2)
#[tokio::test] async fn no_fallback_after_first_chunk();       // step1 streams 2 chunks then errors → Err/Stream error, step2 hit count == 0   (Review Focus #3)
#[tokio::test] async fn all_steps_failed_returns_all_failed(); // both 429 → Err(AllFailed) len 2
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cargo test --manifest-path core/Cargo.toml --test routing_test`
Expected: FAIL — `routing` not found.

- [ ] **Step 3: Implement `routing.rs`**

Resolution precedence exactly as in Review Focus #5. `execute` walks `plan()`, builds an adapter per attempt, calls `chat()`; on `!is_fallbackable()` return `RouteError::Upstream` immediately; on fallbackable try the next attempt; exhausted → `AllFailed`. For `UpstreamOutcome::Stream`, wrap the stream so the **first** yielded item flips a `sent_first_byte` flag inside the attempt loop: an error before the first item allows fallback, after it does not (the wrapper returns the error to the caller instead). Record stats: `record_failure` per failed attempt, `record_success` (with usage parsed from JSON body `usage` or from a stream usage chunk) on success. Log every attempt via `stats.log`.

- [ ] **Step 4: Run tests to verify it passes**

Run: `cargo test --manifest-path core/Cargo.toml --test routing_test`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/routing.rs core/tests/routing_test.rs && git commit -m "feat(core): model resolution and fallback routing engine"
```

---

### Task 11: `/v1/chat/completions` and `/v1/models` endpoints

**Files:**
- Modify: `core/src/server.rs`
- Test: `core/tests/chat_endpoint_test.rs`

**Interfaces:**
- Consumes: `authorize` (Task 5), `execute` (Task 10), `build_router` (Task 4).
- Produces: routes `POST /v1/chat/completions`, `GET /v1/models` added to `build_router`.

- [ ] **Step 1: Write the failing test** (`core/tests/chat_endpoint_test.rs`)

```rust
#[tokio::test] async fn chat_requires_key_when_keys_configured();  // cfg.gateway_keys=["k"], no header → 401 + OpenAI error body
#[tokio::test] async fn models_lists_combo_and_provider_models();  // ids contain "free-stack" and "openai/gpt-4o", object "list"
#[tokio::test] async fn non_stream_returns_openai_json();          // 200, object "chat.completion", choices[0].message.role == "assistant"
#[tokio::test] async fn stream_returns_event_stream_with_done();   // content-type text/event-stream, last frame data: [DONE]
#[tokio::test] async fn unknown_model_is_404_openai_error_shape(); // {"error":{"message":..,"type":"invalid_request_error"}}
#[tokio::test] async fn all_providers_failed_is_502();             // combo both 429 → 502, error.type "server_error"
#[tokio::test] async fn health_requires_key_when_keys_configured(); // Global Constraints: /health is under the same auth rule as /v1/*
#[tokio::test] async fn stalled_upstream_stream_times_out();       // Review Focus #1: cfg.stream_idle_timeout_ms=200, upstream sends 1 chunk then sleeps 2s
                                                                    // → client stream ends with an error frame (no [DONE]) well before the sleep finishes,
                                                                    // and the combo's second step was never called (hit count 0)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cargo test --manifest-path core/Cargo.toml --test chat_endpoint_test`
Expected: FAIL — routes missing (404 from router).

- [ ] **Step 3: Implement handlers in `server.rs`**

Every handler: extract `ConnectInfo<SocketAddr>`, `peer_is_loopback = addr.is_loopback()`, `authorize(...)` mapped to the OpenAI error body with `AuthError::status()`. Parse body into `ChatCompletionRequest` (400 on parse failure), call `execute`. Stream responses use `axum::body::Body::from_stream(...)` over `futures_util::StreamExt::map`, appending the `DONE` frame after the last event; wrap with `tokio::time::timeout(stream_idle_timeout_ms)` per read gap (idle timeout is enforced inside the stream wrapper, error → single `{"error":{...}}` frame then close). `GET /v1/models` builds `data` from `cfg.providers[].models` as `"{provider_id}/{model}"` plus each `combo.name`, `owned_by` = provider id or `"omniroute-combo"`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cargo test --manifest-path core/Cargo.toml --test chat_endpoint_test`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/server.rs core/tests/chat_endpoint_test.rs && git commit -m "feat(core): chat completions and models endpoints"
```

---

### Task 12: `/v1/messages` (Anthropic-in) endpoint

**Files:**
- Modify: `core/src/server.rs`, `core/src/translate/mod.rs` (add inbound mapping), `core/src/translate/stream.rs` (add outbound synthesis)
- Test: `core/tests/messages_endpoint_test.rs`

**Interfaces:**
- Consumes: `a2o_request`-style inbound mapping (new `a2o_chat_request`), `o2a_stream` (Task 8), `execute` (Task 10).
- Produces: `pub fn inbound_messages(body: serde_json::Value) -> Result<ChatCompletionRequest, serde_json::Error>` and route `POST /v1/messages`.

- [ ] **Step 1: Write the failing test** (`core/tests/messages_endpoint_test.rs`)

```rust
#[tokio::test] async fn messages_endpoint_requires_key();          // 401 + Anthropic error body {"type":"error","error":{"type":"authentication_error",...}}
#[tokio::test] async fn anthropic_request_runs_canonically();      // mock OpenAI upstream sees OpenAI-shaped body; response 200 (non-stream) translated back to Anthropic message shape
#[tokio::test] async fn anthropic_stream_returns_anthropic_events();// content-type text/event-stream; first event message_start, last data frame is message_stop (no [DONE])
#[tokio::test] async fn tool_results_are_forwarded();              // inbound tool_result block reaches upstream as role "tool" message
#[tokio::test] async fn unknown_model_is_not_found_error_shape();  // 404 {"type":"error","error":{"type":"not_found_error",...}}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cargo test --manifest-path core/Cargo.toml --test messages_endpoint_test`
Expected: FAIL — route missing.

- [ ] **Step 3: Implement inbound mapping and handler**

`inbound_messages`: Anthropic body (`model`, `system`, `messages`, `max_tokens`, `tools`, `tool_choice`, `stream`, `temperature`, `top_p`) → canonical `ChatCompletionRequest` (system string → first system message; `tool_choice` `{"type":"auto"|"any"|"tool","name":…}` → OpenAI `auto`/`required`/`{"type":"function","function":{"name":…}}`; Anthropic `thinking` in `extra`). Non-stream: canonical upstream JSON → **new** `pub fn o2a_response(body: serde_json::Value, model: &str) -> serde_json::Value` (canonical OpenAI → Anthropic message shape; the inverse of Task 8's `a2o_response`) added in this task's `translate/mod.rs` change. Stream: canonical events → `o2a_stream` → frames, no `[DONE]`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cargo test --manifest-path core/Cargo.toml --test messages_endpoint_test`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/server.rs core/src/translate && git commit -m "feat(core): Anthropic messages endpoint with bidirectional streaming"
```

---

### Task 13: Dashboard assets + loopback rule

**Files:**
- Create: `core/src/dashboard.rs`, `dashboard/index.html` (placeholder), `core/tests/dashboard_test.rs`
- Modify: `core/src/server.rs`

**Interfaces:**
- Consumes: `authorize` rules (Task 5).
- Produces: `GET /dashboard` and `GET /dashboard/*` serving `dashboard/` via `rust-embed`.

- [ ] **Step 1: Write the failing test** (`core/tests/dashboard_test.rs`)

```rust
#[tokio::test] async fn dashboard_serves_index_for_loopback_without_key(); // 200, body contains "<!DOCTYPE html>"
#[tokio::test] async fn dashboard_401_for_remote_when_keys_configured();   // authorize path: keys=["k"], !loopback, no header → 401 (call authorize directly for the remote case, since a real socket from this host is loopback)
#[tokio::test] async fn unknown_asset_is_404();
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cargo test --manifest-path core/Cargo.toml --test dashboard_test`
Expected: FAIL — route missing.

- [ ] **Step 3: Implement `dashboard.rs`**

`#[derive(RustEmbed)] #[folder = "../../dashboard/"]` (path relative to `core/src/`). Handler: `/dashboard` → redirect to `/dashboard/`; `/dashboard/*` → look up asset, set `Content-Type` from extension, `Cache-Control: no-cache` for `.html`. Apply auth: loopback always allowed, otherwise `authorize`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cargo test --manifest-path core/Cargo.toml --test dashboard_test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/dashboard.rs core/src/server.rs dashboard core/tests/dashboard_test.rs
git commit -m "feat(core): embedded dashboard assets with loopback access rule"
```

---

### Task 14: CLI, end-to-end smoke, README

**Files:**
- Modify: `core/src/main.rs`, `core/tests/e2e_smoke_test.rs`
- Create: `README.md`

**Interfaces:**
- Consumes: everything above.
- Produces: runnable binary; `pub fn parse_args(args: &[String]) -> Result<CliOptions, String>` with `CliOptions { bind: String, port: u16, config: PathBuf }` (defaults from `GatewayConfig::default()`).

- [ ] **Step 1: Write the failing tests**

`core/tests/e2e_smoke_test.rs`:
```rust
#[tokio::test] async fn full_chain_fallback_end_to_end();
// config: providers p1 (500s) + p2 (echoes), combo "c" = [p1, p2]
// POST /v1/chat/completions model "c" non-stream → 200, body from p2, p1 hit >=1, p2 hit == 1
#[tokio::test] async fn anthropic_client_falls_back_to_openai_provider();
// POST /v1/messages model "c" → 200 Anthropic shape, upstream p2 saw OpenAI body
```
`core/tests/cli_test.rs`:
```rust
#[test] fn parse_args_defaults();       // [] → bind 127.0.0.1, port 20128, config ./omniroute.json
#[test] fn parse_args_overrides();      // ["--port","3000","--bind","0.0.0.0","--config","/tmp/x.json"]
#[test] fn parse_args_rejects_bad_port(); // "--port" "abc" → Err
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cargo test --manifest-path core/Cargo.toml`
Expected: FAIL on the new tests (stubs).

- [ ] **Step 3: Implement `main.rs` and the smoke tests**

`main`: parse args → `GatewayConfig::load` (missing file → defaults + `save` it) → `validate()` (exit 2 with message on error) → `tracing_subscriber` init → `reqwest::Client::with_timeout` → `AppState` → `axum::serve` on `bind:port` with `with_connect_info::<SocketAddr>()` → log `omniroute listening on http://{bind}:{port}`.

- [ ] **Step 4: Run the full suite**

Run: `cargo test --manifest-path core/Cargo.toml`
Expected: all tests across every `tests/*.rs` PASS.

- [ ] **Step 5: Manual smoke check**

Run: `cargo run --manifest-path core/Cargo.toml -- --port 20128 &` then `curl -s http://127.0.0.1:20128/health | head -c 200`
Expected: JSON with `"status":"ok"`. Kill the process afterwards.

- [ ] **Step 6: README + commit + push + CI green**

`README.md`: what it is, quickstart (`cargo run --manifest-path core/Cargo.toml`), example `omniroute.json` with one OpenAI-compatible and one Anthropic provider plus a combo, the two curl examples (`/v1/chat/completions`, `/v1/messages`), auth rule, link to the spec.
```bash
git add -A && git commit -m "feat(core): CLI, end-to-end smoke tests and README" && git push
```
Then re-check Actions (same curl as Task 1 Step 6) until `conclusion == "success"`.

---

## Wire Formats (implement exactly)

**Canonical OpenAI request** (already pinned in Task 3) — example:
```json
{"model":"free-stack","messages":[{"role":"user","content":"hi"}],"stream":false,"max_tokens":100}
```

**OpenAI streaming chunk (text):**
```json
{"id":"chatcmpl-1","object":"chat.completion.chunk","created":1767600000,"model":"gpt-4o",
 "choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"},"finish_reason":null}]}
```
Final chunk: `"delta":{},"finish_reason":"stop"`. Tool-call delta: `"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"f","arguments":""}}]}` with `arguments` accumulated across chunks; finish_reason `"tool_calls"`. Optional usage chunk: `"choices":[],"usage":{"prompt_tokens":7,"completion_tokens":3}`. Stream always ends with the frame `data: [DONE]`.

**Anthropic streaming events** (each is one SSE frame with matching `event:` name and JSON `data:`):
- `message_start` → `{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-x","content":[],"stop_reason":null,"usage":{"input_tokens":7,"output_tokens":0}}}`
- `content_block_start` → `{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}` (or `{"type":"tool_use","id":"tool_1","name":"f","input":{}}`)
- `content_block_delta` → `{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}` (or `{"type":"input_json_delta","partial_json":"{"}`)
- `content_block_stop` → `{"type":"content_block_stop","index":0}`
- `message_delta` → `{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":3}}`
- `message_stop` → `{"type":"message_stop"}`
- `ping` → `{"type":"ping"}`
Non-stream Anthropic response: `{"id":"msg_1","type":"message","role":"assistant","model":"…","content":[{"type":"text","text":"…"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":7,"output_tokens":3}}`.

**Error bodies:**
- OpenAI: `{"error":{"message":"…","type":"invalid_request_error","code":401}}` with matching HTTP status (`invalid_request_error` for 400/404/401, `server_error` for 502/504).
- Anthropic: `{"type":"error","error":{"type":"authentication_error","message":"…"}}`; `not_found_error` for 404, `api_error` for 502/504, `invalid_request_error` for 400.

---

## Self-review notes (already applied)

- Every spec goal for the core maps to a task: gateway endpoints (11, 12), routing/combos (10), quota stats (4, 10), API-key auth (5), dashboard (13), CI (1), runnable binary (14).
- Decisions carried from the spec's builder-check resolutions: model-name precedence (#1), quota from `usage` + manual limits (server exposes `usage`; the manual-limit UI is plan 2), LAN/key coupling (#4).
- Deferred to later plans (not here): JNI bridge, Compose UI, foreground service, APK signing workflow, real dashboard HTML/JS wired to `/health`.
