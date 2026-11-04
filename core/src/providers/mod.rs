//! Provider adapters: the [`ProviderAdapter`] trait, the shared upstream
//! error types, and the [`adapter_for`] registry.

use crate::config::{ProviderConfig, ProviderKind};
use crate::protocol::ChatCompletionRequest;
use crate::sse::SseEvent;

pub mod anthropic;
pub mod openai_compat;

/// Why an upstream call failed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum UpstreamFailure {
    /// The upstream answered with a non-2xx status; `body` is its error body.
    Http { status: u16, body: String },
    /// The call exceeded the configured request timeout.
    Timeout,
    /// The upstream could not be reached or spoke a protocol we cannot relay
    /// (connect refused, DNS, TLS, mid-body I/O, a 2xx that is not JSON).
    Network(String),
}

/// An upstream failure tagged with the provider that produced it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UpstreamError {
    pub provider: String,
    pub failure: UpstreamFailure,
}

impl UpstreamError {
    /// The HTTP status this failure maps to on the gateway's own wire:
    /// `Http` → its status, `Timeout` → 504, `Network` → 502.
    pub fn status(&self) -> u16 {
        match &self.failure {
            UpstreamFailure::Http { status, .. } => *status,
            UpstreamFailure::Timeout => 504,
            UpstreamFailure::Network(_) => 502,
        }
    }

    /// Whether the routing engine may walk to the next combo step after this
    /// failure. The spec's fallback triggers are 429, 5xx and timeouts; a
    /// client-side 4xx means the same request would fail on the next step too.
    pub fn is_fallbackable(&self) -> bool {
        match &self.failure {
            UpstreamFailure::Http { status, .. } => *status == 429 || (500..=599).contains(status),
            UpstreamFailure::Timeout => true,
            UpstreamFailure::Network(_) => true,
        }
    }
}

/// What an upstream produced for one [`ProviderAdapter::chat`] call.
pub enum UpstreamOutcome {
    /// The canonical OpenAI response body, verbatim.
    Json(serde_json::Value),
    /// Canonical SSE events without the trailing `[DONE]` sentinel — the
    /// caller re-appends it.
    Stream(futures_util::stream::BoxStream<'static, Result<SseEvent, UpstreamError>>),
}

/// One upstream wire protocol, as the routing engine sees it.
#[async_trait::async_trait]
pub trait ProviderAdapter: Send + Sync {
    /// Execute one chat completion against the upstream.
    async fn chat(&self, req: &ChatCompletionRequest) -> Result<UpstreamOutcome, UpstreamError>;
}

/// Build the adapter matching `cfg.kind`.
pub fn adapter_for(cfg: &ProviderConfig, client: reqwest::Client) -> Box<dyn ProviderAdapter> {
    match &cfg.kind {
        ProviderKind::OpenAiCompatible => {
            Box::new(openai_compat::OpenAiCompatAdapter::new(cfg, client))
        }
        // Replaced by the Anthropic adapter in Task 9; no test constructs an
        // Anthropic provider until then.
        ProviderKind::Anthropic => todo!("Anthropic adapter lands in Task 9"),
    }
}
