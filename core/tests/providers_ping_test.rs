//! Integration tests for `POST /v1/providers/{id}/ping`.

use std::net::SocketAddr;
use std::sync::Arc;

use omniroute_core::config::{GatewayConfig, ProviderConfig, ProviderKind};
use omniroute_core::server::build_router;
use omniroute_core::state::{AppState, Stats};
use reqwest::Client;
use serde_json::Value;
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

struct MockUpstream {
    addr: SocketAddr,
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

async fn spawn_upstream() -> MockUpstream {
    use axum::http::{header, StatusCode};
    use axum::response::IntoResponse;
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind mock upstream");
    let addr = listener.local_addr().expect("mock upstream addr");
    let handle = tokio::spawn(async move {
        let router = axum::Router::new().fallback(|| async {
            (StatusCode::OK, [(header::CONTENT_TYPE, "application/json")], "{\"ok\":true}").into_response()
        });
        axum::serve(listener, router).await.expect("serve mock upstream");
    });
    MockUpstream { addr, handle }
}

fn provider_cfg(base_url: String) -> ProviderConfig {
    ProviderConfig {
        id: "p".to_string(),
        kind: ProviderKind::OpenAiCompatible,
        base_url,
        api_key: "k".to_string(),
        models: vec![],
    }
}

#[tokio::test]
async fn ping_returns_latency_for_reachable_provider() {
    let upstream = spawn_upstream().await;
    let mut cfg = GatewayConfig::default();
    cfg.providers = vec![provider_cfg(upstream.base_url())];
    let (addr, handle) = spawn(app_state(cfg)).await;

    let resp = Client::new()
        .post(format!("http://{addr}/v1/providers/p/ping"))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let json: Value = resp.json().await.unwrap();
    assert_eq!(json["ok"].as_bool(), Some(true));
    assert!(json["latency_ms"].as_u64().is_some());
    assert_eq!(json["status"].as_u64(), Some(200));

    handle.abort();
}

#[tokio::test]
async fn ping_unreachable_returns_ok_false() {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let dead_addr = listener.local_addr().unwrap();
    drop(listener);

    let mut cfg = GatewayConfig::default();
    cfg.providers = vec![provider_cfg(format!("http://{}", dead_addr))];
    let (addr, handle) = spawn(app_state(cfg)).await;

    let resp = Client::new()
        .post(format!("http://{addr}/v1/providers/p/ping"))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 502);
    let json: Value = resp.json().await.unwrap();
    assert_eq!(json["ok"].as_bool(), Some(false));
    assert!(json["error"].as_str().is_some());

    handle.abort();
}

#[tokio::test]
async fn ping_unknown_provider_is_404() {
    let cfg = GatewayConfig::default();
    let (addr, handle) = spawn(app_state(cfg)).await;

    let resp = Client::new()
        .post(format!("http://{addr}/v1/providers/nope/ping"))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 404);

    handle.abort();
}

#[tokio::test]
async fn ping_requires_auth_when_remote() {
    let mut cfg = GatewayConfig::default();
    cfg.gateway_keys = vec!["k".into()];
    let (addr, handle) = spawn(app_state(cfg)).await;

    let resp = Client::new()
        .post(format!("http://{addr}/v1/providers/p/ping"))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 401);

    handle.abort();
}
