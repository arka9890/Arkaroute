//! Integration tests for the `/v1/messages` (Anthropic-in) endpoint.

use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use axum::body::Body;
use axum::extract::{Request, State};
use axum::http::{header, StatusCode};
use axum::response::{IntoResponse, Response};
use bytes::Bytes;
use futures_util::StreamExt;
use omniroute_core::config::{GatewayConfig, ProviderConfig, ProviderKind};
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
    // capture body
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

async fn spawn_upstream(script: impl Fn(usize) -> Response + Send + Sync + 'static) -> MockUpstream {
    spawn_upstream_capturing(script).await
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

fn gateway(base_url: &str, keys: Vec<String>) -> GatewayConfig {
    GatewayConfig {
        bind: "127.0.0.1".into(),
        port: 20128,
        lan_enabled: false,
        gateway_keys: keys,
        default_combo: None,
        providers: vec![ProviderConfig {
            id: "openai".into(),
            kind: ProviderKind::OpenAiCompatible,
            base_url: base_url.to_string(),
            api_key: "sk-openai".into(),
            models: vec!["gpt-4o".into()],
        }],
        combos: vec![],
        request_timeout_ms: 5_000,
        stream_idle_timeout_ms: 200,
    }
}

#[tokio::test]
async fn messages_endpoint_requires_key() {
    let mut cfg = GatewayConfig::default();
    cfg.gateway_keys = vec!["k".into()];
    let (addr, handle) = spawn(app_state(cfg)).await;
    let resp = Client::new()
        .post(format!("http://{addr}/v1/messages"))
        .json(&json!({"model":"x","max_tokens":10,"messages":[{"role":"user","content":"hi"}]}))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 401);
    let json: Value = resp.json().await.unwrap();
    assert_eq!(json["type"], "error");
    assert_eq!(json["error"]["type"], "authentication_error");
    handle.abort();
}

