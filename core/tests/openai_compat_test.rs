//! Integration tests for the OpenAI-compatible provider adapter, exercised
//! against a scripted mock upstream on an ephemeral port.

use std::net::SocketAddr;
use std::sync::{Arc, Mutex};

use axum::body::{to_bytes, Body};
use axum::extract::{Request, State};
use axum::http::{header, StatusCode};
use axum::response::{IntoResponse, Response};
use bytes::Bytes;
use futures_util::StreamExt;
use omniroute_core::config::{ProviderConfig, ProviderKind};
use omniroute_core::protocol::{ChatCompletionRequest, ChatMessage, Role};
use omniroute_core::providers::{adapter_for, UpstreamError, UpstreamFailure, UpstreamOutcome};
use omniroute_core::sse::SseEvent;
use serde_json::{json, Value};

/// Request timeout the tests hand to `adapter_for`.
const REQUEST_TIMEOUT_MS: u64 = 5_000;

/// One request the mock upstream received, for assertions.
#[derive(Debug, Clone)]
struct Recorded {
    path: String,
    authorization: Option<String>,
    content_type: Option<String>,
    body: Value,
}

type Script = Arc<dyn Fn(usize) -> Response + Send + Sync>;

/// A scripted mock upstream bound to `127.0.0.1:0`.
struct MockUpstream {
    addr: SocketAddr,
    requests: Arc<Mutex<Vec<Recorded>>>,
    handle: tokio::task::JoinHandle<()>,
}

impl MockUpstream {
    fn base_url(&self) -> String {
        format!("http://{}", self.addr)
    }
}

impl Drop for MockUpstream {
    fn drop(&mut self) {
        self.handle.abort();
    }
}

async fn handle(
    State(state): State<(Arc<Mutex<Vec<Recorded>>>, Script)>,
    req: Request,
) -> Response {
    let (requests, script) = state;
    let (parts, body) = req.into_parts();
    let bytes = to_bytes(body, 16 * 1024 * 1024).await.unwrap_or_default();
    let recorded = Recorded {
        path: parts.uri.path().to_string(),
        authorization: parts
            .headers
            .get(header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .map(str::to_string),
        content_type: parts
            .headers
            .get(header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .map(str::to_string),
        body: serde_json::from_slice(&bytes).unwrap_or(Value::Null),
    };
    let index = {
        let mut requests = requests.lock().expect("mock request lock");
        let index = requests.len();
        requests.push(recorded);
        index
    };
    script(index)
}

/// Spawn the mock upstream; `script` maps a zero-based request index to the
/// response to return.
async fn spawn_upstream(
    script: impl Fn(usize) -> Response + Send + Sync + 'static,
) -> MockUpstream {
    let requests = Arc::new(Mutex::new(Vec::new()));
    let state = (requests.clone(), Arc::new(script) as Script);
    let router = axum::Router::new().fallback(handle).with_state(state);
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind mock upstream");
    let addr = listener.local_addr().expect("mock upstream addr");
    let handle = tokio::spawn(async move {
        axum::serve(listener, router)
            .await
            .expect("serve mock upstream");
    });
    MockUpstream {
        addr,
        requests,
        handle,
    }
}

fn json_ok(body: &str) -> Response {
    (
        StatusCode::OK,
        [(header::CONTENT_TYPE, "application/json")],
        body.to_string(),
    )
        .into_response()
}

/// A `text/event-stream` response; each part arrives as its own frame with a
/// small gap, so the adapter sees multiple network reads.
fn sse_response(parts: Vec<String>) -> Response {
    let stream = async_stream::stream! {
        let mut first = true;
        for part in parts {
            if !first {
                tokio::time::sleep(std::time::Duration::from_millis(25)).await;
            }
            first = false;
            yield Ok::<_, std::convert::Infallible>(Bytes::from(part));
        }
    };
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "text/event-stream")
        .body(Body::from_stream(stream))
        .expect("response builds")
}

fn chunk(id: u64, text: &str) -> String {
    format!(
        r#"data: {{"id":"c{id}","object":"chat.completion.chunk","created":1,"model":"m","choices":[{{"index":0,"delta":{{"content":"{text}"}}}}]}}"#
    )
}

/// A 200 JSON response whose body arrives only after `delay`.
fn delayed_json(body: &'static str, delay: std::time::Duration) -> Response {
    let stream = async_stream::stream! {
        tokio::time::sleep(delay).await;
        yield Ok::<_, std::convert::Infallible>(Bytes::from(body));
    };
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from_stream(stream))
        .expect("response builds")
}

