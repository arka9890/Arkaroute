//! Integration tests for the `/v1/chat/completions` and `/v1/models` endpoints.

use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use axum::body::Body;
use axum::extract::{Request, State};
use axum::http::{header, StatusCode};
use axum::response::{IntoResponse, Response};
use bytes::Bytes;
use futures_util::StreamExt;
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

async fn handle(State(state): State<(Arc<Mutex<Vec<usize>>>, Script)>, _req: Request) -> Response {
    let (requests, script) = state;
    let index = {
        let mut r = requests.lock().unwrap();
        let i = r.len();
        r.push(i);
        i
    };
    script(index)
}

async fn spawn_upstream(script: impl Fn(usize) -> Response + Send + Sync + 'static) -> MockUpstream {
    let requests = Arc::new(Mutex::new(Vec::new()));
    let state = (requests.clone(), Arc::new(script) as Script);
    let router = axum::Router::new().fallback(handle).with_state(state);
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let handle = tokio::spawn(async move {
        axum::serve(listener, router).await.unwrap();
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
        combos: vec![combo("free-stack", vec![("openai", "gpt-4o")])],
        request_timeout_ms: 5_000,
        stream_idle_timeout_ms: 200,
    }
}

#[tokio::test]
async fn chat_requires_key_when_keys_configured() {
    let mut cfg = GatewayConfig::default();
    cfg.gateway_keys = vec!["k".into()];
    let (addr, handle) = spawn(app_state(cfg)).await;
    let resp = Client::new()
        .post(format!("http://{addr}/v1/chat/completions"))
        .json(&json!({"model":"x","messages":[{"role":"user","content":"hi"}]}))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 401);
    let json: Value = resp.json().await.unwrap();
    assert_eq!(json["error"]["type"], "invalid_request_error");
    handle.abort();
}

#[tokio::test]
async fn models_lists_combo_and_provider_models() {
    let cfg = gateway("http://127.0.0.1:1", vec![]);
    let (addr, handle) = spawn(app_state(cfg)).await;
    let resp = Client::new()
        .get(format!("http://{addr}/v1/models"))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let json: Value = resp.json().await.unwrap();
    assert_eq!(json["object"], "list");
    let ids: Vec<&str> = json["data"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(|m| m["id"].as_str())
        .collect();
    assert!(ids.contains(&"free-stack"), "{ids:?}");
    assert!(ids.contains(&"openai/gpt-4o"), "{ids:?}");
    handle.abort();
}

#[tokio::test]
async fn non_stream_returns_openai_json() {
    let upstream = spawn_upstream(|_| {
        json_ok(r#"{"id":"x","object":"chat.completion","created":1,"model":"gpt-4o","choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}]}"#)
    })
    .await;
    let cfg = gateway(&upstream.base_url(), vec![]);
    let (addr, handle) = spawn(app_state(cfg)).await;
    let resp = Client::new()
        .post(format!("http://{addr}/v1/chat/completions"))
        .json(&json!({"model":"openai/gpt-4o","messages":[{"role":"user","content":"hi"}],"stream":false}))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let json: Value = resp.json().await.unwrap();
    assert_eq!(json["object"], "chat.completion");
    assert_eq!(json["choices"][0]["message"]["role"], "assistant");
    handle.abort();
}

#[tokio::test]
async fn stream_returns_event_stream_with_done() {
    let upstream = spawn_upstream(|_| {
        Response::builder()
            .status(StatusCode::OK)
            .header(header::CONTENT_TYPE, "text/event-stream")
            .body(Body::from(
                "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hel\"}}]}\n\ndata: [DONE]\n\n",
            ))
            .unwrap()
    })
    .await;
    let cfg = gateway(&upstream.base_url(), vec![]);
    let (addr, handle) = spawn(app_state(cfg)).await;
    let resp = Client::new()
        .post(format!("http://{addr}/v1/chat/completions"))
        .json(&json!({"model":"openai/gpt-4o","messages":[{"role":"user","content":"hi"}],"stream":true}))
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
    assert!(text.contains("data: [DONE]"), "{text}");
    assert!(text.trim_end().ends_with("data: [DONE]"), "{text}");
    handle.abort();
}

#[tokio::test]
async fn unknown_model_is_404_openai_error_shape() {
    let cfg = GatewayConfig::default();
    let (addr, handle) = spawn(app_state(cfg)).await;
    let resp = Client::new()
        .post(format!("http://{addr}/v1/chat/completions"))
        .json(&json!({"model":"nope","messages":[{"role":"user","content":"hi"}]}))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 404);
    let json: Value = resp.json().await.unwrap();
    assert_eq!(json["error"]["type"], "invalid_request_error");
    handle.abort();
}

#[tokio::test]
async fn all_providers_failed_is_502() {
    let upstream = spawn_upstream(|_| {
        (
            StatusCode::TOO_MANY_REQUESTS,
            [(header::CONTENT_TYPE, "application/json")],
            r#"{"error":{"message":"rate","type":"rate_limit_error"}}"#,
        )
            .into_response()
    })
    .await;
    let mut cfg = gateway(&upstream.base_url(), vec![]);
    cfg.combos = vec![combo("free-stack", vec![("openai", "gpt-4o"), ("openai", "gpt-4o")])];
    let (addr, handle) = spawn(app_state(cfg)).await;
    let resp = Client::new()
        .post(format!("http://{addr}/v1/chat/completions"))
        .json(&json!({"model":"free-stack","messages":[{"role":"user","content":"hi"}]}))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 502);
    let json: Value = resp.json().await.unwrap();
    assert_eq!(json["error"]["type"], "server_error");
    handle.abort();
}

