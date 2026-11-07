//! Integration tests for `core::lifecycle::ServerHandle`.

use omniroute_core::config::GatewayConfig;
use omniroute_core::lifecycle::{LifecycleError, ServerHandle};

fn cfg_for(port: u16) -> GatewayConfig {
    let mut cfg = GatewayConfig::default();
    cfg.bind = "127.0.0.1".into();
    cfg.port = port;
    cfg
}

/// Blocking GET following the existing `core/tests/` pattern
/// (`reqwest::Client` async calls) run on a runtime created inside the test.
fn blocking_get(url: &str) -> Result<(u16, String), reqwest::Error> {
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    rt.block_on(async {
        let resp = reqwest::get(url).await?;
        let status = resp.status().as_u16();
        let body = resp.text().await?;
        Ok((status, body))
    })
}

#[test]
fn start_binds_and_serves_health() {
    let cfg = cfg_for(41011);
    let handle = ServerHandle::start(cfg).expect("start should succeed");
    assert_eq!(handle.local_addr().port(), 41011);

    let (status, body) = blocking_get("http://127.0.0.1:41011/health").expect("GET should succeed");
    assert_eq!(status, 200);
    assert!(body.contains("status"), "body missing status: {body}");

    handle.shutdown();
}

#[test]
fn shutdown_stops_listener() {
    let cfg = cfg_for(41012);
    let handle = ServerHandle::start(cfg).expect("start should succeed");
    let (status, _) = blocking_get("http://127.0.0.1:41012/health").expect("GET should succeed");
    assert_eq!(status, 200);

    handle.shutdown();

    let after = blocking_get("http://127.0.0.1:41012/health");
    assert!(after.is_err(), "GET after shutdown must fail, got: {after:?}");
}

#[test]
fn restart_after_shutdown_rebinds_same_port() {
    let cfg = cfg_for(41013);
    let handle = ServerHandle::start(cfg.clone()).expect("first start should succeed");
    handle.shutdown();

    let handle2 = ServerHandle::start(cfg).expect("restart should succeed");
    let (status, body) = blocking_get("http://127.0.0.1:41013/health").expect("GET should succeed");
    assert_eq!(status, 200);
    assert!(body.contains("status"), "body missing status: {body}");

    handle2.shutdown();
}

#[test]
fn invalid_bind_address_errors() {
    let mut cfg = cfg_for(41014);
    cfg.bind = "not-an-ip".into();
    match ServerHandle::start(cfg) {
        Err(LifecycleError::Bind(_)) => {}
        Ok(handle) => {
            handle.shutdown();
            panic!("expected Bind error, start succeeded");
        }
    }
}
