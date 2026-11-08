//! Gateway configuration: model, persistence, and validation.

use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::error::ConfigError;

/// The provider wire protocol spoken by an upstream.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub enum ProviderKind {
    /// OpenAI-compatible `/v1/chat/completions` and Responses upstreams.
    #[default]
    OpenAiCompatible,
    /// Anthropic `/v1/messages` upstreams.
    Anthropic,
}

/// One configured upstream provider.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProviderConfig {
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub kind: ProviderKind,
    #[serde(default)]
    pub base_url: String,
    #[serde(default)]
    pub api_key: String,
    #[serde(default)]
    pub models: Vec<String>,
}

/// A single step in a chain combo: one provider serving one model.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ComboStep {
    #[serde(default)]
    pub provider: String,
    #[serde(default)]
    pub model: String,
}

fn default_true() -> bool {
    true
}

/// A named chain of provider/model steps.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Combo {
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub steps: Vec<ComboStep>,
    #[serde(default = "default_true")]
    pub enabled: bool,
}

/// Root gateway configuration, persisted as JSON.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct GatewayConfig {
    #[serde(default = "default_bind")]
    pub bind: String,
    #[serde(default = "default_port")]
    pub port: u16,
    #[serde(default)]
    pub lan_enabled: bool,
    #[serde(default)]
    pub gateway_keys: Vec<String>,
    #[serde(default)]
    pub default_combo: Option<String>,
    #[serde(default)]
    pub providers: Vec<ProviderConfig>,
    #[serde(default)]
    pub combos: Vec<Combo>,
    #[serde(default = "default_request_timeout_ms")]
    pub request_timeout_ms: u64,
    #[serde(default = "default_stream_idle_timeout_ms")]
    pub stream_idle_timeout_ms: u64,
}

fn default_bind() -> String {
    "127.0.0.1".to_string()
}

fn default_port() -> u16 {
    20128
}

fn default_request_timeout_ms() -> u64 {
    600_000
}

fn default_stream_idle_timeout_ms() -> u64 {
    60_000
}

impl Default for GatewayConfig {
    fn default() -> Self {
        Self {
            bind: default_bind(),
            port: default_port(),
            lan_enabled: false,
            gateway_keys: Vec::new(),
            default_combo: None,
            providers: Vec::new(),
            combos: Vec::new(),
            request_timeout_ms: default_request_timeout_ms(),
            stream_idle_timeout_ms: default_stream_idle_timeout_ms(),
        }
    }
}

impl GatewayConfig {
    /// Load a config from `path`. Missing fields fall back to defaults.
    pub fn load(path: &Path) -> Result<Self, ConfigError> {
        let raw = std::fs::read_to_string(path)?;
        let cfg = serde_json::from_str(&raw)?;
        Ok(cfg)
    }

    /// Write this config to `path` as pretty-printed JSON.
    pub fn save(&self, path: &Path) -> Result<(), ConfigError> {
        let json = serde_json::to_string_pretty(self)?;
        std::fs::write(path, json)?;
        Ok(())
    }

    /// Validate structural invariants the gateway relies on.
    pub fn validate(&self) -> Result<(), ConfigError> {
        if self.bind.is_empty() {
            return Err(ConfigError::Invalid("bind must not be empty".into()));
        }
        if self.port == 0 {
            return Err(ConfigError::Invalid("port must not be 0".into()));
        }
        if self.lan_enabled && self.gateway_keys.is_empty() {
            return Err(ConfigError::Invalid(
                "LAN enabled but no gateway key configured".into(),
            ));
        }
        let mut provider_ids: Vec<&str> = Vec::with_capacity(self.providers.len());
        for provider in &self.providers {
            if provider_ids.contains(&provider.id.as_str()) {
                return Err(ConfigError::Invalid(format!(
                    "duplicate provider id \"{}\"",
                    provider.id
                )));
            }
            provider_ids.push(&provider.id);
        }
        let mut combo_names: Vec<&str> = Vec::with_capacity(self.combos.len());
        for combo in &self.combos {
            if combo_names.contains(&combo.name.as_str()) {
                return Err(ConfigError::Invalid(format!(
                    "duplicate combo name \"{}\"",
                    combo.name
                )));
            }
            combo_names.push(&combo.name);
        }
        for combo in &self.combos {
            for step in &combo.steps {
                if self.provider(&step.provider).is_none() {
                    return Err(ConfigError::Invalid(format!(
                        "combo \"{}\" references unknown provider \"{}\"",
                        combo.name, step.provider
                    )));
                }
            }
        }
        Ok(())
    }

    /// Look up a configured provider by id.
    pub fn provider(&self, id: &str) -> Option<&ProviderConfig> {
        self.providers.iter().find(|p| p.id == id)
    }

    /// Look up a configured combo by name.
    pub fn combo(&self, name: &str) -> Option<&Combo> {
        self.combos.iter().find(|c| c.name == name)
    }
}
