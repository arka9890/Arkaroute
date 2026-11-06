//! Integration tests for the `/health` endpoint and the stats snapshot shape.

use std::net::SocketAddr;
use std::sync::{Arc, RwLock};

use omniroute_core::config::GatewayConfig;
use omniroute_core::server::build_router;
use omniroute_core::state::{AppState, LogLevel, Stats};
use tokio::task::JoinHandle;

fn app_state(cfg: GatewayConfig, stats: Stats) -> AppState {
    AppState {
        config: Arc::new(RwLock::new(cfg)),
        stats: Arc::new(stats),
        client: reqwest::Client::new(),
    }
}

/// Bind an ephemeral port, serve `build_router`, and return the address.
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

#[tokio::test]
async fn health_reports_snapshot_shape() {
    let (addr, handle) = spawn(app_state(GatewayConfig::default(), Stats::new())).await;

    let resp = reqwest::get(format!("http://{addr}/health")).await.unwrap();
    assert_eq!(resp.status(), 200);

    let json: serde_json::Value = resp.json().await.unwrap();
    assert_eq!(json["status"].as_str(), Some("ok"));
    assert_eq!(json["version"].as_str(), Some(omniroute_core::VERSION));
    assert!(json["uptime_seconds"].as_u64().is_some());
    assert_eq!(json["requests_total"].as_u64(), Some(0));
    assert_eq!(json["lan_enabled"].as_bool(), Some(false));
    assert_eq!(json["providers"].as_array(), Some(&Vec::new()));

    handle.abort();
}

#[tokio::test]
async fn health_counts_requests_and_logs() {
    let stats = Stats::new();
    stats.record_request();
    stats.record_request();
    stats.record_request();
    stats.record_success("openai", 10, 20, 5);
    stats.log(LogLevel::Info, "boot");

    let (addr, handle) = spawn(app_state(GatewayConfig::default(), stats)).await;

    let resp = reqwest::get(format!("http://{addr}/health")).await.unwrap();
    assert_eq!(resp.status(), 200);

    let json: serde_json::Value = resp.json().await.unwrap();
    assert_eq!(json["requests_total"].as_u64(), Some(3));
    assert_eq!(json["providers"][0]["id"].as_str(), Some("openai"));
    assert_eq!(json["providers"][0]["tokens_in"].as_u64(), Some(10));
    assert_eq!(json["logs"][0]["message"].as_str(), Some("boot"));

    handle.abort();
}
