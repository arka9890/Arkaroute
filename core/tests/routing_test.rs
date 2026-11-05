//! Integration tests for the routing engine: model resolution precedence and
//! the fallback walk in [`omniroute_core::routing::execute`].

use std::net::SocketAddr;
use std::sync::{Arc, Mutex, RwLock};

use axum::body::Body;
use axum::extract::{Request, State};
use axum::http::{header, StatusCode};
use axum::response::{IntoResponse, Response};
use bytes::Bytes;
use futures_util::StreamExt;
use omniroute_core::config::{Combo, ComboStep, GatewayConfig, ProviderConfig, ProviderKind};
use omniroute_core::protocol::{ChatCompletionRequest, ChatMessage, Role};
use omniroute_core::providers::{UpstreamError, UpstreamOutcome};
use omniroute_core::routing::{execute, resolve_model, ResolveError, ResolvedModel, RouteError};
use omniroute_core::sse::SseEvent;
use omniroute_core::state::{AppState, Stats};
use serde_json::json;

/// Request timeout the tests hand to adapters built via `GatewayConfig`.
const REQUEST_TIMEOUT_MS: u64 = 5_000;

const JSON_OK: &str = r#"{"id":"x","object":"chat.completion","created":1,"model":"m2","choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":3}}"#;

// ---------------------------------------------------------------------------
// Mock upstream
// ---------------------------------------------------------------------------

type Script = Arc<dyn Fn(usize) -> Response + Send + Sync>;

/// A scripted mock upstream bound to `127.0.0.1:0`.
struct MockUpstream {
    addr: SocketAddr,
    requests: Arc<Mutex<Vec<usize>>>,
    handle: tokio::task::JoinHandle<()>,
}

impl MockUpstream {
    fn base_url(&self) -> String {
        format!("http://{}", self.addr)
    }
}

impl Drop for MockUpstream {
    fn drop(&mut self) {
        self.handle.abort();
    }
}

async fn handle(State(state): State<(Arc<Mutex<Vec<usize>>>, Script)>, _req: Request) -> Response {
    let (requests, script) = state;
    let index = {
        let mut requests = requests.lock().expect("mock request lock");
        let index = requests.len();
        requests.push(index);
        index
    };
    script(index)
}

/// Spawn the mock upstream; `script` maps a zero-based request index to the
/// response to return.
async fn spawn_upstream(
    script: impl Fn(usize) -> Response + Send + Sync + 'static,
) -> MockUpstream {
    let requests = Arc::new(Mutex::new(Vec::new()));
    let state = (requests.clone(), Arc::new(script) as Script);
    let router = axum::Router::new().fallback(handle).with_state(state);
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind mock upstream");
    let addr = listener.local_addr().expect("mock upstream addr");
    let handle = tokio::spawn(async move {
        axum::serve(listener, router)
            .await
            .expect("serve mock upstream");
    });
    MockUpstream {
        addr,
        requests,
        handle,
    }
}

fn json_ok(body: &str) -> Response {
    (
        StatusCode::OK,
        [(header::CONTENT_TYPE, "application/json")],
        body.to_string(),
    )
        .into_response()
}

fn error_response(status: StatusCode, message: &str) -> Response {
    (
        status,
        [(header::CONTENT_TYPE, "application/json")],
        format!(r#"{{"error":{{"message":"{message}","type":"error"}}}}"#),
    )
        .into_response()
}

/// SSE frames over a body that stays open past any idle window.
fn sse_response(parts: Vec<String>) -> Response {
    let stream = async_stream::stream! {
        let mut first = true;
        for part in parts {
            if !first {
                tokio::time::sleep(std::time::Duration::from_millis(25)).await;
            }
            first = false;
            yield Ok::<_, std::convert::Infallible>(Bytes::from(part));
        }
        tokio::time::sleep(std::time::Duration::from_secs(60)).await;
    };
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "text/event-stream")
        .body(Body::from_stream(stream))
        .expect("response builds")
}

fn chunk(id: u64, text: &str) -> String {
    format!(
        r#"data: {{"id":"c{id}","object":"chat.completion.chunk","created":1,"model":"m","choices":[{{"index":0,"delta":{{"content":"{text}"}}}}]}}"#
    )
}

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

fn provider(id: &str, base_url: String) -> ProviderConfig {
    ProviderConfig {
        id: id.into(),
        kind: ProviderKind::OpenAiCompatible,
        base_url,
        api_key: format!("sk-{id}"),
        models: vec![format!("{id}-model")],
    }
}

