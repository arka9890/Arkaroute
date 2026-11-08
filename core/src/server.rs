//! HTTP router construction and handlers.

use std::net::SocketAddr;

use axum::body::Bytes;
use axum::extract::{ConnectInfo, FromRequestParts, Path, State};use axum::extract::connect_info::MockConnectInfo;
use axum::http::request::Parts;
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use futures_util::StreamExt;
use serde_json::json;
use tokio::time::{timeout, Duration};

use crate::auth::{authorize, AuthError};
use crate::config::{GatewayConfig, ProviderKind};
use crate::protocol::ChatCompletionRequest;
use crate::providers;
use crate::routing::{execute, RouteError};
use crate::sse::{format_event, DONE};
use crate::state::AppState;

/// Build the gateway router.
pub fn build_router(state: AppState) -> Router {
    Router::new()
        .route("/health", get(health_handler))
        .route("/v1/chat/completions", post(chat_completions_handler))
        .route("/v1/messages", post(messages_handler))
        .route("/v1/models", get(models_handler))
        .route("/v1/providers/{id}/ping", post(provider_ping_handler))
        .route("/providers/{id}/ping", post(provider_ping_handler))
        .route("/dashboard", get(dashboard_index_handler))
        .route("/dashboard/", get(dashboard_root_handler))
        .route("/dashboard/{*path}", get(dashboard_asset_handler))
        .with_state(state)
}

struct PeerAddr(Option<SocketAddr>);

impl<S> FromRequestParts<S> for PeerAddr
where
    S: Send + Sync,
{
    type Rejection = std::convert::Infallible;

    async fn from_request_parts(parts: &mut Parts, _state: &S) -> Result<Self, Self::Rejection> {
        if let Some(ci) = parts.extensions.get::<ConnectInfo<SocketAddr>>() {
            return Ok(PeerAddr(Some(ci.0)));
        }
        if let Some(mc) = parts.extensions.get::<MockConnectInfo<SocketAddr>>() {
            return Ok(PeerAddr(Some(mc.0)));
        }
        use std::sync::atomic::{AtomicBool, Ordering};
        static WARNED: AtomicBool = AtomicBool::new(false);
        if !WARNED.swap(true, Ordering::Relaxed) {
            tracing::warn!("no peer address source present in request extensions; treating peer as non-loopback");
        }
        Ok(PeerAddr(None))
    }
}

fn auth_error_body(err: AuthError) -> (StatusCode, Json<serde_json::Value>) {
    let status = err.status();
    let type_str = if status >= 500 {
        "server_error"
    } else {
        "invalid_request_error"
    };
    let message = err.to_string();
    (
        StatusCode::from_u16(status).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR),
        Json(json!({"error": {"message": message, "type": type_str, "code": status}})),
    )
}

fn openai_error(status: u16, message: impl Into<String>) -> (StatusCode, Json<serde_json::Value>) {
    let type_str = if status >= 500 {
        "server_error"
    } else {
        "invalid_request_error"
    };
    let code = if status == 404 {
        json!("model_not_found")
    } else {
        json!(status)
    };
    (
        StatusCode::from_u16(status).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR),
        Json(json!({"error": {"message": message.into(), "type": type_str, "code": code}})),
    )
}

fn anthropic_error_type(status: u16) -> &'static str {
    match status {
        401 => "authentication_error",
        403 => "permission_error",
        404 => "not_found_error",
        400 => "invalid_request_error",
        502 | 504 => "api_error",
        _ if status >= 500 => "api_error",
        _ => "invalid_request_error",
    }
}

fn anthropic_error(status: u16, message: impl Into<String>) -> (StatusCode, Json<serde_json::Value>) {
    (
        StatusCode::from_u16(status).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR),
        Json(json!({"type": "error", "error": {"type": anthropic_error_type(status), "message": message.into()}})),
    )
}

