//! Integration tests for the Anthropic provider adapter, exercised against a
//! scripted mock upstream on an ephemeral port.

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
    api_key: Option<String>,
    anthropic_version: Option<String>,
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
        api_key: parts
            .headers
            .get("x-api-key")
            .and_then(|v| v.to_str().ok())
            .map(str::to_string),
        anthropic_version: parts
            .headers
            .get("anthropic-version")
            .and_then(|v| v.to_str().ok())
            .map(str::to_string),
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

/// One Anthropic SSE frame: `event: <name>\ndata: <json>\n\n`.
fn frame(event: &str, data: Value) -> String {
    format!("event: {event}\ndata: {data}\n\n")
}

fn anthropic_provider(base_url: String) -> ProviderConfig {
    ProviderConfig {
        id: "mock".into(),
        kind: ProviderKind::Anthropic,
        base_url,
        api_key: "sk-ant-test".into(),
        models: vec!["claude-x".into()],
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

const ANTHROPIC_OK: &str = r#"{"id":"msg_1","type":"message","role":"assistant","model":"claude-x","content":[{"type":"text","text":"Hi there"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":7,"output_tokens":3}}"#;

#[tokio::test]
async fn request_body_is_o2a_translated_and_headers_set() {
    let upstream = spawn_upstream(|_| json_ok(ANTHROPIC_OK)).await;

    let adapter = adapter_for(
        &anthropic_provider(format!("http://{}/v1", upstream.addr)),
        reqwest::Client::new(),
        REQUEST_TIMEOUT_MS,
    );
    let req = ChatCompletionRequest {
        model: "free-stack".into(),
        messages: vec![
            ChatMessage {
                role: Role::System,
                content: Some(json!("be nice")),
                tool_calls: None,
                tool_call_id: None,
                name: None,
                extra: Default::default(),
            },
            ChatMessage {
                role: Role::User,
                content: Some(json!("hi")),
                tool_calls: None,
                tool_call_id: None,
                name: None,
                extra: Default::default(),
            },
        ],
        stream: false,
        max_tokens: Some(1024),
        temperature: None,
        top_p: None,
        tools: None,
        tool_choice: None,
        extra: Default::default(),
    };
    let outcome = adapter.chat(&req).await.expect("chat succeeds");
    match outcome {
        UpstreamOutcome::Json(_) => {}
        UpstreamOutcome::Stream(_) => panic!("expected a JSON outcome, got a stream"),
    }

    let requests = upstream.requests.lock().unwrap();
    assert_eq!(requests.len(), 1);
    assert_eq!(requests[0].path, "/v1/messages");
    assert_eq!(requests[0].api_key.as_deref(), Some("sk-ant-test"));
    assert_eq!(requests[0].anthropic_version.as_deref(), Some("2023-06-01"));
    assert_eq!(
        requests[0].authorization, None,
        "Anthropic speaks x-api-key, not an Authorization bearer"
    );
    assert_eq!(
        requests[0].content_type.as_deref(),
        Some("application/json")
    );
    assert_eq!(requests[0].body["model"], json!("free-stack"));
    assert_eq!(requests[0].body["max_tokens"], json!(1024));
    assert_eq!(requests[0].body["system"], json!("be nice"));
    assert_eq!(
        requests[0].body["messages"],
        json!([{"role": "user", "content": "hi"}]),
        "the system message leaves the messages array"
    );
}

#[tokio::test]
async fn json_response_becomes_canonical_response() {
    let upstream = spawn_upstream(|_| json_ok(ANTHROPIC_OK)).await;

    let adapter = adapter_for(
        &anthropic_provider(format!("http://{}/v1", upstream.addr)),
        reqwest::Client::new(),
        REQUEST_TIMEOUT_MS,
    );
    let outcome = adapter
        .chat(&chat_req("claude-x", false))
        .await
        .expect("chat succeeds");

    let value = match outcome {
        UpstreamOutcome::Json(v) => v,
        UpstreamOutcome::Stream(_) => panic!("expected a Json outcome, got a stream"),
    };
    assert_eq!(value["id"], json!("msg_1"));
    assert_eq!(value["choices"][0]["message"]["content"], json!("Hi there"));
    assert_eq!(
        value["choices"][0]["finish_reason"],
        json!("stop"),
        "Anthropic stop_reason end_turn → canonical finish_reason stop"
    );
    assert_eq!(value["usage"]["prompt_tokens"], json!(7));
    assert_eq!(value["usage"]["completion_tokens"], json!(3));
    assert_eq!(value["usage"]["total_tokens"], json!(10));

    let requests = upstream.requests.lock().unwrap();
    assert_eq!(requests.len(), 1);
}

#[tokio::test]
async fn sse_response_becomes_canonical_chunks() {
    let upstream = spawn_upstream(|_| {
        sse_response(vec![
            frame(
                "message_start",
                json!({
                    "type": "message_start",
                    "message": {
                        "id": "msg_1", "type": "message", "role": "assistant",
                        "model": "claude-x", "content": [],
                        "stop_reason": null,
                        "usage": {"input_tokens": 7, "output_tokens": 0},
                    },
                }),
            ),
            frame(
                "content_block_start",
                json!({
                    "type": "content_block_start",
                    "index": 0,
                    "content_block": {"type": "text", "text": ""},
                }),
            ),
            frame(
                "content_block_delta",
                json!({
                    "type": "content_block_delta",
                    "index": 0,
                    "delta": {"type": "text_delta", "text": "Hello"},
                }),
            ),
            frame(
                "content_block_delta",
                json!({
                    "type": "content_block_delta",
                    "index": 0,
                    "delta": {"type": "text_delta", "text": " world"},
                }),
            ),
            frame(
                "content_block_stop",
                json!({"type": "content_block_stop", "index": 0}),
            ),
            frame(
                "message_delta",
                json!({
                    "type": "message_delta",
                    "delta": {"stop_reason": "end_turn", "stop_sequence": null},
                    "usage": {"output_tokens": 5},
                }),
            ),
            frame("message_stop", json!({"type": "message_stop"})),
        ])
    })
    .await;

    let adapter = adapter_for(
        &anthropic_provider(format!("http://{}/v1", upstream.addr)),
        reqwest::Client::new(),
        REQUEST_TIMEOUT_MS,
    );
    let outcome = adapter
        .chat(&chat_req("claude-x", true))
        .await
        .expect("chat succeeds");
    let stream = match outcome {
        UpstreamOutcome::Stream(s) => s,
        UpstreamOutcome::Json(_) => panic!("expected Stream outcome"),
    };
    let events = collect(stream).await;

    // text chunk, text chunk, finish chunk, usage chunk — then `[DONE]`,
    // which the adapter strips and the caller re-appends.
    assert_eq!(events.len(), 4, "chunks + finish + usage; [DONE] stripped");
    assert!(
        events.iter().all(|e| e.data != "[DONE]"),
        "terminal sentinel must not be forwarded"
    );
    assert!(
        events[0].data.contains(r#""content":"Hello""#),
        "first text chunk replayed: {}",
        events[0].data
    );
    assert!(
        events[1].data.contains(r#""content":" world""#),
        "second text chunk replayed: {}",
        events[1].data
    );
    assert!(
        events[2].data.contains(r#""finish_reason":"stop""#),
        "final finish chunk before [DONE]: {}",
        events[2].data
    );
    assert!(
        events[3].data.contains(r#""usage""#),
        "usage chunk precedes [DONE]: {}",
        events[3].data
    );
}

#[tokio::test]
async fn upstream_429_is_fallbackable() {
    let upstream = spawn_upstream(|_| {
        (
            StatusCode::TOO_MANY_REQUESTS,
            [(header::CONTENT_TYPE, "application/json")],
            r#"{"type":"error","error":{"message":"rate limited","type":"rate_limit_error"}}"#,
        )
            .into_response()
    })
    .await;

    let adapter = adapter_for(
        &anthropic_provider(format!("http://{}/v1", upstream.addr)),
        reqwest::Client::new(),
        REQUEST_TIMEOUT_MS,
    );
    let err = match adapter.chat(&chat_req("claude-x", false)).await {
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
