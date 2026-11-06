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

use std::sync::{Arc, Mutex};
use std::time::Instant;

use futures_util::stream::BoxStream;
use futures_util::{stream, StreamExt};
use serde_json::Value;

use crate::config::{GatewayConfig, ProviderConfig};
use crate::protocol::ChatCompletionRequest;
use crate::providers::{adapter_for, UpstreamError, UpstreamFailure, UpstreamOutcome};
use crate::sse::SseEvent;
use crate::state::{AppState, LogLevel, Stats};

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

/// Latest token counts observed in yielded stream events (the usage chunk
/// arrives last on the wire).
#[derive(Debug, Default)]
struct UsageCell {
    tokens: Option<(u64, u64)>,
}

impl UsageCell {
    fn write(&mut self, tokens: Option<(u64, u64)>) {
        self.tokens = tokens;
    }

    fn read(&self) -> (u64, u64) {
        self.tokens.unwrap_or((0, 0))
    }
}

/// Lifecycle phase of a committed stream.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Phase {
    /// Yield the first event pulled before committing, then stream the rest.
    First,
    /// Pull the next event from the source stream.
    Active,
    /// The stream errored after the first byte.
    Failed,
    /// The stream ended normally.
    Done,
}

/// State of the committed-stream wrapper.
struct StreamState {
    phase: Phase,
    first: Option<SseEvent>,
    inner: BoxStream<'static, Result<SseEvent, UpstreamError>>,
    usage: Arc<Mutex<UsageCell>>,
    provider: String,
    stats: Arc<Stats>,
    model: String,
    started: Instant,
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

/// Pull one event from a committed stream, capturing usage; end the stream
/// with the outcome recorded.
async fn unfold_step(
    mut st: StreamState,
) -> Option<(Result<SseEvent, UpstreamError>, StreamState)> {
    if st.phase == Phase::Done || st.phase == Phase::Failed {
        return None;
    }
    let item = match st.phase {
        Phase::First => {
            st.phase = Phase::Active;
            Some(Ok(st.first.take().expect("first event pending")))
        }
        Phase::Active => st.inner.next().await,
        Phase::Done | Phase::Failed => return None,
    };
    let (item, phase) = match item {
        Some(Ok(ev)) => {
            if let Some(tokens) = usage_from_event(&ev) {
                st.usage.lock().expect("usage lock").write(Some(tokens));
            }
            (Some(Ok(ev)), Phase::Active)
        }
        Some(Err(e)) => {
            st.phase = Phase::Failed;
            st.stats.log(
                LogLevel::Warn,
                format!(
                    "provider {} stream failed after first byte: status {}",
                    st.provider,
                    e.status()
                ),
            );
            st.stats
                .record_failure(&st.provider, &format!("status {}", e.status()));
            (Some(Err(e)), Phase::Failed)
        }
        None => {
            st.phase = Phase::Done;
            (None, Phase::Done)
        }
    };
    if phase == Phase::Done {
        let tokens = st.usage.lock().expect("usage lock").read();
        st.stats.record_success(
            &st.provider,
            tokens.0,
            tokens.1,
            st.started.elapsed().as_millis() as u64,
        );
    }
    let next = StreamState {
        phase,
        first: None,
        inner: st.inner,
        usage: st.usage,
        provider: st.provider.clone(),
        stats: st.stats.clone(),
        model: st.model.clone(),
        started: st.started,
    };
    item.map(|i| (i, next))
}

/// Run the fallback chain for one request against the gateway state.
pub async fn execute(
    state: &AppState,
    req: ChatCompletionRequest,
) -> Result<RouteOutcome, RouteError> {
    let (attempts, request_timeout_ms, providers) = {
        let cfg = read_gateway_config(state);
        let attempts = plan(&req.model, &cfg)?;
        // Clone what the attempt loop needs out of the config before the first
        // `.await`: the `RwLockReadGuard` is not `Send`, and the future handed to
        // the Task 11 handler must be.
        let request_timeout_ms = cfg.request_timeout_ms;
        let providers: std::collections::HashMap<String, ProviderConfig> = cfg
            .providers
            .iter()
            .map(|p| (p.id.clone(), p.clone()))
            .collect();
        (attempts, request_timeout_ms, providers)
    };

    let started = Instant::now();
    let mut tried: Vec<String> = Vec::new();
    let mut failures: Vec<UpstreamError> = Vec::new();

    for attempt in attempts {
        tried.push(attempt.provider.clone());
        let provider_cfg = providers
            .get(&attempt.provider)
            .ok_or(ResolveError::UnknownProvider(attempt.provider.clone()))?;
        let adapter = adapter_for(provider_cfg, state.client.clone(), request_timeout_ms);
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
                    Some(Err(e)) => {
                        // Error before the first byte: fallback may apply.
                        record_failure(state, &e);
                        if !e.is_fallbackable() {
                            return Err(RouteError::Upstream(e));
                        }
                        failures.push(e);
                        continue;
                    }
                    Some(Ok(first_ev)) => {
                        // First byte reached the caller: the attempt is committed.
                        // The wrapper captures usage from every yielded event and
                        // records the attempt's success exactly once when the stream
                        // ends normally (final usage). A mid-stream error is surfaced
                        // through the stream and recorded as a failure.
                        let usage = Arc::new(Mutex::new(UsageCell::default()));
                        let stream: BoxStream<'static, Result<SseEvent, UpstreamError>> =
                            stream::unfold(
                                StreamState {
                                    phase: Phase::First,
                                    first: Some(first_ev),
                                    inner: src,
                                    usage,
                                    provider: attempt.provider.clone(),
                                    stats: state.stats.clone(),
                                    model: attempt.model.clone(),
                                    started,
                                },
                                unfold_step,
                            )
                            .boxed();
                        return Ok(RouteOutcome {
                            response: UpstreamOutcome::Stream(stream),
                            provider: attempt.provider,
                            model: attempt.model,
                            attempts: tried,
                        });
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

/// Token counts of a stream event payload, from its JSON body `usage`. Returns
/// `None` when the event carries no usage (e.g. a content chunk).
fn usage_from_event(ev: &SseEvent) -> Option<(u64, u64)> {
    let Ok(value) = serde_json::from_str::<Value>(&ev.data) else {
        return None;
    };
    let usage = value.get("usage").cloned().unwrap_or(Value::Null);
    if usage.is_null() {
        return None;
    }
    Some((
        usage
            .get("prompt_tokens")
            .and_then(Value::as_u64)
            .unwrap_or(0),
        usage
            .get("completion_tokens")
            .and_then(Value::as_u64)
            .unwrap_or(0),
    ))
}
