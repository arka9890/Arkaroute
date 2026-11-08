//! Tests for gateway key authorization rules (`authorize`).

use axum::http::header::AUTHORIZATION;
use axum::http::{HeaderMap, HeaderValue};

use omniroute_core::auth::{authorize, AuthError};
use omniroute_core::config::GatewayConfig;

fn keys_config(keys: &[&str]) -> GatewayConfig {
    let mut cfg = GatewayConfig::default();
    cfg.gateway_keys = keys.iter().map(|k| k.to_string()).collect();
    cfg
}

fn bearer_header(value: &str) -> HeaderMap {
    let mut headers = HeaderMap::new();
    headers.insert(AUTHORIZATION, HeaderValue::from_str(value).unwrap());
    headers
}

#[test]
fn valid_bearer_key_is_accepted() {
    let cfg = keys_config(&["k"]);

    assert_eq!(
        authorize(&cfg, &bearer_header("Bearer k"), false),
        Ok(())
    );
    // The `Bearer` scheme compare is case-insensitive.
    assert_eq!(
        authorize(&cfg, &bearer_header("bearer k"), true),
        Ok(())
    );
    assert_eq!(
        authorize(&cfg, &bearer_header("BEARER k"), false),
        Ok(())
    );
}

#[test]
fn missing_key_with_keys_configured_is_401() {
    let cfg = keys_config(&["k"]);

    let err = authorize(&cfg, &HeaderMap::new(), false).unwrap_err();
    assert_eq!(err, AuthError::MissingKey);
    assert_eq!(err.status(), 401);
}

#[test]
fn wrong_key_is_401() {
    let cfg = keys_config(&["k"]);

    let err = authorize(&cfg, &bearer_header("Bearer nope"), false).unwrap_err();
    assert_eq!(err, AuthError::InvalidKey);
    assert_eq!(err.status(), 401);
}

#[test]
fn empty_keys_allow_loopback_only() {
    let cfg = keys_config(&[]);

    assert_eq!(authorize(&cfg, &HeaderMap::new(), true), Ok(()));

    let err = authorize(&cfg, &HeaderMap::new(), false).unwrap_err();
    assert_eq!(err, AuthError::ForbiddenOrigin);
    assert_eq!(err.status(), 403);
}

#[test]
fn empty_keys_reject_remote_even_when_lan_off() {
    // Regression: an open proxy must not be possible when LAN is off and no
    // keys are configured — remote peers are rejected regardless.
    let mut cfg = keys_config(&[]);
    cfg.lan_enabled = false;

    let err = authorize(&cfg, &HeaderMap::new(), false).unwrap_err();
    assert_eq!(err, AuthError::ForbiddenOrigin);
    assert_eq!(err.status(), 403);

    // ...and the same config still serves loopback.
    assert_eq!(authorize(&cfg, &HeaderMap::new(), true), Ok(()));
}

#[test]
fn empty_configured_key_cannot_authenticate() {
    let cfg = keys_config(&[""]);

    let err = authorize(&cfg, &bearer_header("Bearer "), false)
        .unwrap_err();
    assert_eq!(err.status(), 401);
}

#[test]
fn loopback_allowed_even_with_keys_configured() {
    let cfg = keys_config(&["k"]);

    // Regression: after a gateway key is created, loopback callers without a
    // Bearer header (dashboard fetch('/health'), app polling, simulator)
    // must not get 401.
    assert_eq!(authorize(&cfg, &HeaderMap::new(), true), Ok(()));
}

#[test]
fn nonloopback_requires_bearer_when_keys_configured() {
    let cfg = keys_config(&["k"]);

    let err = authorize(&cfg, &HeaderMap::new(), false).unwrap_err();
    assert_eq!(err, AuthError::MissingKey);
    assert_eq!(err.status(), 401);
}
