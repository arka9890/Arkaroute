//! Routing engine: model resolution, plan building, and the fallback walk.
//!
//! Resolution precedence (Task 10 brief / Review Focus #5): a `combo/<name>`
//! prefix resolves to that combo; an exact combo name beats a bare provider
//! model of the same name; `provider/model` resolves direct; a bare unknown
//! name falls back to the configured default combo or is unknown.
//!
//! Execution walks the plan attempt by attempt. A failure with a fallbackable
//! error (429 / 5xx / timeout / network) moves to the next attempt; any other
//! failure surfaces immediately as [`RouteError::Upstream`]. A fallback is only
//! ever taken before the first upstream byte reaches the client: the stream is
//! wrapped so the first yielded item commits the attempt, and a failure after
//! that just flows through to the caller.

use std::time::Instant;

use futures_util::stream::BoxStream;
use futures_util::{stream, StreamExt};
use serde_json::Value;

use crate::config::GatewayConfig;
use crate::protocol::ChatCompletionRequest;
use crate::providers::{adapter_for, UpstreamError, UpstreamFailure, UpstreamOutcome};
use crate::sse::SseEvent;
use crate::state::{AppState, LogLevel};

/// A resolved model: a fallback combo chain or a direct provider/model pair.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ResolvedModel {
    /// Run the named combo's steps in order.
    Combo(String),
    /// Skip the chain and talk to one provider directly.
    Direct { provider: String, model: String },
}

/// Why resolution failed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ResolveError {
    /// No combo matched and no default combo was configured.
    UnknownModel(String),
    /// A `combo/<name>` prefix (or default combo) named an unknown combo.
    UnknownCombo(String),
    /// A `provider/model` pair named an unknown provider.
    UnknownProvider(String),
}

/// One step of a plan: a provider serving one model.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Attempt {
    pub provider: String,
    pub model: String,
}

/// The outcome of a successful execution: the upstream response plus the
/// routing metadata around it.
#[derive(Debug)]
pub struct RouteOutcome {
    /// The upstream outcome: a canonical JSON body or a SSE event stream.
    pub response: UpstreamOutcome,
    /// The provider that produced the response.
    pub provider: String,
    /// The model the provider served.
    pub model: String,
    /// Provider ids tried, in order (the successful one is last).
    pub attempts: Vec<String>,
}

/// Everything [`execute`](fn.execute.html) can fail with.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RouteError {
    /// The model name could not be resolved.
    Resolve(ResolveError),
    /// Every step of the plan failed with a fallbackable error.
    AllFailed(Vec<UpstreamError>),
    /// The attempt failed with a non-fallbackable error, or a post-first-byte
    /// stream error was surfaced through the stream.
    Upstream(UpstreamError),
}

impl From<ResolveError> for RouteError {
    fn from(err: ResolveError) -> Self {
        RouteError::Resolve(err)
    }
}

/// Resolve a client-facing model string into a [`ResolvedModel`].
pub fn resolve_model(model: &str, cfg: &GatewayConfig) -> Result<ResolvedModel, ResolveError> {
    // `combo/<name>` prefix wins: it names an explicit fallback chain.
    if let Some(name) = model.strip_prefix("combo/") {
        return cfg
            .combo(name)
            .map(|c| ResolvedModel::Combo(c.name.clone()))
            .ok_or(ResolveError::UnknownCombo(name.to_string()));
    }
    // An exact combo name wins over a bare provider model of the same name.
    if let Some(combo) = cfg.combo(model) {
        return Ok(ResolvedModel::Combo(combo.name.clone()));
    }
    // `provider/model` → direct.
    if let Some((provider_id, upstream_model)) = model.split_once('/') {
        if cfg.provider(provider_id).is_none() {
            return Err(ResolveError::UnknownProvider(provider_id.to_string()));
        }
        return Ok(ResolvedModel::Direct {
            provider: provider_id.to_string(),
            model: upstream_model.to_string(),
        });
    }
    // Bare unknown name → configured default combo, else unknown.
    if let Some(name) = &cfg.default_combo {
        if let Some(combo) = cfg.combo(name) {
            return Ok(ResolvedModel::Combo(combo.name.clone()));
        }
    }
    Err(ResolveError::UnknownModel(model.to_string()))
}

/// Turn a model string into the ordered attempt chain.
pub fn plan(model: &str, cfg: &GatewayConfig) -> Result<Vec<Attempt>, ResolveError> {
    match resolve_model(model, cfg)? {
        ResolvedModel::Combo(name) => {
            let combo = cfg
                .combo(&name)
                .ok_or(ResolveError::UnknownCombo(name.clone()))?;
            Ok(combo
                .steps
                .iter()
                .map(|step| Attempt {
                    provider: step.provider.clone(),
                    model: step.model.clone(),
                })
                .collect())
        }
        ResolvedModel::Direct { provider, model } => Ok(vec![Attempt { provider, model }]),
    }
}