/// One SSE frame immediately; the body then stays open without more data.
fn sse_response_stalling(frame: String) -> Response {
    let stream = async_stream::stream! {
        yield Ok::<_, std::convert::Infallible>(Bytes::from(frame));
        tokio::time::sleep(std::time::Duration::from_secs(60)).await;
    };
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "text/event-stream")
        .body(Body::from_stream(stream))
        .expect("response builds")
}

/// One SSE frame immediately, then one every `gap` for `count` frames; the
/// body stays open afterwards.
fn sse_response_trickling(frame: String, gap: std::time::Duration, count: usize) -> Response {
    let stream = async_stream::stream! {
        for i in 0..count {
            if i > 0 {
                tokio::time::sleep(gap).await;
            }
            yield Ok::<_, std::convert::Infallible>(Bytes::from(frame.clone()));
        }
        tokio::time::sleep(std::time::Duration::from_secs(60)).await;
    };
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "text/event-stream")
        .body(Body::from_stream(stream))
        .expect("response builds")
}

fn provider(base_url: String) -> ProviderConfig {
    ProviderConfig {
        id: "mock".into(),
        kind: ProviderKind::OpenAiCompatible,
        base_url,
        api_key: "sk-test".into(),
        models: vec!["test-model".into()],
    }
}

fn chat_req(model: &str, stream: bool) -> ChatCompletionRequest {
    ChatCompletionRequest {
        model: model.into(),
        messages: vec![ChatMessage {
            role: Role::User,
            content: Some(json!("hello")),
            tool_calls: None,
            tool_call_id: None,
            name: None,
            extra: Default::default(),
        }],
        stream,
        max_tokens: None,
        temperature: None,
        top_p: None,
        tools: None,
        tool_choice: None,
        extra: Default::default(),
    }
}

async fn collect(
    mut stream: impl futures_util::Stream<Item = Result<SseEvent, UpstreamError>> + Unpin,
) -> Vec<SseEvent> {
    let mut events = Vec::new();
    while let Some(item) = stream.next().await {
        events.push(item.expect("stream yields events without error"));
    }
    events
}

#[tokio::test]
async fn json_response_is_returned_verbatim() {
    let upstream = spawn_upstream(|_| {
        json_ok(
            r#"{"id":"x","object":"chat.completion","created":1,"model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":3}}"#,
        )
    })
    .await;

    let adapter = adapter_for(
        &provider(upstream.base_url()),
        reqwest::Client::new(),
        REQUEST_TIMEOUT_MS,
    );
    let outcome = adapter
        .chat(&chat_req("test-model", false))
        .await
        .expect("chat succeeds");

    let value = match outcome {
        UpstreamOutcome::Json(v) => v,
        UpstreamOutcome::Stream(_) => panic!("expected Json outcome"),
    };
    assert_eq!(value["usage"]["prompt_tokens"], json!(7), "usage intact");
    assert_eq!(
        value["usage"]["completion_tokens"],
        json!(3),
        "usage intact"
    );
    assert_eq!(value["id"], json!("x"));

    let requests = upstream.requests.lock().unwrap();
    assert_eq!(requests.len(), 1);
    assert_eq!(
        requests[0].body["model"],
        json!("test-model"),
        "upstream received the requested model"
    );
    assert_eq!(requests[0].path, "/chat/completions");
    assert_eq!(requests[0].authorization.as_deref(), Some("Bearer sk-test"));
    assert_eq!(
        requests[0].content_type.as_deref(),
        Some("application/json")
    );
}

