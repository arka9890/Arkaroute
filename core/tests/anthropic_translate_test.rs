//! Anthropic outbound translation: canonical requests → `/v1/messages` bodies,
//! Anthropic responses/streams → canonical shapes, and canonical chunks →
//! Anthropic events. All tests are synchronous: the stream translators are
//! pure `Vec<SseEvent> → Vec<SseEvent>` functions.

use omniroute_core::protocol::ChatCompletionRequest;
use omniroute_core::sse::SseEvent;
use omniroute_core::translate::stream::{a2o_stream, o2a_stream};
use omniroute_core::translate::o2a_request;
use serde_json::{json, Value};

/// One Anthropic SSE frame: named event + JSON `data` payload.
fn anthropic_event(name: &str, data: Value) -> SseEvent {
    SseEvent {
        event: Some(name.to_string()),
        data: data.to_string(),
    }
}

/// One canonical SSE frame: a data-only chunk (or the `[DONE]` sentinel).
fn chunk_event(data: Value) -> SseEvent {
    SseEvent {
        event: None,
        data: data.to_string(),
    }
}

/// Decode an event's JSON payload.
fn data_of(ev: &SseEvent) -> Value {
    serde_json::from_str(&ev.data).expect("event data is JSON")
}

fn request(body: Value) -> ChatCompletionRequest {
    ChatCompletionRequest::from_value(body).expect("canonical request parses")
}

#[test]
fn system_messages_become_system_param() {
    let req = request(json!({
        "model": "free-stack",
        "messages": [
            {"role": "system", "content": "be nice"},
            {"role": "user", "content": "hi"}
        ]
    }));

    let out = o2a_request(&req, "claude-x");

    assert_eq!(out["model"], json!("claude-x"), "upstream model comes from the routing plan");
    assert_eq!(out["system"], json!("be nice"), "system text becomes the top-level system param");
    assert_eq!(
        out["messages"],
        json!([{"role": "user", "content": "hi"}]),
        "system messages leave the messages array"
    );
}

#[test]
fn missing_max_tokens_defaults_to_4096() {
    let without = request(json!({
        "model": "free-stack",
        "messages": [{"role": "user", "content": "hi"}]
    }));
    assert_eq!(
        o2a_request(&without, "claude-x")["max_tokens"],
        json!(4096),
        "Anthropic requires max_tokens; the OpenAI shape defaults it to 4096"
    );

    let explicit = request(json!({
        "model": "free-stack",
        "messages": [{"role": "user", "content": "hi"}],
        "max_tokens": 100
    }));
    assert_eq!(
        o2a_request(&explicit, "claude-x")["max_tokens"],
        json!(100),
        "an explicit max_tokens passes through untouched"
    );
}

#[test]
fn tools_map_to_anthropic_tools_schema() {
    let req = request(json!({
        "model": "free-stack",
        "messages": [{"role": "user", "content": "weather?"}],
        "tools": [{
            "type": "function",
            "function": {
                "name": "get_weather",
                "description": "Get the weather",
                "parameters": {"type": "object", "properties": {"city": {"type": "string"}}}
            }
        }]
    }));

    let out = o2a_request(&req, "claude-x");

    assert_eq!(
        out["tools"],
        json!([{
            "name": "get_weather",
            "description": "Get the weather",
            "input_schema": {"type": "object", "properties": {"city": {"type": "string"}}}
        }]),
        "function.parameters becomes input_schema under the tool's own name"
    );
}

#[test]
fn tool_result_message_maps_to_user_tool_result() {
    let req = request(json!({
        "model": "free-stack",
        "messages": [
            {"role": "assistant", "tool_calls": [{
                "id": "call_1", "type": "function",
                "function": {"name": "get_weather", "arguments": "{\"city\":\"Paris\"}"}
            }]},
            {"role": "tool", "tool_call_id": "call_1", "content": "sunny"}
        ]
    }));

    let out = o2a_request(&req, "claude-x");

    assert_eq!(
        out["messages"][1],
        json!({
            "role": "user",
            "content": [{"type": "tool_result", "tool_use_id": "call_1", "content": "sunny"}]
        }),
        "role tool becomes a user message carrying a tool_result block"
    );
}

#[test]
fn assistant_tool_calls_map_to_tool_use_blocks() {
    let req = request(json!({
        "model": "free-stack",
        "messages": [{
            "role": "assistant",
            "content": "checking",
            "tool_calls": [{
                "id": "call_1",
                "type": "function",
                "function": {"name": "get_weather", "arguments": "{\"city\":\"Paris\"}"}
            }]
        }]
    }));

    let out = o2a_request(&req, "claude-x");

    assert_eq!(
        out["messages"],
        json!([{
            "role": "assistant",
            "content": [
                {"type": "text", "text": "checking"},
                {"type": "tool_use", "id": "call_1", "name": "get_weather", "input": {"city": "Paris"}}
            ]
        }]),
        "tool calls become tool_use blocks with input parsed from the JSON string"
    );
}

