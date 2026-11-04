//! Gateway errors surfaced to callers and the dashboard.

use thiserror::Error;

/// Errors arising from loading, saving, or validating [`crate::config::GatewayConfig`].
#[derive(Debug, Error)]
pub enum ConfigError {
    /// Reading or writing the config file failed.
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),

    /// The config file is not valid JSON.
    #[error("failed to parse config: {0}")]
    Parse(#[from] serde_json::Error),

    /// The config is well-formed but invalid.
    #[error("invalid config: {0}")]
    Invalid(String),
}
