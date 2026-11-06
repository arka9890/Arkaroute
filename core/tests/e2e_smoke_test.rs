//! End-to-end smoke tests for the full gateway chain.

use std::net::SocketAddr;
use std::sync::{Arc, Mutex};

use axum::extract::{Request, State};
use axum::http::{header, StatusCode};
use axum::response::{IntoResponse, Response};
use omniroute_core::config::{Combo, ComboStep, GatewayConfig, ProviderConfig, ProviderKind};
use omniroute_core::server::build_router;
use omniroute_core::state::{AppState, Stats};
use reqwest::Client;
use serde_json::{json, Value};
use tokio::task::JoinHandle;

fn app_state(cfg: GatewayConfig) -> AppState {
    AppState {
        config: Arc::new(std::sync::RwLock::new(cfg)),
        stats: Arc::new(Stats::new()),
        client: reqwest::Client::new(),
    }
}

async fn spawn(state: AppState) -> (SocketAddr, JoinHandle<()>) {
    let router = build_router(state);
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let handle = tokio::spawn(async move {
        axum::serve(listener, router.into_make_service_with_connect_info::<SocketAddr>())
            .await
            .unwrap();
    });
    (addr, handle)
}

type Script = Arc<dyn Fn(usize) -> Response + Send + Sync>;

struct MockUpstream {
    addr: SocketAddr,
    requests: Arc<Mutex<Vec<usize>>>,
    captured: Arc<Mutex<Option<Value>>>,
    handle: JoinHandle<()>,
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

async fn spawn_upstream(script: impl Fn(usize) -> Response + Send + Sync + 'static) -> MockUpstream {
    spawn_upstream_capturing(script).await
}

async fn handle(
    State(state): State<(Arc<Mutex<Vec<usize>>>, Script, Arc<Mutex<Option<Value>>>)>,
    req: Request,
) -> Response {
    let (requests, script, captured) = state;
    let index = {
        let mut r = requests.lock().unwrap();
        let i = r.len();
        r.push(i);
        i
    };
    let bytes = axum::body::to_bytes(req.into_body(), 1024 * 1024)
        .await
        .unwrap_or_default();
    if !bytes.is_empty() {
        if let Ok(v) = serde_json::from_slice::<Value>(&bytes) {
            *captured.lock().unwrap() = Some(v);
        }
    }
    script(index)
}

async fn spawn_upstream_capturing(
    script: impl Fn(usize) -> Response + Send + Sync + 'static,
) -> MockUpstream {
    let requests = Arc::new(Mutex::new(Vec::new()));
    let captured = Arc::new(Mutex::new(None));
    let state = (
        requests.clone(),
        Arc::new(script) as Script,
        captured.clone(),
    );
    let router = axum::Router::new().fallback(handle).with_state(state);
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let handle = tokio::spawn(async move {
        axum::serve(listener, router).await.unwrap();
    });
    MockUpstream {
        addr,
        requests,
        captured,
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

fn combo(name: &str, steps: Vec<(&str, &str)>) -> Combo {
    Combo {
        name: name.into(),
        steps: steps
            .into_iter()
            .map(|(p, m)| ComboStep {
                provider: p.into(),
                model: m.into(),
            })
            .collect(),
    }
}

#[tokio::test]
async fn full_chain_fallback_end_to_end() {
    let p1 = spawn_upstream(|_| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            [(header::CONTENT_TYPE, "application/json")],
            r#"{"error":{"message":"boom"}}"#,
        )
            .into_response()
    })
    .await;
    let p2 = spawn_upstream(|_| {
        json_ok(r#"{"id":"p2","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"from-p2"},"finish_reason":"stop"}]}"#)
    })
    .await;

    let mut cfg = GatewayConfig::default();
    cfg.providers = vec![
        ProviderConfig {
            id: "p1".into(),
            kind: ProviderKind::OpenAiCompatible,
            base_url: p1.base_url(),
            api_key: "".into(),
            models: vec![],
        },
        ProviderConfig {
            id: "p2".into(),
            kind: ProviderKind::OpenAiCompatible,
            base_url: p2.base_url(),
            api_key: "".into(),
            models: vec![],
        },
    ];
    cfg.combos = vec![combo("c", vec![("p1", "m"), ("p2", "m")])];
    cfg.request_timeout_ms = 5_000;
    cfg.stream_idle_timeout_ms = 200;

    let (addr, handle) = spawn(app_state(cfg)).await;
    let resp = Client::new()
        .post(format!("http://{addr}/v1/chat/completions"))
        .json(&json!({"model":"c","messages":[{"role":"user","content":"hi"}],"stream":false}))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let body: Value = resp.json().await.unwrap();
    assert_eq!(body["choices"][0]["message"]["content"], "from-p2");
    assert!(p1.requests.lock().unwrap().len() >= 1);
    assert_eq!(p2.requests.lock().unwrap().len(), 1);
    handle.abort();
    drop(p1);
    drop(p2);
}

#[tokio::test]
async fn anthropic_client_falls_back_to_openai_provider() {
    let p1 = spawn_upstream(|_| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            [(header::CONTENT_TYPE, "application/json")],
            r#"{"error":{"message":"boom"}}"#,
        )
            .into_response()
    })
    .await;
    let p2 = spawn_upstream_capturing(|_| {
        json_ok(r#"{"id":"p2","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"from-p2"},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":3}}"#)
    })
    .await;

    let mut cfg = GatewayConfig::default();
    cfg.providers = vec![
        ProviderConfig {
            id: "p1".into(),
            kind: ProviderKind::OpenAiCompatible,
            base_url: p1.base_url(),
            api_key: "".into(),
            models: vec![],
        },
        ProviderConfig {
            id: "p2".into(),
            kind: ProviderKind::OpenAiCompatible,
            base_url: p2.base_url(),
            api_key: "".into(),
            models: vec![],
        },
    ];
    cfg.combos = vec![combo("c", vec![("p1", "m"), ("p2", "m")])];
    cfg.request_timeout_ms = 5_000;
    cfg.stream_idle_timeout_ms = 200;

    let (addr, handle) = spawn(app_state(cfg)).await;
    let resp = Client::new()
        .post(format!("http://{addr}/v1/messages"))
        .json(&json!({"model":"c","max_tokens":100,"messages":[{"role":"user","content":"hi"}],"stream":false}))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let body: Value = resp.json().await.unwrap();
    assert_eq!(body["type"], "message");
    assert_eq!(body["content"][0]["type"], "text");
    assert_eq!(body["content"][0]["text"], "from-p2");

    let captured = p2.captured.lock().unwrap().clone().unwrap();
    assert!(captured.get("messages").is_some(), "upstream saw OpenAI body: {captured}");

    handle.abort();
    drop(p1);
    drop(p2);
}