fn combo(name: &str, steps: Vec<(&str, &str)>) -> Combo {
    Combo {
        name: name.into(),
        steps: steps
            .into_iter()
            .map(|(provider, model)| ComboStep {
                provider: provider.into(),
                model: model.into(),
            })
            .collect(),
    }
}

fn gateway(default_combo: Option<String>, combos: Vec<Combo>, base_url: &str) -> GatewayConfig {
    GatewayConfig {
        bind: "127.0.0.1".into(),
        port: 20128,
        lan_enabled: false,
        gateway_keys: Vec::new(),
        default_combo,
        providers: vec![
            provider("p1", base_url.to_string()),
            provider("p2", base_url.to_string()),
        ],
        combos,
        request_timeout_ms: REQUEST_TIMEOUT_MS,
        stream_idle_timeout_ms: 60_000,
    }
}

fn app_state(cfg: GatewayConfig, stats: Arc<Stats>) -> AppState {
    AppState {
        config: Arc::new(RwLock::new(cfg)),
        stats,
        client: reqwest::Client::new(),
    }
}

fn chat_req(model: &str, stream: bool) -> ChatCompletionRequest {
    ChatCompletionRequest {
        model: model.into(),
        messages: vec![ChatMessage {
            role: Role::User,
            content: Some(json!("hello")),
            tool_calls: None,
            tool_call_id: None,
            name: None,
            extra: Default::default(),
        }],
        stream,
        max_tokens: None,
        temperature: None,
        top_p: None,
        tools: None,
        tool_choice: None,
        extra: Default::default(),
    }
}

async fn collect(
    mut stream: impl futures_util::Stream<Item = Result<SseEvent, UpstreamError>> + Unpin,
) -> Vec<Result<SseEvent, UpstreamError>> {
    let mut events = Vec::new();
    while let Some(item) = stream.next().await {
        events.push(item);
    }
    events
}

// ---------------------------------------------------------------------------
// Resolution: precedence
// ---------------------------------------------------------------------------

#[test]
fn combo_prefix_wins() {
    let cfg = GatewayConfig {
        combos: vec![combo("free", vec![("p1", "m1")])],
        ..GatewayConfig::default()
    };
    assert_eq!(
        resolve_model("combo/free", &cfg).expect("combo/free resolves"),
        ResolvedModel::Combo("free".into())
    );
}

#[test]
fn exact_combo_name_wins_over_provider() {
    let cfg = GatewayConfig {
        combos: vec![combo("fast", vec![("p1", "m1")])],
        ..GatewayConfig::default()
    };
    assert_eq!(
        resolve_model("fast", &cfg).expect("bare combo name resolves"),
        ResolvedModel::Combo("fast".into())
    );
}

#[test]
fn provider_slash_model_resolves_direct() {
    let cfg = GatewayConfig {
        providers: vec![provider("openai", "http://127.0.0.1:1".into())],
        ..GatewayConfig::default()
    };
    assert_eq!(
        resolve_model("openai/gpt-4o", &cfg).expect("provider/model resolves"),
        ResolvedModel::Direct {
            provider: "openai".into(),
            model: "gpt-4o".into(),
        }
    );
}

#[test]
fn unknown_name_falls_back_to_default_combo() {
    let cfg = GatewayConfig {
        default_combo: Some("d".into()),
        combos: vec![combo("d", vec![("p1", "m1")])],
        ..GatewayConfig::default()
    };
    assert_eq!(
        resolve_model("nope", &cfg).expect("unknown name falls to default combo"),
        ResolvedModel::Combo("d".into())
    );
}

#[test]
fn unknown_name_without_default_is_404() {
    let cfg = GatewayConfig::default();
    assert_eq!(
        resolve_model("nope", &cfg),
        Err(ResolveError::UnknownModel("nope".into()))
    );
}

// ---------------------------------------------------------------------------
// Execution: fallback walk
// ---------------------------------------------------------------------------