#[test]
fn a2o_stream_emits_canonical_text_chunks() {
    let events = vec![
        anthropic_event("message_start", json!({
            "type": "message_start",
            "message": {"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-x",
                        "content": [], "stop_reason": null,
                        "usage": {"input_tokens": 7, "output_tokens": 0}}
        })),
        anthropic_event("content_block_start", json!({
            "type": "content_block_start", "index": 0,
            "content_block": {"type": "text", "text": ""}
        })),
        anthropic_event("content_block_delta", json!({
            "type": "content_block_delta", "index": 0,
            "delta": {"type": "text_delta", "text": "Hel"}
        })),
        anthropic_event("content_block_delta", json!({
            "type": "content_block_delta", "index": 0,
            "delta": {"type": "text_delta", "text": "lo"}
        })),
        anthropic_event("content_block_stop", json!({"type": "content_block_stop", "index": 0})),
        anthropic_event("message_delta", json!({
            "type": "message_delta",
            "delta": {"stop_reason": "end_turn", "stop_sequence": null},
            "usage": {"output_tokens": 3}
        })),
        anthropic_event("message_stop", json!({"type": "message_stop"})),
    ];

    let out = a2o_stream(events, "gpt-4o");
    assert_eq!(out.len(), 5, "two text chunks + usage + finish + [DONE]");

    let first = data_of(&out[0]);
    assert_eq!(first["id"], json!("msg_1"));
    assert_eq!(first["object"], json!("chat.completion.chunk"));
    assert_eq!(first["model"], json!("gpt-4o"), "chunks carry the canonical model name");
    assert!(first["created"].is_i64(), "chunks are stamped with a unix epoch");
    assert_eq!(
        first["choices"][0],
        json!({"index": 0, "delta": {"role": "assistant", "content": "Hel"}, "finish_reason": null}),
        "the first text chunk announces the role alongside its content"
    );

    let second = data_of(&out[1]);
    assert_eq!(
        second["choices"][0]["delta"],
        json!({"content": "lo"}),
        "later text chunks carry only the content delta"
    );

    let usage = data_of(&out[2]);
    assert_eq!(usage["choices"], json!([]));
    assert_eq!(
        usage["usage"],
        json!({"prompt_tokens": 7, "completion_tokens": 3}),
        "usage is captured from message_start.input_tokens + message_delta.output_tokens"
    );

    let finish = data_of(&out[3]);
    assert_eq!(finish["choices"][0]["delta"], json!({}));
    assert_eq!(
        finish["choices"][0]["finish_reason"],
        json!("stop"),
        "final finish_reason is stop"
    );

    assert_eq!(out[4].event, None, "chunks are data-only frames");
    assert_eq!(out[4].data, "[DONE]", "the stream ends with [DONE]");
}

#[test]
fn a2o_stream_maps_tool_use_to_tool_call_chunks() {
    let events = vec![
        anthropic_event("message_start", json!({
            "type": "message_start",
            "message": {"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-x",
                        "content": [], "stop_reason": null,
                        "usage": {"input_tokens": 7, "output_tokens": 0}}
        })),
        anthropic_event("content_block_start", json!({
            "type": "content_block_start", "index": 0,
            "content_block": {"type": "tool_use", "id": "tool_1", "name": "f", "input": {}}
        })),
        anthropic_event("content_block_delta", json!({
            "type": "content_block_delta", "index": 0,
            "delta": {"type": "input_json_delta", "partial_json": "{\"city\":"}
        })),
        anthropic_event("content_block_delta", json!({
            "type": "content_block_delta", "index": 0,
            "delta": {"type": "input_json_delta", "partial_json": "\"Paris\"}"}
        })),
        anthropic_event("content_block_stop", json!({"type": "content_block_stop", "index": 0})),
        anthropic_event("message_delta", json!({
            "type": "message_delta",
            "delta": {"stop_reason": "tool_use", "stop_sequence": null},
            "usage": {"output_tokens": 3}
        })),
        anthropic_event("message_stop", json!({"type": "message_stop"})),
    ];

    let out = a2o_stream(events, "gpt-4o");
    assert_eq!(out.len(), 6, "tool call deltas + usage + finish + [DONE]");

    let start = data_of(&out[0]);
    assert_eq!(
        start["choices"][0]["delta"]["tool_calls"],
        json!([{
            "index": 0,
            "id": "tool_1",
            "type": "function",
            "function": {"name": "f", "arguments": ""}
        }]),
        "the block start announces the tool call identity with empty arguments"
    );

    let first_args = data_of(&out[1]);
    assert_eq!(
        first_args["choices"][0]["delta"]["tool_calls"],
        json!([{"index": 0, "function": {"arguments": "{\"city\":"}}]),
        "input_json_delta partials become argument increments"
    );

    let second_args = data_of(&out[2]);
    assert_eq!(
        second_args["choices"][0]["delta"]["tool_calls"],
        json!([{"index": 0, "function": {"arguments": "\"Paris\"}"}}]),
        "arguments accumulate across chunks when concatenated"
    );

    let finish = data_of(&out[4]);
    assert_eq!(
        finish["choices"][0]["finish_reason"],
        json!("tool_calls"),
        "stop_reason tool_use maps to finish_reason tool_calls"
    );
    assert_eq!(out[5].data, "[DONE]", "the stream ends with [DONE]");
}