/// Run the fallback chain for one request against the gateway state.
pub async fn execute(
    state: &AppState,
    req: ChatCompletionRequest,
) -> Result<RouteOutcome, RouteError> {
    let cfg = read_gateway_config(state);
    let attempts = plan(&req.model, &cfg)?;
    let started = Instant::now();
    let mut tried: Vec<String> = Vec::new();
    let mut failures: Vec<UpstreamError> = Vec::new();

    for attempt in attempts {
        tried.push(attempt.provider.clone());
        let provider_cfg = cfg
            .provider(&attempt.provider)
            .ok_or(ResolveError::UnknownProvider(attempt.provider.clone()))?;
        let adapter = adapter_for(provider_cfg, state.client.clone(), cfg.request_timeout_ms);
        let outcome = adapter.chat(&req).await;
        match outcome {
            Err(e) => {
                record_failure(state, &e);
                if !e.is_fallbackable() {
                    // A client error (400/401/404) would fail identically on the
                    // next step — surface it, never fall back.
                    return Err(RouteError::Upstream(e));
                }
                failures.push(e);
                continue;
            }
            Ok(UpstreamOutcome::Json(value)) => {
                let (tokens_in, tokens_out) = usage_from_json(&value);
                state.stats.log(
                    LogLevel::Info,
                    format!(
                        "provider {} served model {}",
                        attempt.provider, attempt.model
                    ),
                );
                state.stats.record_success(
                    &attempt.provider,
                    tokens_in,
                    tokens_out,
                    started.elapsed().as_millis() as u64,
                );
                return Ok(RouteOutcome {
                    response: UpstreamOutcome::Json(value),
                    provider: attempt.provider,
                    model: attempt.model,
                    attempts: tried,
                });
            }
            Ok(UpstreamOutcome::Stream(s)) => {
                let mut src: BoxStream<'static, Result<SseEvent, UpstreamError>> = s;
                let first = src.next().await;
                match first {
                    Some(Ok(first_ev)) => {
                        // First byte reached the caller: the attempt is committed,
                        // the stream continues after this item and later errors
                        // just surface to the client (no fallback).
                        let (tokens_in, tokens_out) = usage_from_event(&first_ev);
                        let stream =
                            stream::once(async move { Ok::<SseEvent, UpstreamError>(first_ev) })
                                .chain(src)
                                .boxed();
                        state.stats.log(
                            LogLevel::Info,
                            format!(
                                "provider {} served model {}",
                                attempt.provider, attempt.model
                            ),
                        );
                        state.stats.record_success(
                            &attempt.provider,
                            tokens_in,
                            tokens_out,
                            started.elapsed().as_millis() as u64,
                        );
                        return Ok(RouteOutcome {
                            response: UpstreamOutcome::Stream(stream),
                            provider: attempt.provider,
                            model: attempt.model,
                            attempts: tried,
                        });
                    }
                    Some(Err(e)) => {
                        // Error before the first byte: fallback may apply.
                        record_failure(state, &e);
                        if !e.is_fallbackable() {
                            return Err(RouteError::Upstream(e));
                        }
                        failures.push(e);
                        continue;
                    }
                    None => {
                        // The upstream closed before yielding anything.
                        let e = UpstreamError {
                            provider: attempt.provider.clone(),
                            failure: UpstreamFailure::Network(
                                "upstream stream ended before the first event".to_string(),
                            ),
                        };
                        record_failure(state, &e);
                        if !e.is_fallbackable() {
                            return Err(RouteError::Upstream(e));
                        }
                        failures.push(e);
                        continue;
                    }
                }
            }
        }
    }
    Err(RouteError::AllFailed(failures))
}

/// Read the gateway config, recovering from a poisoned lock.
fn read_gateway_config(state: &AppState) -> std::sync::RwLockReadGuard<'_, GatewayConfig> {
    state
        .config
        .read()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Record one failed attempt against the stats and log it.
fn record_failure(state: &AppState, e: &UpstreamError) {
    state.stats.log(
        LogLevel::Warn,
        format!("provider {} failed: status {}", e.provider, e.status()),
    );
    state
        .stats
        .record_failure(&e.provider, &format!("status {}", e.status()));
}

/// Token counts of a canonical JSON body, from its top-level `usage`.
fn usage_from_json(value: &Value) -> (u64, u64) {
    let usage = value.get("usage").cloned().unwrap_or(Value::Null);
    (
        usage
            .get("prompt_tokens")
            .and_then(Value::as_u64)
            .unwrap_or(0),
        usage
            .get("completion_tokens")
            .and_then(Value::as_u64)
            .unwrap_or(0),
    )
}

/// Token counts of a stream event payload, from its JSON body `usage`.
fn usage_from_event(ev: &SseEvent) -> (u64, u64) {
    let Ok(value) = serde_json::from_str::<Value>(&ev.data) else {
        return (0, 0);
    };
    usage_from_json(&value)
}
