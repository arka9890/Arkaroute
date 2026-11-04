//! Gateway key authorization rules for `/v1/*` and `/health`.
//!
//! Task 11 wires [`authorize`] into middleware; this module is the rule
//! itself, independent of routing.

use axum::http::header::AUTHORIZATION;
use axum::http::HeaderMap;

use crate::config::GatewayConfig;

/// Why a request failed authorization.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum AuthError {
    /// Gateway keys are configured and no `Authorization` header was sent.
    #[error("missing gateway key")]
    MissingKey,

    /// The presented bearer key does not match any configured gateway key.
    #[error("invalid gateway key")]
    InvalidKey,

    /// No keys configured and the peer is not loopback.
    #[error("forbidden origin")]
    ForbiddenOrigin,
}

impl AuthError {
    /// The HTTP status code this error maps to: 401 / 401 / 403.
    pub fn status(&self) -> u16 {
        match self {
            AuthError::MissingKey => 401,
            AuthError::InvalidKey => 401,
            AuthError::ForbiddenOrigin => 403,
        }
    }
}

/// Authorize a request against `cfg`'s gateway key rules.
///
/// - Keys configured: a valid `Authorization: Bearer <key>` is required
///   regardless of peer. Missing header → [`AuthError::MissingKey`], wrong
///   key (or a non-bearer credential) → [`AuthError::InvalidKey`].
/// - No keys configured: allowed only from a loopback peer; otherwise
///   [`AuthError::ForbiddenOrigin`].
pub fn authorize(
    cfg: &GatewayConfig,
    headers: &HeaderMap,
    peer_is_loopback: bool,
) -> Result<(), AuthError> {
    if !cfg.gateway_keys.is_empty() {
        let raw = headers.get(AUTHORIZATION).ok_or(AuthError::MissingKey)?;
        let value = raw.to_str().map_err(|_| AuthError::InvalidKey)?;
        // Case-insensitive `Bearer` scheme compare; plain `==` over keys
        // is fine for v1 — keys live in an encrypted DB on device.
        let presented = value
            .split_once(' ')
            .filter(|(scheme, _)| scheme.eq_ignore_ascii_case("bearer"))
            .map(|(_, key)| key)
            .ok_or(AuthError::InvalidKey)?;
        if cfg.gateway_keys.iter().any(|k| k == presented) {
            return Ok(());
        }
        return Err(AuthError::InvalidKey);
    }

    if peer_is_loopback {
        return Ok(());
    }
    Err(AuthError::ForbiddenOrigin)
}