#[tokio::test]
async fn stream_is_replayed_as_canonical_events() {
    let upstream = spawn_upstream(|_| {
        sse_response(vec![
            format!("{}\n\n", chunk(1, "a")),
            format!("{}\n\ndata: [DONE]\n\n", chunk(2, "b")),
        ])
    })
    .await;

    let adapter = adapter_for(
        &provider(upstream.base_url()),
        reqwest::Client::new(),
        REQUEST_TIMEOUT_MS,
    );
    let outcome = adapter
        .chat(&chat_req("m", true))
        .await
        .expect("chat succeeds");

    let stream = match outcome {
        UpstreamOutcome::Stream(s) => s,
        UpstreamOutcome::Json(_) => panic!("expected Stream outcome"),
    };
    let events = collect(stream).await;

    assert_eq!(events.len(), 2, "[DONE] is stripped; caller re-adds it");
    assert!(
        events[0].data.contains(r#""content":"a""#),
        "chunk 1 replayed"
    );
    assert!(
        events[1].data.contains(r#""content":"b""#),
        "chunk 2 replayed"
    );
    assert!(
        events.iter().all(|e| e.data != "[DONE]"),
        "terminal sentinel must not be forwarded"
    );

    let requests = upstream.requests.lock().unwrap();
    assert_eq!(requests.len(), 1);
    assert_eq!(
        requests[0].body["stream"],
        json!(true),
        "stream flag set explicitly on the outgoing body"
    );
}

#[tokio::test]
async fn stream_injects_include_usage_and_retries_once_on_400() {
    let upstream = spawn_upstream(|index| {
        if index == 0 {
            (
                StatusCode::BAD_REQUEST,
                [(header::CONTENT_TYPE, "application/json")],
                r#"{"error":{"message":"stream_options is not supported","type":"invalid_request_error"}}"#,
            )
                .into_response()
        } else {
            sse_response(vec![format!("{}\n\ndata: [DONE]\n\n", chunk(1, "ok"))])
        }
    })
    .await;

    let adapter = adapter_for(
        &provider(upstream.base_url()),
        reqwest::Client::new(),
        REQUEST_TIMEOUT_MS,
    );
    let outcome = adapter
        .chat(&chat_req("m", true))
        .await
        .expect("retry succeeds");

    let stream = match outcome {
        UpstreamOutcome::Stream(s) => s,
        UpstreamOutcome::Json(_) => panic!("expected Stream outcome"),
    };
    let events = collect(stream).await;
    assert_eq!(events.len(), 1, "one chunk, [DONE] stripped");

    let requests = upstream.requests.lock().unwrap();
    assert_eq!(requests.len(), 2, "retried exactly once");
    assert_eq!(
        requests[0].body["stream_options"],
        json!({"include_usage": true}),
        "usage injection present on first attempt"
    );
    assert!(
        requests[1].body.get("stream_options").is_none(),
        "retry drops the injected field"
    );
    assert_eq!(requests[1].body["stream"], json!(true));
}

#[tokio::test]
async fn upstream_429_maps_to_fallbackable_error() {
    let upstream = spawn_upstream(|_| {
        (
            StatusCode::TOO_MANY_REQUESTS,
            [(header::CONTENT_TYPE, "application/json")],
            r#"{"error":{"message":"rate limited","type":"rate_limit_error"}}"#,
        )
            .into_response()
    })
    .await;

    let adapter = adapter_for(
        &provider(upstream.base_url()),
        reqwest::Client::new(),
        REQUEST_TIMEOUT_MS,
    );
    let err = match adapter.chat(&chat_req("m", false)).await {
        Err(e) => e,
        Ok(_) => panic!("expected an upstream error"),
    };

    assert_eq!(err.provider, "mock");
    assert_eq!(err.status(), 429);
    assert!(err.is_fallbackable(), "429 must allow combo fallback");
    match &err.failure {
        UpstreamFailure::Http { status, body } => {
            assert_eq!(*status, 429);
            assert!(body.contains("rate limited"), "upstream body preserved");
        }
        other => panic!("expected Http failure, got {other:?}"),
    }
    let requests = upstream.requests.lock().unwrap();
    assert_eq!(requests.len(), 1, "no retry without usage injection");
}

#[tokio::test]
async fn connection_refused_maps_to_network_failure() {
    // Bind then drop: the port stays unbound, so connecting is refused.
    let addr = {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        drop(listener);
        addr
    };

    let adapter = adapter_for(
        &provider(format!("http://{addr}")),
        reqwest::Client::new(),
        REQUEST_TIMEOUT_MS,
    );
    let err = match adapter.chat(&chat_req("m", false)).await {
        Err(e) => e,
        Ok(_) => panic!("expected a network failure"),
    };

    assert_eq!(err.provider, "mock");
    assert_eq!(err.status(), 502);
    assert!(
        err.is_fallbackable(),
        "unreachable upstream allows fallback"
    );
    match &err.failure {
        UpstreamFailure::Network(msg) => assert!(!msg.is_empty(), "diagnostic: {msg}"),
        other => panic!("expected Network failure, got {other:?}"),
    }
}

#[tokio::test]
async fn injected_attempt_429_is_not_retried() {
    let upstream = spawn_upstream(|_| {
        (
            StatusCode::TOO_MANY_REQUESTS,
            [(header::CONTENT_TYPE, "application/json")],
            r#"{"error":{"message":"rate limited","type":"rate_limit_error"}}"#,
        )
            .into_response()
    })
    .await;

    let adapter = adapter_for(
        &provider(upstream.base_url()),
        reqwest::Client::new(),
        REQUEST_TIMEOUT_MS,
    );
    let err = match adapter.chat(&chat_req("m", true)).await {
        Err(e) => e,
        Ok(_) => panic!("expected an upstream error"),
    };

    assert_eq!(err.status(), 429);
    assert!(err.is_fallbackable());
    let requests = upstream.requests.lock().unwrap();
    assert_eq!(
        requests.len(),
        1,
        "only an HTTP 400 of the injected attempt may be retried"
    );
    assert_eq!(
        requests[0].body["stream_options"],
        json!({"include_usage": true}),
        "the failing attempt was the injected one"
    );
}

#[tokio::test]
async fn configured_request_timeout_is_honored() {
    // Body arrives after 1s; the adapter is configured with a 100ms budget,
    // so the call must fail with Timeout instead of waiting for the body.
    let upstream = spawn_upstream(|_| {
        delayed_json(
            r#"{"id":"late","object":"chat.completion","created":1,"model":"m","choices":[]}"#,
            std::time::Duration::from_secs(1),
        )
    })
    .await;

    let adapter = adapter_for(&provider(upstream.base_url()), reqwest::Client::new(), 100);
    let err = match adapter.chat(&chat_req("m", false)).await {
        Err(e) => e,
        Ok(_) => panic!("expected the configured timeout to fire"),
    };

    assert_eq!(err.status(), 504);
    match &err.failure {
        UpstreamFailure::Timeout => {}
        other => panic!("expected Timeout, got {other:?}"),
    }
}

#[tokio::test]
async fn stalled_stream_yields_timeout_error() {
    // One chunk now, then the body stays open past the 300ms total budget:
    // the stream must end with Timeout (504), not stream forever.
    let upstream =
        spawn_upstream(|_| sse_response_stalling(format!("{}\n\n", chunk(1, "a")))).await;

    let adapter = adapter_for(&provider(upstream.base_url()), reqwest::Client::new(), 300);
    let started = tokio::time::Instant::now();
    let outcome = adapter
        .chat(&chat_req("m", true))
        .await
        .expect("headers arrive within the budget");

    let mut stream = match outcome {
        UpstreamOutcome::Stream(s) => s,
        UpstreamOutcome::Json(_) => panic!("expected Stream outcome"),
    };

    let first = tokio::time::timeout(std::time::Duration::from_secs(2), stream.next())
        .await
        .expect("first chunk arrives promptly")
        .expect("stream does not end before the first chunk")
        .expect("first chunk yields without error");
    assert!(first.data.contains(r#""content":"a""#));

    let next = tokio::time::timeout(std::time::Duration::from_secs(2), stream.next()).await;
    let err = match next {
        Ok(Some(Err(e))) => e,
        Ok(Some(Ok(ev))) => panic!("stalled stream must not yield further events: {ev:?}"),
        Ok(None) => panic!("stalled stream must end with Timeout, not EOF"),
        Err(_) => panic!("stalled stream must be cut at the deadline, not hang"),
    };
    assert_eq!(err.status(), 504);
    match &err.failure {
        UpstreamFailure::Timeout => {}
        other => panic!("expected Timeout, got {other:?}"),
    }
    assert!(
        started.elapsed() < std::time::Duration::from_secs(2),
        "the cut must come from the total budget, not from the upstream closing"
    );
}

#[tokio::test]
async fn trickling_stream_is_cut_at_total_deadline() {
    // A chunk every 100ms: every read sits inside a per-read budget, so only
    // a total deadline can stop the stream before `request_timeout_ms`.
    let upstream = spawn_upstream(|_| {
        sse_response_trickling(
            format!("{}\n\n", chunk(1, "a")),
            std::time::Duration::from_millis(100),
            50,
        )
    })
    .await;

    let adapter = adapter_for(&provider(upstream.base_url()), reqwest::Client::new(), 300);
    let started = tokio::time::Instant::now();
    let outcome = adapter
        .chat(&chat_req("m", true))
        .await
        .expect("headers arrive within the budget");

    let mut stream = match outcome {
        UpstreamOutcome::Stream(s) => s,
        UpstreamOutcome::Json(_) => panic!("expected Stream outcome"),
    };

    let mut events = Vec::new();
    let mut error = None;
    loop {
        match tokio::time::timeout(std::time::Duration::from_secs(2), stream.next()).await {
            Ok(Some(Ok(ev))) => events.push(ev),
            Ok(Some(Err(e))) => {
                error = Some(e);
                break;
            }
            Ok(None) => break,
            Err(_) => break, // safety: never hang the test
        }
        if started.elapsed() >= std::time::Duration::from_millis(1200) {
            break; // guard: an implementation without a total bound never errs
        }
    }

    let err = error.expect("trickling stream must be cut at the total deadline");
    assert_eq!(err.status(), 504);
    match &err.failure {
        UpstreamFailure::Timeout => {}
        other => panic!("expected Timeout, got {other:?}"),
    }
    assert!(
        events.len() <= 6,
        "cut near the 300ms budget, got {} events",
        events.len()
    );
}
