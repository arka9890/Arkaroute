//! Integration tests for the `/dashboard` assets and loopback access rule.

use std::net::SocketAddr;
use std::sync::{Arc, RwLock};

use omniroute_core::auth::{authorize, AuthError};
use omniroute_core::config::GatewayConfig;
use omniroute_core::server::build_router;
use omniroute_core::state::{AppState, Stats};
use reqwest::Client;
use tokio::task::JoinHandle;

fn app_state(cfg: GatewayConfig) -> AppState {
    AppState {
        config: Arc::new(RwLock::new(cfg)),
        stats: Arc::new(Stats::new()),
        client: reqwest::Client::new(),
    }
}

async fn spawn(state: AppState) -> (SocketAddr, JoinHandle<()>) {
    let router = build_router(state);
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let handle = tokio::spawn(async move {
        axum::serve(
            listener,
            router.into_make_service_with_connect_info::<SocketAddr>(),
        )
        .await
        .unwrap();
    });
    (addr, handle)
}

#[tokio::test]
async fn dashboard_serves_index_for_loopback_without_key() {
    let cfg = GatewayConfig::default();
    let (addr, handle) = spawn(app_state(cfg)).await;

    let resp = Client::new()
        .get(format!("http://{addr}/dashboard"))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let text = resp.text().await.unwrap();
    assert!(text.contains("<!DOCTYPE html>"), "{text}");

    handle.abort();
}

#[tokio::test]
async fn dashboard_401_for_remote_when_keys_configured() {
    let mut cfg = GatewayConfig::default();
    cfg.gateway_keys = vec!["k".into()];

    // Fail-closed: a router served without connect-info treats peer as non-loopback.
    let router = build_router(app_state(cfg.clone()));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let handle = tokio::spawn(async move {
        axum::serve(listener, router).await.unwrap();
    });

    let resp = Client::new()
        .get(format!("http://{addr}/dashboard"))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 401);

    handle.abort();

    // Direct authorize check for the remote case.
    let err = authorize(&cfg, &Default::default(), false).unwrap_err();
    assert_eq!(err, AuthError::MissingKey);
    assert_eq!(err.status(), 401);
}

#[tokio::test]
async fn unknown_asset_is_404() {
    let cfg = GatewayConfig::default();
    let (addr, handle) = spawn(app_state(cfg)).await;

    let resp = Client::new()
        .get(format!("http://{addr}/dashboard/does-not-exist.css"))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 404);

    handle.abort();
}
