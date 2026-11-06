//! Embedded dashboard assets served via `rust-embed`.

use axum::http::{header, StatusCode};
use axum::response::{IntoResponse, Response};
use rust_embed::Embed;

#[derive(Embed)]
#[folder = "../dashboard/"]
pub struct DashboardAssets;

/// Serve a single embedded asset by its path relative to `dashboard/`.
///
/// Empty path resolves to `index.html`. Sets `Content-Type` from the file
/// extension and `Cache-Control: no-cache` for HTML documents.
pub fn serve_asset(path: &str) -> Response {
    let asset_path = path.trim_start_matches('/').trim_start_matches("dashboard/");
    let asset_path = if asset_path.is_empty() {
        "index.html"
    } else {
        asset_path
    };

    match DashboardAssets::get(asset_path) {
        Some(content) => {
            let mut builder = Response::builder().status(StatusCode::OK);
            let mime = content.metadata.mimetype();
            builder = builder.header(header::CONTENT_TYPE, mime);
            if asset_path.ends_with(".html") {
                builder = builder.header(header::CACHE_CONTROL, "no-cache");
            }
            builder
                .body(axum::body::Body::from(content.data))
                .unwrap_or_else(|_| StatusCode::INTERNAL_SERVER_ERROR.into_response())
        }
        None => (StatusCode::NOT_FOUND, "404 Not Found").into_response(),
    }
}