#[tokio::test]
async fn anthropic_request_runs_canonically() {
    let upstream = spawn_upstream(|_| {
        json_ok(r#"{"id":"x","object":"chat.completion","created":1,"model":"gpt-4o","choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":3}}"#)
    })
    .await;
    let cfg = gateway(&upstream.base_url(), vec![]);
    // allow model openai/gpt-4o
    let mut cfg = cfg;
    cfg.combos = vec![];
    let (addr, handle) = spawn(app_state(cfg)).await;
    let resp = Client::new()
        .post(format!("http://{addr}/v1/messages"))
        .json(&json!({"model":"openai/gpt-4o","max_tokens":100,"messages":[{"role":"user","content":"hi"}],"stream":false}))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let json: Value = resp.json().await.unwrap();
    assert_eq!(json["type"], "message");
    assert_eq!(json["role"], "assistant");
    assert_eq!(json["content"][0]["type"], "text");
    assert_eq!(json["content"][0]["text"], "hi");
    assert_eq!(json["stop_reason"], "end_turn");
    handle.abort();
}

#[tokio::test]
async fn anthropic_stream_returns_anthropic_events() {
    let upstream = spawn_upstream(|_| {
        Response::builder()
            .status(StatusCode::OK)
            .header(header::CONTENT_TYPE, "text/event-stream")
            .body(Body::from(
                "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hel\"},\"finish_reason\":null}]}\n\ndata: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n",
            ))
            .unwrap()
    })
    .await;
    let cfg = gateway(&upstream.base_url(), vec![]);
    let (addr, handle) = spawn(app_state(cfg)).await;
    let resp = Client::new()
        .post(format!("http://{addr}/v1/messages"))
        .json(&json!({"model":"openai/gpt-4o","max_tokens":100,"messages":[{"role":"user","content":"hi"}],"stream":true}))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let ct = resp
        .headers()
        .get("content-type")
        .unwrap()
        .to_str()
        .unwrap()
        .to_string();
    assert!(ct.contains("text/event-stream"), "{ct}");
    let text = resp.text().await.unwrap();
    assert!(text.contains("message_start"), "{text}");
    assert!(text.contains("message_stop"), "{text}");
    assert!(!text.contains("[DONE]"), "{text}");
    // first event message_start
    assert!(text.trim_start().starts_with("event: message_start"), "{text}");
    handle.abort();
}

#[tokio::test]
async fn tool_results_are_forwarded() {
    let upstream = spawn_upstream_capturing(|_| {
        json_ok(r#"{"id":"x","object":"chat.completion","created":1,"model":"gpt-4o","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"#)
    })
    .await;
    let cfg = gateway(&upstream.base_url(), vec![]);
    let (addr, handle) = spawn(app_state(cfg)).await;
    let resp = Client::new()
        .post(format!("http://{addr}/v1/messages"))
        .json(&json!({
            "model":"openai/gpt-4o",
            "max_tokens":100,
            "messages":[
                {"role":"user","content":"hi"},
                {"role":"assistant","content":[{"type":"tool_use","id":"call_1","name":"f","input":{}}]},
                {"role":"user","content":[{"type":"tool_result","tool_use_id":"call_1","content":"sunny"}]}
            ],
            "stream":false
        }))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let captured = upstream.captured.lock().unwrap().clone().unwrap();
    let msgs = captured["messages"].as_array().unwrap();
    let tool_msg = msgs.iter().find(|m| m["role"] == "tool").unwrap();
    assert_eq!(tool_msg["tool_call_id"], "call_1");
    assert_eq!(tool_msg["content"], "sunny");
    handle.abort();
}

#[tokio::test]
async fn unknown_model_is_not_found_error_shape() {
    let cfg = GatewayConfig::default();
    let (addr, handle) = spawn(app_state(cfg)).await;
    let resp = Client::new()
        .post(format!("http://{addr}/v1/messages"))
        .json(&json!({"model":"nope","max_tokens":10,"messages":[{"role":"user","content":"hi"}]}))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 404);
    let json: Value = resp.json().await.unwrap();
    assert_eq!(json["type"], "error");
    assert_eq!(json["error"]["type"], "not_found_error");
    handle.abort();
}

#[tokio::test]
async fn messages_stream_incremental_emits_before_upstream_eof() {

    let stream = async_stream::stream! {
        yield Ok::<_, std::convert::Infallible>(Bytes::from(
            "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hel\"},\"finish_reason\":null}]}\n\n",
        ));
        tokio::time::sleep(Duration::from_secs(5)).await;
    };
    let holder = std::sync::Arc::new(std::sync::Mutex::new(Some(Box::pin(stream)
        as std::pin::Pin<Box<dyn futures_util::Stream<Item = Result<Bytes, std::convert::Infallible>> + Send>>)));
    let holder_clone = holder.clone();
    let upstream = spawn_upstream(move |_i| {
        let s = holder_clone.lock().unwrap().take().unwrap();
        axum::response::Response::builder()
            .status(StatusCode::OK)
            .header(header::CONTENT_TYPE, "text/event-stream")
            .body(Body::from_stream(s))
            .unwrap()
    })
    .await;
    let mut cfg = gateway(&upstream.base_url(), vec![]);
    cfg.stream_idle_timeout_ms = 5_000;
    let (gaddr, ghandle) = spawn(app_state(cfg)).await;

    let resp = Client::new()
        .post(format!("http://{gaddr}/v1/messages"))
        .json(&json!({"model":"openai/gpt-4o","max_tokens":100,"messages":[{"role":"user","content":"hi"}],"stream":true}))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let mut stream = resp.bytes_stream();
    let mut body = String::new();
    let started = std::time::Instant::now();
    while started.elapsed() < Duration::from_millis(1500) {
        match tokio::time::timeout(Duration::from_millis(200), stream.next()).await {
            Ok(Some(Ok(chunk))) => body.push_str(&String::from_utf8_lossy(&chunk)),
            Ok(Some(Err(_))) => break,
            Ok(None) => break,
            Err(_) => break,
        }
        if body.contains("message_start") && body.contains("Hel") {
            break;
        }
    }
    assert!(body.contains("message_start"), "message_start not emitted early: {body}");
    assert!(body.contains("Hel"), "content not emitted early: {body}");
    ghandle.abort();
}

#[tokio::test]
async fn messages_stream_midstream_failure_emits_error_frame() {
    let stream = async_stream::stream! {
        yield Ok::<_, std::convert::Infallible>(Bytes::from(
            "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hel\"},\"finish_reason\":null}]}\n\n",
        ));
        tokio::time::sleep(Duration::from_secs(5)).await;
    };
    let holder = std::sync::Arc::new(std::sync::Mutex::new(Some(Box::pin(stream)
        as std::pin::Pin<Box<dyn futures_util::Stream<Item = Result<Bytes, std::convert::Infallible>> + Send>>)));
    let holder_clone = holder.clone();
    let upstream = spawn_upstream(move |_i| {
        let s = holder_clone.lock().unwrap().take().unwrap();
        axum::response::Response::builder()
            .status(StatusCode::OK)
            .header(header::CONTENT_TYPE, "text/event-stream")
            .body(Body::from_stream(s))
            .unwrap()
    })
    .await;
    let mut cfg = gateway(&upstream.base_url(), vec![]);
    cfg.stream_idle_timeout_ms = 200;
    let (gaddr, ghandle) = spawn(app_state(cfg)).await;

    let resp = Client::new()
        .post(format!("http://{gaddr}/v1/messages"))
        .json(&json!({"model":"openai/gpt-4o","max_tokens":100,"messages":[{"role":"user","content":"hi"}],"stream":true}))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let text = tokio::time::timeout(Duration::from_secs(3), resp.text())
        .await
        .unwrap()
        .unwrap();
    assert!(text.contains("api_error") || text.contains("stream idle timeout"), "{text}");
    assert!(!text.contains("message_stop"), "no clean message_stop after failure: {text}");
    ghandle.abort();
}
