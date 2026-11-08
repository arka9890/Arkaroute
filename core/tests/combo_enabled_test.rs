//! Tests for the `Combo.enabled` flag: back-compat default, routing skip, and
//! `/v1/models` filtering.

use std::net::SocketAddr;
use std::sync::Arc;

use omniroute_core::config::{Combo, ComboStep, GatewayConfig};
use omniroute_core::routing::{resolve_model, ResolveError};
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

fn gateway_with_combos(combos: Vec<Combo>) -> GatewayConfig {
    let mut cfg = GatewayConfig::default();
    cfg.combos = combos;
    cfg
}

#[tokio::test]
async fn disabled_combo_not_in_models() {
    let cfg = gateway_with_combos(vec![
        Combo {
            name: "enabled-combo".into(),
            steps: vec![],
            enabled: true,
        },
        Combo {
            name: "disabled-combo".into(),
            steps: vec![],
            enabled: false,
        },
    ]);
    let (addr, handle) = spawn(app_state(cfg)).await;
    let resp = Client::new()
        .get(format!("http://{addr}/v1/models"))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), 200);
    let json: Value = resp.json().await.unwrap();
    let ids: Vec<&str> = json["data"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(|m| m["id"].as_str())
        .collect();
    assert!(ids.contains(&"enabled-combo"), "{ids:?}");
    assert!(!ids.contains(&"disabled-combo"), "{ids:?}");
    handle.abort();
}

#[test]
fn disabled_combo_resolves_to_unknown_model() {
    let cfg = gateway_with_combos(vec![Combo {
        name: "disabled-combo".into(),
        steps: vec![ComboStep {
            provider: "p".into(),
            model: "m".into(),
        }],
        enabled: false,
    }]);
    let err = resolve_model("disabled-combo", &cfg).unwrap_err();
    assert_eq!(err, ResolveError::UnknownModel("disabled-combo".into()));
}

#[test]
fn enabled_flag_defaults_true_for_old_config() {
    let combo: Combo = serde_json::from_str(r#"{"name":"x","steps":[]}"#).unwrap();
    assert!(combo.enabled);
}