#[test]
fn a2o_stream_captures_usage_from_message_events() {
    let events = vec![
        anthropic_event("message_start", json!({
            "type": "message_start",
            "message": {"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-x",
                        "content": [], "stop_reason": null,
                        "usage": {"input_tokens": 7, "output_tokens": 0}}
        })),
        anthropic_event("content_block_start", json!({
            "type": "content_block_start", "index": 0,
            "content_block": {"type": "text", "text": ""}
        })),
        anthropic_event("content_block_delta", json!({
            "type": "content_block_delta", "index": 0,
            "delta": {"type": "text_delta", "text": "ok"}
        })),
        anthropic_event("content_block_stop", json!({"type": "content_block_stop", "index": 0})),
        anthropic_event("message_delta", json!({
            "type": "message_delta",
            "delta": {"stop_reason": "end_turn", "stop_sequence": null},
            "usage": {"output_tokens": 3}
        })),
        anthropic_event("message_stop", json!({"type": "message_stop"})),
    ];

    let out = a2o_stream(events, "gpt-4o");

    let usage_pos = out
        .iter()
        .position(|ev| data_of(ev).get("usage").is_some())
        .expect("a usage chunk is emitted");
    let usage = data_of(&out[usage_pos]);
    assert_eq!(
        usage["usage"],
        json!({"prompt_tokens": 7, "completion_tokens": 3}),
        "input_tokens → prompt_tokens, output_tokens → completion_tokens"
    );
    assert!(
        usage_pos < out.len() - 1,
        "the usage chunk lands before [DONE]"
    );
    assert_eq!(out.last().unwrap().data, "[DONE]");
}

#[test]
fn o2a_stream_emits_anthropic_event_sequence() {
    let events = vec![
        chunk_event(json!({
            "id": "chatcmpl-1",
            "object": "chat.completion.chunk",
            "created": 1767600000,
            "model": "gpt-4o",
            "choices": [{"index": 0, "delta": {"role": "assistant", "content": "Hel"}, "finish_reason": null}]
        })),
        chunk_event(json!({
            "id": "chatcmpl-1",
            "object": "chat.completion.chunk",
            "created": 1767600000,
            "model": "gpt-4o",
            "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]
        })),
        SseEvent {
            event: None,
            data: "[DONE]".to_string(),
        },
    ];

    let out = o2a_stream(events, "claude-x");

    let names: Vec<&str> = out.iter().map(|ev| ev.event.as_deref().unwrap_or("")).collect();
    assert_eq!(
        names,
        [
            "message_start",
            "content_block_start",
            "content_block_delta",
            "content_block_stop",
            "message_delta",
            "message_stop"
        ],
        "one content block, full Anthropic lifecycle, and no [DONE] frame"
    );

    let start = data_of(&out[0]);
    assert_eq!(start["type"], json!("message_start"));
    assert_eq!(start["message"]["id"], json!("chatcmpl-1"), "the canonical chunk id becomes the message id");
    assert_eq!(start["message"]["model"], json!("claude-x"), "model comes from the routing plan");
    assert_eq!(start["message"]["content"], json!([]));
    assert_eq!(
        start["message"]["usage"],
        json!({"input_tokens": 0, "output_tokens": 0}),
        "no usage was known yet at message_start"
    );

    let block_start = data_of(&out[1]);
    assert_eq!(block_start["content_block"], json!({"type": "text", "text": ""}));

    let delta = data_of(&out[2]);
    assert_eq!(delta["index"], json!(0));
    assert_eq!(delta["delta"], json!({"type": "text_delta", "text": "Hel"}));

    let message_delta = data_of(&out[4]);
    assert_eq!(
        message_delta["delta"],
        json!({"stop_reason": "end_turn", "stop_sequence": null}),
        "finish_reason stop maps back to stop_reason end_turn"
    );
    assert_eq!(
        message_delta["usage"],
        json!({"output_tokens": 0}),
        "the canonical stream carried no usage"
    );
}
