//! HTTP router construction and handlers.

use std::net::SocketAddr;

use axum::body::Bytes;
use axum::extract::{ConnectInfo, FromRequestParts, State};
use axum::extract::connect_info::MockConnectInfo;
use axum::http::request::Parts;
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use futures_util::StreamExt;
use serde_json::json;
use tokio::time::{timeout, Duration};

use crate::auth::{authorize, AuthError};
use crate::config::GatewayConfig;
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
        .route("/v1/models", get(models_handler))
        .with_state(state)
}

struct PeerAddr(SocketAddr);

impl<S> FromRequestParts<S> for PeerAddr
where
    S: Send + Sync,
{
    type Rejection = std::convert::Infallible;

    async fn from_request_parts(parts: &mut Parts, _state: &S) -> Result<Self, Self::Rejection> {
        if let Some(ci) = parts.extensions.get::<ConnectInfo<SocketAddr>>() {
            return Ok(PeerAddr(ci.0));
        }
        if let Some(mc) = parts.extensions.get::<MockConnectInfo<SocketAddr>>() {
            return Ok(PeerAddr(mc.0));
        }
        Ok(PeerAddr(SocketAddr::from(([127, 0, 0, 1], 0))))
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

/// `GET /health` -> 200 JSON snapshot of live stats.
async fn health_handler(
    peer: PeerAddr,
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Response {
    let cfg = read_cfg(&state);
    if let Err(e) = authorize(&cfg, &headers, peer.0.ip().is_loopback()) {
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
    if let Err(e) = authorize(&cfg, &headers, peer.0.ip().is_loopback()) {
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

async fn chat_completions_handler(
    peer: PeerAddr,
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let cfg = read_cfg(&state);
    if let Err(e) = authorize(&cfg, &headers, peer.0.ip().is_loopback()) {
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

fn read_cfg(state: &AppState) -> GatewayConfig {
    state
        .config
        .read()
        .unwrap_or_else(|p| p.into_inner())
        .clone()
}
