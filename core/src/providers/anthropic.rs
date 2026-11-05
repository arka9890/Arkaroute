//! The Anthropic adapter: `POST {base_url}/messages`.

use std::time::Duration;

use futures_util::StreamExt;
use serde_json::Value;

use crate::config::ProviderConfig;
use crate::protocol::ChatCompletionRequest;
use crate::providers::{ProviderAdapter, UpstreamError, UpstreamFailure, UpstreamOutcome};
use crate::sse::{SseEvent, SseParser};
use crate::translate::o2a_request;
use crate::translate::stream::a2o_stream;

/// Adapter speaking the Anthropic Messages API protocol:
/// `POST {base_url}/v1/messages` with `x-api-key` auth.
pub struct AnthropicAdapter {
    /// Provider id reported in every [`UpstreamError`].
    provider: String,
    base_url: String,
    api_key: String,
    client: reqwest::Client,
    /// Total request budget (`request_timeout_ms`), the base of the absolute
    /// deadline shared by every phase of one `chat` call.
    timeout: Duration,
}

impl AnthropicAdapter {
    /// Build from `cfg`, applying `request_timeout_ms` (from
    /// `GatewayConfig::request_timeout_ms`) as the total budget for the
    /// whole call — streamed responses included.
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

    /// POST the translated `/v1/messages` body, resolving once the upstream's
    /// response headers arrive. Bounded by the call's absolute `deadline` — not
    /// a fresh budget — so every phase of one request shares a single total
    /// bound.
    async fn send(
        &self,
        body: &Value,
        deadline: tokio::time::Instant,
    ) -> Result<reqwest::Response, UpstreamError> {
        let url = format!("{}/messages", self.base_url.trim_end_matches('/'));
        let request = self
            .client
            .post(&url)
            .header("x-api-key", self.api_key.as_str())
            .header("anthropic-version", "2023-06-01")
            .header(reqwest::header::CONTENT_TYPE, "application/json")
            .json(body);
        match tokio::time::timeout_at(deadline, request.send()).await {
            Ok(Ok(resp)) => Ok(resp),
            Ok(Err(e)) => Err(self.error(UpstreamFailure::Network(e.to_string()))),
            Err(_) => Err(self.error(UpstreamFailure::Timeout)),
        }
    }

    /// Read a response body to text, bounded by the call's absolute `deadline`.
    async fn read_text(
        &self,
        resp: reqwest::Response,
        deadline: tokio::time::Instant,
    ) -> Result<String, UpstreamError> {
        match tokio::time::timeout_at(deadline, resp.text()).await {
            Ok(Ok(text)) => Ok(text),
            Ok(Err(e)) => Err(self.error(UpstreamFailure::Network(e.to_string()))),
            Err(_) => Err(self.error(UpstreamFailure::Timeout)),
        }
    }

    /// Map a non-2xx response to an error, preserving its status and body.
    /// The error-body read shares the call's absolute `deadline`; if the
    /// budget runs out mid-read, the known status is still reported.
    async fn http_failure(
        &self,
        resp: reqwest::Response,
        deadline: tokio::time::Instant,
    ) -> UpstreamError {
        let status = resp.status().as_u16();
        let body = match tokio::time::timeout_at(deadline, resp.text()).await {
            Ok(Ok(text)) => text,
            Ok(Err(_)) | Err(_) => String::new(),
        };
        self.error(UpstreamFailure::Http { status, body })
    }

    /// Replay an Anthropic SSE response as canonical events. The upstream's
    /// `message_stop` ends the stream (no `[DONE]` counterpart); `a2o_stream`
    /// appends the canonical `[DONE]` sentinel, which is stripped here — the
    /// caller re-appends it. Every chunk read is bounded by the call's absolute
    /// `deadline`, so the stream as a whole cannot outlive
    /// `request_timeout_ms`: a stalled or trickling upstream is cut off with
    /// `Timeout` (504) once the total budget is exhausted.
    fn stream_events(
        &self,
        resp: reqwest::Response,
        deadline: tokio::time::Instant,
        model: &str,
    ) -> futures_util::stream::BoxStream<'static, Result<SseEvent, UpstreamError>> {
        let provider = self.provider.clone();
        let model = model.to_string();
        let stream = async_stream::stream! {
            let mut parser = SseParser::new();
            let mut events: Vec<SseEvent> = Vec::new();
            let mut bytes = Box::pin(resp.bytes_stream());
            'outer: loop {
                let chunk = match tokio::time::timeout_at(deadline, bytes.next()).await {
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
                            events.push(event);
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
                    events.push(event);
                }
            }
            for event in a2o_stream(events.clone(), &model) {
                if event.data == "[DONE]" {
                    // The caller re-appends the sentinel.
                    break;
                }
                yield Ok(event);
            }
        };
        stream.boxed()
    }
}

#[async_trait::async_trait]
impl ProviderAdapter for AnthropicAdapter {
    async fn chat(&self, req: &ChatCompletionRequest) -> Result<UpstreamOutcome, UpstreamError> {
        // Spec: `request_timeout_ms` is a *total* budget for the whole
        // request. One absolute deadline is shared by every phase — send,
        // body reads, the error-body read, and the entire streamed response.
        let deadline = tokio::time::Instant::now() + self.timeout;
        let body = o2a_request(req, &req.model);

        let resp = self.send(&body, deadline).await?;
        if !resp.status().is_success() {
            return Err(self.http_failure(resp, deadline).await);
        }

        let is_sse = resp
            .headers()
            .get("content-type")
            .and_then(|v| v.to_str().ok())
            .is_some_and(|v| v.contains("text/event-stream"));
        if is_sse {
            Ok(UpstreamOutcome::Stream(
                self.stream_events(resp, deadline, &req.model),
            ))
        } else {
            let text = self.read_text(resp, deadline).await?;
            let value = serde_json::from_str(&text).map_err(|e| {
                self.error(UpstreamFailure::Network(format!(
                    "upstream returned invalid JSON: {e}"
                )))
            })?;
            Ok(UpstreamOutcome::Json(crate::translate::a2o_response(
                value, &req.model,
            )))
        }
    }
}
