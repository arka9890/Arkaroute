//! Tests for `GatewayConfig` defaults, persistence, and validation.

use omniroute_core::config::{Combo, ComboStep, GatewayConfig, ProviderConfig, ProviderKind};
use omniroute_core::error::ConfigError;

fn sample_config() -> GatewayConfig {
    let mut cfg = GatewayConfig::default();
    cfg.providers.push(ProviderConfig {
        id: "openai".into(),
        kind: ProviderKind::OpenAiCompatible,
        base_url: "https://api.openai.com/v1".into(),
        api_key: "sk-test".into(),
        models: vec!["gpt-5".into()],
    });
    cfg.combos.push(Combo {
        name: "translate".into(),
        steps: vec![ComboStep {
            provider: "openai".into(),
            model: "gpt-5".into(),
        }],
    });
    cfg.default_combo = Some("translate".into());
    cfg
}

fn temp_config_path(name: &str) -> std::path::PathBuf {
    let mut path = std::env::temp_dir();
    path.push(format!("omniroute-core-test-{}-{}.json", name, std::process::id()));
    path
}

#[test]
fn default_config_is_valid_and_loopback() {
    let cfg = GatewayConfig::default();
    assert!(cfg.validate().is_ok());
    assert_eq!(cfg.bind, "127.0.0.1");
    assert_eq!(cfg.port, 20128);
    assert!(!cfg.lan_enabled);
}

#[test]
fn load_save_roundtrip_preserves_combos() {
    let cfg = sample_config();
    let path = temp_config_path("roundtrip");
    cfg.save(&path).expect("save should succeed");
    let loaded = GatewayConfig::load(&path).expect("load should succeed");
    let _ = std::fs::remove_file(&path);

    assert_eq!(loaded.providers, cfg.providers);
    assert_eq!(loaded.combos, cfg.combos);
    assert_eq!(loaded.default_combo, cfg.default_combo);
    assert_eq!(loaded.bind, cfg.bind);
    assert_eq!(loaded.port, cfg.port);
}

#[test]
fn validate_rejects_lan_without_keys() {
    let mut cfg = GatewayConfig::default();
    cfg.lan_enabled = true;
    cfg.gateway_keys.clear();
    match cfg.validate() {
        Err(ConfigError::Invalid(msg)) => assert!(msg.contains("gateway"), "message: {msg}"),
        other => panic!("expected Err(Invalid), got {other:?}"),
    }
}

#[test]
fn provider_and_combo_lookup_by_id() {
    let cfg = sample_config();
    assert!(cfg.provider("openai").is_some());
    assert!(cfg.provider("nope").is_none());
    assert!(cfg.combo("translate").is_some());
    assert!(cfg.combo("nope").is_none());
}
