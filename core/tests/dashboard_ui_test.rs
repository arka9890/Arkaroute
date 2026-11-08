//! Integration tests for the self-contained claymorphism dashboard HTML.

use std::net::SocketAddr;
use std::sync::{Arc, RwLock};

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
async fn dashboard_is_self_contained() {
    let cfg = GatewayConfig::default();
    let (addr, handle) = spawn(app_state(cfg)).await;

    let resp = Client::new()
        .get(format!("http://{addr}/dashboard/"))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let text = resp.text().await.unwrap();
    assert!(!text.contains("src=\"http"), "{text}");
    assert!(!text.contains("href=\"http"), "{text}");
    assert!(!text.contains("cdn."), "{text}");

    handle.abort();
}

#[tokio::test]
async fn dashboard_reads_health_endpoint() {
    let cfg = GatewayConfig::default();
    let (addr, handle) = spawn(app_state(cfg)).await;

    let resp = Client::new()
        .get(format!("http://{addr}/dashboard/"))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let text = resp.text().await.unwrap();
    assert!(text.contains("fetch('/health')"), "{text}");
    assert!(text.contains("uptime_seconds"), "{text}");

    handle.abort();
}

#[tokio::test]
async fn dashboard_copy_is_indonesian() {
    let cfg = GatewayConfig::default();
    let (addr, handle) = spawn(app_state(cfg)).await;

    let resp = Client::new()
        .get(format!("http://{addr}/dashboard/"))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let text = resp.text().await.unwrap();
    assert!(text.contains("Permintaan"), "{text}");
    assert!(text.contains("Token"), "{text}");

    handle.abort();
}