/// `GET /health` -> 200 JSON snapshot of live stats.
async fn health_handler(
    peer: PeerAddr,
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Response {
    let cfg = read_cfg(&state);
    if let Err(e) = authorize(&cfg, &headers, peer.0.map(|a| a.ip().is_loopback()).unwrap_or(false)) {
        return auth_error_body(e).into_response();
    }
    let snapshot = state.stats.snapshot(&cfg);
    (StatusCode::OK, Json(snapshot)).into_response()
}

async fn models_handler(
    peer: PeerAddr,
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Response {
    let cfg = read_cfg(&state);
    if let Err(e) = authorize(&cfg, &headers, peer.0.map(|a| a.ip().is_loopback()).unwrap_or(false)) {
        return auth_error_body(e).into_response();
    }
    let mut data = Vec::new();
    for provider in &cfg.providers {
        for model in &provider.models {
            data.push(json!({
                "id": format!("{}/{}", provider.id, model),
                "object": "model",
                "owned_by": provider.id,
            }));
        }
    }
    for combo in &cfg.combos {
        data.push(json!({
            "id": combo.name,
            "object": "model",
            "owned_by": "omniroute-combo",
        }));
    }
    (StatusCode::OK, Json(json!({"object": "list", "data": data}))).into_response()
}

/// `GET /dashboard` -> 302 redirect to `/dashboard/`.
async fn dashboard_index_handler(
    peer: PeerAddr,
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Response {
    let cfg = read_cfg(&state);
    let peer_is_loopback = peer.0.map(|a| a.ip().is_loopback()).unwrap_or(false);
    if !peer_is_loopback {
        if let Err(e) = authorize(&cfg, &headers, false) {
            return auth_error_body(e).into_response();
        }
    }
    (StatusCode::FOUND, [(axum::http::header::LOCATION, "/dashboard/")]).into_response()
}

/// `GET /dashboard/` -> embedded index asset.
async fn dashboard_root_handler(
    peer: PeerAddr,
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Response {
    let cfg = read_cfg(&state);
    let peer_is_loopback = peer.0.map(|a| a.ip().is_loopback()).unwrap_or(false);
    if !peer_is_loopback {
        if let Err(e) = authorize(&cfg, &headers, false) {
            return auth_error_body(e).into_response();
        }
    }
    let _ = headers;
    let _ = cfg;
    crate::dashboard::serve_asset("")
}

/// `GET /dashboard/*` -> embedded asset.
async fn dashboard_asset_handler(
    peer: PeerAddr,
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(path): Path<String>,
) -> Response {
    let cfg = read_cfg(&state);
    let peer_is_loopback = peer.0.map(|a| a.ip().is_loopback()).unwrap_or(false);
    if !peer_is_loopback {
        if let Err(e) = authorize(&cfg, &headers, false) {
            return auth_error_body(e).into_response();
        }
    }
    let _ = headers;
    let _ = cfg;
    crate::dashboard::serve_asset(&path)
}

async fn chat_completions_handler(
    peer: PeerAddr,
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let cfg = read_cfg(&state);
    if let Err(e) = authorize(&cfg, &headers, peer.0.map(|a| a.ip().is_loopback()).unwrap_or(false)) {
        return auth_error_body(e).into_response();
    }

    let req: ChatCompletionRequest = match serde_json::from_slice(&body) {
        Ok(r) => r,
        Err(_) => return openai_error(400, "invalid request body").into_response(),
    };

    let idle_ms = read_cfg(&state).stream_idle_timeout_ms;

    match execute(&state, req).await {
        Ok(outcome) => match outcome.response {
            providers::UpstreamOutcome::Json(v) => {
                (StatusCode::OK, Json(v)).into_response()
            }
            providers::UpstreamOutcome::Stream(s) => {
                let stream = make_stream(s, idle_ms);
                Response::builder()
                    .status(StatusCode::OK)
                    .header("content-type", "text/event-stream")
                    .header("cache-control", "no-cache")
                    .body(axum::body::Body::from_stream(stream))
                    .unwrap()
            }
        },
        Err(err) => match err {
            RouteError::Resolve(crate::routing::ResolveError::UnknownModel(m)) => {
                openai_error(404, format!("The model `{m}` does not exist")).into_response()
            }
            RouteError::Resolve(_) => openai_error(404, "model not found").into_response(),
            RouteError::AllFailed(errs) => {
                let msg = errs
                    .first()
                    .map(|e| format!("all providers failed: {}", e.status()))
                    .unwrap_or_else(|| "all providers failed".to_string());
                openai_error(502, msg).into_response()
            }
            RouteError::Upstream(e) => {
                let status = e.status();
                let msg = match &e.failure {
                    providers::UpstreamFailure::Http { body, .. } => body.clone(),
                    providers::UpstreamFailure::Timeout => "upstream timed out".to_string(),
                    providers::UpstreamFailure::Network(m) => m.clone(),
                };
                openai_error(status, msg).into_response()
            }
        },
    }
}

async fn messages_handler(
    peer: PeerAddr,
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let cfg = read_cfg(&state);
    if let Err(e) = authorize(&cfg, &headers, peer.0.map(|a| a.ip().is_loopback()).unwrap_or(false)) {
        return anthropic_error(e.status(), e.to_string()).into_response();
    }

    let parsed_value: serde_json::Value = match serde_json::from_slice(&body) {
        Ok(v) => v,
        Err(_) => return anthropic_error(400, "invalid request body").into_response(),
    };
    let req = match crate::translate::inbound_messages(parsed_value) {
        Ok(r) => r,
        Err(_) => return anthropic_error(400, "invalid request body").into_response(),
    };

    let idle_ms = read_cfg(&state).stream_idle_timeout_ms;

    match execute(&state, req.clone()).await {
        Ok(outcome) => match outcome.response {
            providers::UpstreamOutcome::Json(v) => {
                let translated = crate::translate::o2a_response(v, &req.model);
                (StatusCode::OK, Json(translated)).into_response()
            }
            providers::UpstreamOutcome::Stream(s) => {
                let stream = make_anthropic_stream(s, idle_ms, req.model.clone());
                Response::builder()
                    .status(StatusCode::OK)
                    .header("content-type", "text/event-stream")
                    .header("cache-control", "no-cache")
                    .body(axum::body::Body::from_stream(stream))
                    .unwrap()
            }
        },
        Err(err) => match err {
            RouteError::Resolve(crate::routing::ResolveError::UnknownModel(m)) => {
                anthropic_error(404, format!("The model `{m}` does not exist")).into_response()
            }
            RouteError::Resolve(_) => anthropic_error(404, "model not found").into_response(),
            RouteError::AllFailed(errs) => {
                let msg = errs
                    .first()
                    .map(|e| format!("all providers failed: {}", e.status()))
                    .unwrap_or_else(|| "all providers failed".to_string());
                anthropic_error(502, msg).into_response()
            }
            RouteError::Upstream(e) => {
                let status = e.status();
                let msg = match &e.failure {
                    providers::UpstreamFailure::Http { body, .. } => body.clone(),
                    providers::UpstreamFailure::Timeout => "upstream timed out".to_string(),
                    providers::UpstreamFailure::Network(m) => m.clone(),
                };
                anthropic_error(status, msg).into_response()
            }
        },
    }
}

fn make_anthropic_stream(
    mut src: futures_util::stream::BoxStream<'static, Result<crate::sse::SseEvent, providers::UpstreamError>>,
    idle_ms: u64,
    model: String,
) -> impl futures_util::Stream<Item = Result<Bytes, std::convert::Infallible>> {
    async_stream::stream! {
        let idle = Duration::from_millis(idle_ms);
        let mut events: Vec<crate::sse::SseEvent> = Vec::new();
        let mut consumed: usize = 0;
        loop {
            match timeout(idle, src.next()).await {
                Ok(Some(Ok(ev))) => {
                    events.push(ev);

                    let out = crate::translate::stream::o2a_stream(events.clone(), &model);
                    let trailer = trailing_finalization_count(&out);
                    // Defer the finalization trailer (message_delta + message_stop)
                    // until stream end so usage chunks that arrive after the
                    // finish chunk are folded into message_delta.
                    let emit_count = out.len().saturating_sub(trailer);

                    if emit_count < consumed {
                        // Fold is not prefix-stable: surface an error rather than
                        // re-emitting.
                        tracing::warn!("anthropic stream translation lost events");
                        let frame = crate::sse::SseEvent {
                            event: Some("error".to_string()),
                            data: serde_json::json!({
                                "type": "error",
                                "error": {
                                    "type": "api_error",
                                    "message": "stream translation lost events"
                                }
                            })
                            .to_string(),
                        };
                        yield Ok(Bytes::from(format_event(&frame)));
                        break;
                    }
                    if emit_count > consumed {
                        for ev in &out[consumed..emit_count] {
                            yield Ok(Bytes::from(format_event(ev)));
                        }
                        consumed = emit_count;
                    }
                }
                Ok(Some(Err(e))) => {
                    tracing::warn!("upstream stream failed: status {}", e.status());
                    let frame = crate::sse::SseEvent {
                        event: Some("error".to_string()),
                        data: serde_json::json!({
                            "type": "error",
                            "error": {
                                "type": "api_error",
                                "message": format!("upstream error status {}", e.status())
                            }
                        })
                        .to_string(),
                    };
                    yield Ok(Bytes::from(format_event(&frame)));
                    break;
                }
                Ok(None) => {
                    // Upstream ended normally: emit any remaining frames,
                    // including finalization.
                    let out = crate::translate::stream::o2a_stream(events.clone(), &model);
                    if out.len() > consumed {
                        for ev in &out[consumed..] {
                            yield Ok(Bytes::from(format_event(ev)));
                        }
                    }
                    break;
                }
                Err(_) => {
                    tracing::warn!("stream idle timeout");
                    let frame = crate::sse::SseEvent {
                        event: Some("error".to_string()),
                        data: serde_json::json!({
                            "type": "error",
                            "error": {
                                "type": "api_error",
                                "message": "stream idle timeout"
                            }
                        })
                        .to_string(),
                    };
                    yield Ok(Bytes::from(format_event(&frame)));
                    break;
                }
            }
        }
    }
}

/// How many trailing frames `o2a_stream` appends as its finalization trailer
/// (`content_block_stop` when a block was open, then `message_delta` and
/// `message_stop`).
fn trailing_finalization_count(out: &[crate::sse::SseEvent]) -> usize {
    if out.is_empty() {
        return 0;
    }
    let last = serde_json::from_str::<serde_json::Value>(&out[out.len() - 1].data).ok();
    if last.as_ref().and_then(|v| v.get("type")).and_then(serde_json::Value::as_str)
        != Some("message_stop")
    {
        return 0;
    }
    if out.len() < 2 {
        return 1;
    }
    let second = serde_json::from_str::<serde_json::Value>(&out[out.len() - 2].data).ok();
    if second.as_ref().and_then(|v| v.get("type")).and_then(serde_json::Value::as_str)
        != Some("message_delta")
    {
        return 1;
    }
    if out.len() >= 3 {
        let third = serde_json::from_str::<serde_json::Value>(&out[out.len() - 3].data).ok();
        if third.as_ref().and_then(|v| v.get("type")).and_then(serde_json::Value::as_str)
            == Some("content_block_stop")
        {
            return 3;
        }
    }
    2
}

fn make_stream(
    mut src: futures_util::stream::BoxStream<'static, Result<crate::sse::SseEvent, providers::UpstreamError>>,
    idle_ms: u64,
) -> impl futures_util::Stream<Item = Result<Bytes, std::convert::Infallible>> {
    async_stream::stream! {
        let idle = Duration::from_millis(idle_ms);
        loop {
            match timeout(idle, src.next()).await {
                Ok(Some(Ok(ev))) => {
                    yield Ok(Bytes::from(format_event(&ev)));
                }
                Ok(Some(Err(e))) => {
                    let status = e.status();
                    let type_str = if status >= 500 { "server_error" } else { "invalid_request_error" };
                    let frame = json!({"error": {"message": format!("upstream error status {}", e.status()), "type": type_str}});
                    yield Ok(Bytes::from(format!("data: {}\n\n", frame)));
                    break;
                }
                Ok(None) => {
                    yield Ok(Bytes::from(DONE));
                    break;
                }
                Err(_) => {
                    let frame = json!({"error": {"message": "stream idle timeout", "type": "server_error"}});
                    yield Ok(Bytes::from(format!("data: {}\n\n", frame)));
                    break;
                }
            }
        }
    }
}


/// `POST /v1/providers/{id}/ping` -> probe upstream reachability.
async fn provider_ping_handler(
    peer: PeerAddr,
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> Response {
    let cfg = read_cfg(&state);
    if let Err(e) = authorize(&cfg, &headers, peer.0.map(|a| a.ip().is_loopback()).unwrap_or(false)) {
        return auth_error_body(e).into_response();
    }
    let provider = match cfg.provider(&id) {
        Some(p) => p.clone(),
        None => return (StatusCode::NOT_FOUND, Json(json!({"error": "provider not found"}))).into_response(),
    };

    let timeout_ms = cfg.request_timeout_ms.min(5000);
    let url = format!("{}/models", provider.base_url.trim_end_matches('/'));
    let start = std::time::Instant::now();
    let mut req = state.client.get(&url);
    match provider.kind {
        ProviderKind::OpenAiCompatible => {
            req = req.bearer_auth(provider.api_key.as_str());
        }
        ProviderKind::Anthropic => {
            req = req.header("x-api-key", provider.api_key.as_str());
        }
    }
    let result = timeout(Duration::from_millis(timeout_ms), req.send()).await;
    let latency = start.elapsed().as_millis() as u64;
    match result {
        Ok(Ok(resp)) => {
            let status = resp.status().as_u16();
            (StatusCode::OK, Json(json!({"ok": true, "latency_ms": latency, "status": status}))).into_response()
        }
        Ok(Err(e)) => {
            (StatusCode::BAD_GATEWAY, Json(json!({"ok": false, "error": e.to_string()}))).into_response()
        }
        Err(_) => {
            (StatusCode::BAD_GATEWAY, Json(json!({"ok": false, "error": "request timed out"}))).into_response()
        }
    }
}


fn read_cfg(state: &AppState) -> GatewayConfig {
    state
        .config
        .read()
        .unwrap_or_else(|p| p.into_inner())
        .clone()
}
