//! HTTP router construction and handlers.

use axum::extract::State;
use axum::routing::get;
use axum::{Json, Router};

use crate::state::AppState;

/// Build the gateway router. Mount the `/health` route in this task; later
/// tasks add further routes as one-line `.route(...)` entries here.
pub fn build_router(state: AppState) -> Router {
    Router::new()
        .route("/health", get(health_handler))
        .with_state(state)
}

/// `GET /health` → 200 JSON snapshot of live stats.
async fn health_handler(State(state): State<AppState>) -> Json<crate::state::StatsSnapshot> {
    let cfg = match state.config.read() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    Json(state.stats.snapshot(&cfg))
}
