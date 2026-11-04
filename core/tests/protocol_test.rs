//! Roundtrip and passthrough tests for the canonical OpenAI-shaped types.

use omniroute_core::protocol::{ChatCompletionRequest, Role};
use serde_json::json;

#[test]
fn deserializes_openai_request_and_keeps_unknown_fields() {
    let body = json!({
        "model": "gpt-4o",
        "messages": [{"role": "user", "content": "hi"}],
        "store": true,
        "metadata": {"a": 1}
    });

    let req = ChatCompletionRequest::from_value(body).unwrap();

    assert_eq!(req.model, "gpt-4o");
    // Clients may omit `stream`; it must default to false.
    assert!(!req.stream, "stream must default to false when absent");
    // Unknown provider-specific parameters are preserved verbatim.
    assert_eq!(req.extra.get("store"), Some(&json!(true)));
    assert_eq!(req.extra.get("metadata"), Some(&json!({"a": 1})));
}

#[test]
fn serializes_roundtrip_without_losing_fields() {
    let original = json!({
        "model": "gpt-4o",
        "stream": true,
        "max_tokens": 1024,
        "temperature": 0.7,
        "top_p": 0.9,
        "tool_choice": "auto",
        "tools": [{
            "type": "function",
            "function": {
                "name": "get_weather",
                "description": "Get the weather",
                "parameters": {"type": "object", "properties": {"city": {"type": "string"}}}
            }
        }],
        "messages": [
            {"role": "system", "content": [{"type": "text", "text": "be terse"}]},
            {"role": "user", "name": "alice", "content": "weather in Paris?"},
            {"role": "assistant", "tool_calls": [{
                "id": "call_1",
                "type": "function",
                "function": {"name": "get_weather", "arguments": "{\"city\":\"Paris\"}"}
            }]},
            {"role": "tool", "tool_call_id": "call_1", "content": "sunny"},
            {"role": "tool", "tool_call_id": "call_2"}
        ],
        "stream_options": {"include_usage": true},
        "store": true
    });

    let parsed = ChatCompletionRequest::from_value(original.clone()).unwrap();
    let serialized = serde_json::to_value(&parsed).unwrap();
    assert_eq!(serialized, original, "serialization must not drop or add fields");

    let reparsed: ChatCompletionRequest = serde_json::from_value(serialized).unwrap();
    assert_eq!(reparsed, parsed, "parse/serialize/parse must be lossless");
}

#[test]
fn message_content_may_be_string_or_array() {
    let body = json!({
        "model": "gpt-4o",
        "messages": [
            {"role": "user", "content": "hi"},
            {"role": "user", "content": [{"type": "text", "text": "hi"}]}
        ]
    });

    let req = ChatCompletionRequest::from_value(body).unwrap();

    assert_eq!(req.messages[0].content, Some(json!("hi")));
    assert_eq!(
        req.messages[1].content,
        Some(json!([{"type": "text", "text": "hi"}]))
    );
}

#[test]
fn tool_call_message_parses() {
    let body = json!({
        "model": "gpt-4o",
        "messages": [
            {"role": "assistant", "content": null, "tool_calls": [{
                "id": "call_1",
                "type": "function",
                "function": {"name": "get_weather", "arguments": "{\"city\":\"Paris\"}"}
            }]},
            {"role": "tool", "tool_call_id": "call_1", "content": "sunny"}
        ]
    });

    let req = ChatCompletionRequest::from_value(body).unwrap();

    let assistant = &req.messages[0];
    assert_eq!(assistant.role, Role::Assistant);
    let calls = assistant.tool_calls.as_ref().unwrap();
    assert_eq!(calls[0].id.as_deref(), Some("call_1"));
    assert_eq!(calls[0].r#type.as_deref(), Some("function"));
    let func = calls[0].function.as_ref().unwrap();
    assert_eq!(func.name.as_deref(), Some("get_weather"));
    assert_eq!(func.arguments.as_deref(), Some("{\"city\":\"Paris\"}"));

    let tool = &req.messages[1];
    assert_eq!(tool.role, Role::Tool);
    assert_eq!(tool.tool_call_id.as_deref(), Some("call_1"));
}

#[test]
fn parses_standard_openai_tool_entry_with_type_key() {
    let body = json!({
        "model": "gpt-4o",
        "messages": [{"role": "user", "content": "hi"}],
        "tools": [{
            "type": "function",
            "function": {"name": "get_weather", "strict": true, "parameters": {"type": "object"}}
        }]
    });

    let req = ChatCompletionRequest::from_value(body).unwrap();

    let tools = req.tools.as_ref().unwrap();
    assert_eq!(tools[0].kind, "function", "wire key \"type\" maps to `kind`");
    assert_eq!(tools[0].function.name, "get_weather");
    assert_eq!(
        tools[0].function.extra.get("strict"),
        Some(&json!(true)),
        "unknown FunctionDef fields must survive via extra"
    );

    // And it must serialize back to the exact wire shape it came from.
    let out = serde_json::to_value(&req).unwrap();
    assert_eq!(out["tools"][0]["type"], json!("function"));
}

#[test]
fn response_usage_extras_roundtrip_losslessly() {
    let original = json!({
        "id": "chatcmpl-1",
        "object": "chat.completion",
        "created": 1730000000,
        "model": "gpt-4o",
        "choices": [{
            "index": 0,
            "message": {"role": "assistant", "content": "hi"},
            "finish_reason": "stop"
        }],
        "usage": {
            "prompt_tokens": 10,
            "completion_tokens": 5,
            "total_tokens": 15,
            "prompt_tokens_details": {"cached_tokens": 4},
            "completion_tokens_details": {"reasoning_tokens": 0}
        }
    });

    let resp: omniroute_core::protocol::ChatCompletionResponse =
        serde_json::from_value(original.clone()).unwrap();

    assert_eq!(resp.usage.as_ref().unwrap().prompt_tokens, 10);
    assert_eq!(resp.usage.as_ref().unwrap().total_tokens, 15);
    // Unknown usage detail blocks must survive untouched.
    assert_eq!(
        resp.usage.as_ref().unwrap().extra.get("prompt_tokens_details"),
        Some(&json!({"cached_tokens": 4}))
    );

    let out = serde_json::to_value(&resp).unwrap();
    assert_eq!(out, original, "response serialization must lose nothing");
}