#[tokio::test]
async fn health_requires_key_when_keys_configured() {
    let mut cfg = GatewayConfig::default();
    cfg.gateway_keys = vec!["k".into()];
    let (addr, handle) = spawn(app_state(cfg)).await;
    let resp = Client::new()
        .get(format!("http://{addr}/health"))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 401);
    handle.abort();
}

#[tokio::test]
async fn stalled_upstream_stream_times_out() {
    let requests: Arc<Mutex<Vec<usize>>> = Arc::new(Mutex::new(Vec::new()));
    let requests_srv = requests.clone();
    let script: Script = Arc::new(|_i| {
        let stream = async_stream::stream! {
            yield Ok::<_, std::convert::Infallible>(Bytes::from(
                "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hel\"}}]}\n\n",
            ));
            tokio::time::sleep(Duration::from_secs(2)).await;
        };
        Response::builder()
            .status(StatusCode::OK)
            .header(header::CONTENT_TYPE, "text/event-stream")
            .body(Body::from_stream(stream))
            .unwrap()
    });
    let state = (requests_srv.clone(), script);
    let router = axum::Router::new().fallback({
        let st = state.clone();
        move |State(s): State<(Arc<Mutex<Vec<usize>>>, Script)>, _req: Request| {
            let (reqs, sc) = s;
            let idx = {
                let mut r = reqs.lock().unwrap();
                let i = r.len();
                r.push(i);
                i
            };
            let resp = sc(idx);
            async move { resp }
        }
    })
    .with_state(state);
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let uaddr = listener.local_addr().unwrap();
    let uhandle = tokio::spawn(async move { axum::serve(listener, router).await.unwrap(); });

    let mut cfg = gateway(&format!("http://{}", uaddr), vec![]);
    cfg.stream_idle_timeout_ms = 200;
    cfg.combos = vec![combo("free-stack", vec![("openai", "gpt-4o"), ("openai", "gpt-4o")])];
    let (gaddr, ghandle) = spawn(app_state(cfg)).await;

    let started = Instant::now();
    let resp = Client::new()
        .post(format!("http://{gaddr}/v1/chat/completions"))
        .json(&json!({"model":"free-stack","messages":[{"role":"user","content":"hi"}],"stream":true}))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let mut stream = resp.bytes_stream();
    let mut body = String::new();
    while let Ok(Some(chunk)) = tokio::time::timeout(Duration::from_millis(1500), stream.next()).await {
        if let Ok(b) = chunk {
            body.push_str(&String::from_utf8_lossy(&b));
        }
        if started.elapsed() > Duration::from_millis(1500) {
            break;
        }
    }
    assert!(!body.contains("data: [DONE]"), "no DONE on timeout: {body}");
    assert!(body.contains("error"), "error frame present: {body}");
    assert!(started.elapsed() < Duration::from_secs(2), "finished well before stall");
    assert_eq!(requests.lock().unwrap().len(), 1);
    ghandle.abort();
    uhandle.abort();
}

#[tokio::test]
async fn peer_without_connect_info_fails_closed() {
    // keyless config: missing connect-info must NOT be treated as loopback
    let cfg = GatewayConfig::default();
    let router = build_router(app_state(cfg));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let handle = tokio::spawn(async move { axum::serve(listener, router).await.unwrap(); });
    let resp = Client::new()
        .get(format!("http://{addr}/health"))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 403);
    handle.abort();

    // keys configured: still 401 without a key regardless of peer
    let mut cfg = GatewayConfig::default();
    cfg.gateway_keys = vec!["k".into()];
    let router = build_router(app_state(cfg));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let handle = tokio::spawn(async move { axum::serve(listener, router).await.unwrap(); });
    let resp = Client::new()
        .get(format!("http://{addr}/v1/models"))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 401);
    handle.abort();
}