#[tokio::test]
async fn combo_walks_to_second_step_on_500() {
    let upstream = spawn_upstream(|i| {
        if i == 0 {
            error_response(StatusCode::INTERNAL_SERVER_ERROR, "boom")
        } else {
            json_ok(JSON_OK)
        }
    })
    .await;

    let cfg = gateway(
        None,
        vec![combo("free", vec![("p1", "m1"), ("p2", "m2")])],
        &upstream.base_url(),
    );
    let stats = Arc::new(Stats::new());
    let state = app_state(cfg.clone(), Arc::clone(&stats));

    let outcome = execute(&state, chat_req("free", false))
        .await
        .expect("combo walks to second step on 500");

    assert!(
        matches!(outcome.response, UpstreamOutcome::Json(_)),
        "second step answered"
    );
    assert_eq!(
        outcome.attempts,
        vec!["p1".to_string(), "p2".to_string()],
        "both provider ids tried, in order"
    );
    assert_eq!(outcome.provider, "p2");
    assert_eq!(outcome.model, "m2");

    let snapshot = stats.snapshot(&cfg);
    let p1 = snapshot
        .providers
        .iter()
        .find(|p| p.id == "p1")
        .expect("p1 snapshotted");
    assert!(!p1.healthy, "p1 recorded the failure");
    assert_eq!(p1.requests, 1);
    assert!(p1.last_error.is_some(), "p1 last_error set");

    let p2 = snapshot
        .providers
        .iter()
        .find(|p| p.id == "p2")
        .expect("p2 snapshotted");
    assert!(p2.healthy, "p2 recorded the success");
    assert_eq!(p2.tokens_in, 7);
    assert_eq!(p2.tokens_out, 3);
}

#[tokio::test]
async fn combo_stops_on_400() {
    let upstream = spawn_upstream(|_| error_response(StatusCode::BAD_REQUEST, "bad")).await;

    let cfg = gateway(
        None,
        vec![combo("free", vec![("p1", "m1"), ("p2", "m2")])],
        &upstream.base_url(),
    );
    let stats = Arc::new(Stats::new());
    let state = app_state(cfg, Arc::clone(&stats));

    let err = execute(&state, chat_req("free", false))
        .await
        .expect_err("400 must not fall back");
    match err {
        RouteError::Upstream(e) => {
            assert_eq!(e.status(), 400);
            assert!(!e.is_fallbackable(), "client error must not fall back");
        }
        other => panic!("expected RouteError::Upstream, got {other:?}"),
    }

    // Step 2 never saw a request: no fallback after a client error.
    let requests = upstream.requests.lock().expect("mock request lock");
    assert_eq!(requests.len(), 1, "no fallback after 400");
}

#[tokio::test]
async fn no_fallback_after_first_chunk() {
    let upstream = spawn_upstream(|_| {
        sse_response(vec![
            format!("{}\n\n", chunk(1, "a")),
            format!("{}\n\n", chunk(2, "b")),
        ])
    })
    .await;

    let mut cfg = gateway(
        None,
        vec![combo("free", vec![("p1", "m1"), ("p2", "m2")])],
        &upstream.base_url(),
    );
    cfg.request_timeout_ms = 1_000;
    let stats = Arc::new(Stats::new());
    let state = app_state(cfg, Arc::clone(&stats));

    let outcome = execute(&state, chat_req("free", true))
        .await
        .expect("first chunk reached the client: Ok(RouteOutcome::Stream)");

    let stream = match outcome.response {
        UpstreamOutcome::Stream(s) => s,
        UpstreamOutcome::Json(_) => panic!("expected Stream outcome"),
    };
    let events = collect(stream).await;
    assert_eq!(events.len(), 3, "two chunks then the error");
    assert!(events[0]
        .as_ref()
        .expect("chunk 1 ok")
        .data
        .contains(r#""content":"a""#));
    assert!(events[1]
        .as_ref()
        .expect("chunk 2 ok")
        .data
        .contains(r#""content":"b""#));
    let err = events[2]
        .as_ref()
        .expect_err("stream must end with an error");
    assert_eq!(err.status(), 504);
    assert!(err.is_fallbackable());

    // No fallback after the first byte: step 2 was never contacted.
    let requests = upstream.requests.lock().expect("mock request lock");
    assert_eq!(requests.len(), 1, "no fallback after first chunk");
}

#[tokio::test]
async fn all_steps_failed_returns_all_failed() {
    let upstream = spawn_upstream(|_| error_response(StatusCode::TOO_MANY_REQUESTS, "rate")).await;

    let cfg = gateway(
        None,
        vec![combo("free", vec![("p1", "m1"), ("p2", "m2")])],
        &upstream.base_url(),
    );
    let stats = Arc::new(Stats::new());
    let state = app_state(cfg, Arc::clone(&stats));

    let err = execute(&state, chat_req("free", false))
        .await
        .expect_err("both steps failing must surface AllFailed");
    match err {
        RouteError::AllFailed(errs) => assert_eq!(errs.len(), 2, "one error per attempt"),
        other => panic!("expected RouteError::AllFailed, got {other:?}"),
    }
}
