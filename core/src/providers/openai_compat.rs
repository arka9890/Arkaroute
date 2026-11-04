//! The OpenAI-compatible adapter: `POST {base_url}/chat/completions`.

use std::time::Duration;

use futures_util::StreamExt;
use serde_json::{json, Value};

use crate::config::ProviderConfig;
use crate::protocol::ChatCompletionRequest;
use crate::providers::{ProviderAdapter, UpstreamError, UpstreamFailure, UpstreamOutcome};
use crate::sse::{SseEvent, SseParser};

/// Adapter speaking the OpenAI-compatible chat-completions protocol.
pub struct OpenAiCompatAdapter {
    /// Provider id reported in every [`UpstreamError`].
    provider: String,
    base_url: String,
    api_key: String,
    client: reqwest::Client,
    timeout: Duration,
}

impl OpenAiCompatAdapter {
    /// Build from `cfg`, applying `request_timeout_ms` (from
    /// `GatewayConfig::request_timeout_ms`) to every wait on the upstream.
    pub fn new(cfg: &ProviderConfig, client: reqwest::Client, request_timeout_ms: u64) -> Self {
        Self {
            provider: cfg.id.clone(),
            base_url: cfg.base_url.clone(),
            api_key: cfg.api_key.clone(),
            client,
            timeout: Duration::from_millis(request_timeout_ms),
        }
    }

    fn error(&self, failure: UpstreamFailure) -> UpstreamError {
        UpstreamError {
            provider: self.provider.clone(),
            failure,
        }
    }

    /// POST the body, bounded by the request timeout. `send` resolves once the
    /// upstream's response headers arrive.
    async fn send(&self, body: &Value) -> Result<reqwest::Response, UpstreamError> {
        let url = format!("{}/chat/completions", self.base_url.trim_end_matches('/'));
        let request = self
            .client
            .post(&url)
            .bearer_auth(self.api_key.as_str())
            .header(reqwest::header::CONTENT_TYPE, "application/json")
            .json(body);
        match tokio::time::timeout(self.timeout, request.send()).await {
            Ok(Ok(resp)) => Ok(resp),
            Ok(Err(e)) => Err(self.error(UpstreamFailure::Network(e.to_string()))),
            Err(_) => Err(self.error(UpstreamFailure::Timeout)),
        }
    }

    /// Read a response body to text, bounded by the request timeout.
    async fn read_text(&self, resp: reqwest::Response) -> Result<String, UpstreamError> {
        match tokio::time::timeout(self.timeout, resp.text()).await {
            Ok(Ok(text)) => Ok(text),
            Ok(Err(e)) => Err(self.error(UpstreamFailure::Network(e.to_string()))),
            Err(_) => Err(self.error(UpstreamFailure::Timeout)),
        }
    }

    /// Map a non-2xx response to an error, preserving its status and body.
    async fn http_failure(&self, resp: reqwest::Response) -> UpstreamError {
        let status = resp.status().as_u16();
        // The status is already known; a failed or timed-out read of the
        // error body must not mask it.
        let body = match tokio::time::timeout(self.timeout, resp.text()).await {
            Ok(Ok(text)) => text,
            Ok(Err(_)) | Err(_) => String::new(),
        };
        self.error(UpstreamFailure::Http { status, body })
    }

    /// Replay an SSE response as canonical events. The upstream's `data:
    /// [DONE]` sentinel ends the stream without being forwarded — the caller
    /// re-appends it. Each network read is bounded by the request timeout so
    /// a stalled upstream cannot hang the caller forever.
    fn stream_events(
        &self,
        resp: reqwest::Response,
    ) -> futures_util::stream::BoxStream<'static, Result<SseEvent, UpstreamError>> {
        let provider = self.provider.clone();
        let read_timeout = self.timeout;
        let stream = async_stream::stream! {
            let mut parser = SseParser::new();
            let mut bytes = Box::pin(resp.bytes_stream());
            'outer: loop {
                let chunk = match tokio::time::timeout(read_timeout, bytes.next()).await {
                    Ok(Some(Ok(bytes))) => bytes,
                    Ok(Some(Err(e))) => {
                        yield Err(UpstreamError {
                            provider: provider.clone(),
                            failure: UpstreamFailure::Network(e.to_string()),
                        });
                        break 'outer;
                    }
                    Ok(None) => {
                        // End of body: flush a final event that arrived
                        // without a trailing blank line.
                        for event in parser.finish() {
                            if event.data == "[DONE]" {
                                break 'outer;
                            }
                            yield Ok(event);
                        }
                        break 'outer;
                    }
                    Err(_) => {
                        yield Err(UpstreamError {
                            provider: provider.clone(),
                            failure: UpstreamFailure::Timeout,
                        });
                        break 'outer;
                    }
                };
                for event in parser.push(&chunk) {
                    if event.data == "[DONE]" {
                        // Terminal sentinel: end the stream without
                        // forwarding it.
                        break 'outer;
                    }
                    yield Ok(event);
                }
            }
        };
        stream.boxed()
    }
}

#[async_trait::async_trait]
impl ProviderAdapter for OpenAiCompatAdapter {
    async fn chat(&self, req: &ChatCompletionRequest) -> Result<UpstreamOutcome, UpstreamError> {
        let mut body = serde_json::to_value(req).map_err(|e| {
            self.error(UpstreamFailure::Network(format!(
                "failed to serialize request: {e}"
            )))
        })?;
        // The canonical type omits `stream: false`; set it explicitly so the
        // upstream never has to guess.
        body["stream"] = Value::Bool(req.stream);

        let plain = body.clone();
        let injected = req.stream && body.get("stream_options").is_none();
        if injected {
            body["stream_options"] = json!({ "include_usage": true });
        }

        let mut resp = self.send(&body).await?;
        if injected && resp.status().as_u16() == 400 {
            // Retry gate: no event has reached the caller yet — `chat()` has
            // not returned a stream — so a retry here can never violate the
            // "no fallback after first chunk" rule. Only an HTTP 400 of the
            // injected attempt is retried: it may be the upstream rejecting
            // the `stream_options` we added. Any other failure (429, 401,
            // 5xx, transport) is reported as-is.
            resp = self.send(&plain).await?;
        }
        if !resp.status().is_success() {
            return Err(self.http_failure(resp).await);
        }

        if req.stream {
            Ok(UpstreamOutcome::Stream(self.stream_events(resp)))
        } else {
            let text = self.read_text(resp).await?;
            let value = serde_json::from_str(&text).map_err(|e| {
                self.error(UpstreamFailure::Network(format!(
                    "upstream returned invalid JSON: {e}"
                )))
            })?;
            Ok(UpstreamOutcome::Json(value))
        }
    }
}
