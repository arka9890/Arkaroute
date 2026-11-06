//! Request/response mapping between the canonical OpenAI shape and
//! Anthropic's Messages API: `o2a` goes outbound (canonical → Anthropic),
//! `a2o` comes back (Anthropic → canonical). Stream mapping lives in
//! [`stream`].

pub mod stream;

use serde_json::{json, Value};

use crate::protocol::anthropic::{
    AnthropicContentBlock, AnthropicMessage, AnthropicRequest, AnthropicResponse, AnthropicRole,
    AnthropicTool,
};
use crate::protocol::{ChatCompletionRequest, ChatMessage, Role};

/// Anthropic requires `max_tokens`; the OpenAI shape has no such field, so a
/// request without one falls back to this.
const DEFAULT_MAX_TOKENS: u32 = 4096;

/// Unix epoch seconds for the `created` field of synthesized canonical
/// bodies.
pub(crate) fn now_secs() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// Translate a canonical request into an Anthropic `/v1/messages` body.
///
/// `model` is the upstream model chosen by the routing plan; it replaces the
/// client-facing model name. System messages are folded into the top-level
/// `system` param, tool results become `tool_result` blocks on user
/// messages, and tool calls become `tool_use` blocks on assistant messages.
pub fn o2a_request(req: &ChatCompletionRequest, model: &str) -> Value {
    // OpenAI `tool_choice: "none"` means "the model must not call tools";
    // Anthropic's closest equivalent is offering no tools at all.
    let drop_tools = req
        .tool_choice
        .as_ref()
        .is_some_and(|choice| choice.as_str() == Some("none"));

    let mut system_parts: Vec<String> = Vec::new();
    let mut messages: Vec<AnthropicMessage> = Vec::new();

    for msg in &req.messages {
        match msg.role {
            Role::System => {
                if let Some(text) = plain_text(&msg.content) {
                    system_parts.push(text);
                }
            }
            Role::User => messages.push(AnthropicMessage {
                role: AnthropicRole::User,
                content: msg.content.clone().unwrap_or_else(|| json!("")),
            }),
            Role::Tool => {
                // Anthropic has no tool role: the result rides on a user
                // message as a tool_result block keyed by tool_use_id.
                let block = AnthropicContentBlock {
                    kind: "tool_result".to_string(),
                    tool_use_id: Some(msg.tool_call_id.clone().unwrap_or_default()),
                    content: Some(msg.content.clone().unwrap_or_else(|| json!(""))),
                    ..Default::default()
                };
                messages.push(AnthropicMessage {
                    role: AnthropicRole::User,
                    content: Value::Array(vec![to_value(block)]),
                });
            }
            Role::Assistant => messages.push(AnthropicMessage {
                role: AnthropicRole::Assistant,
                content: Value::Array(assistant_blocks(msg)),
            }),
        }
    }

    let request = AnthropicRequest {
        model: model.to_string(),
        system: if system_parts.is_empty() {
            None
        } else {
            Some(system_parts.join("\n\n"))
        },
        messages,
        max_tokens: req.max_tokens.unwrap_or(DEFAULT_MAX_TOKENS),
        temperature: req.temperature,
        top_p: req.top_p,
        stream: req.stream,
        tools: if drop_tools {
            Vec::new()
        } else {
            req.tools
                .iter()
                .flatten()
                .map(|tool| AnthropicTool {
                    name: tool.function.name.clone(),
                    description: tool.function.description.clone(),
                    input_schema: tool
                        .function
                        .parameters
                        .clone()
                        .unwrap_or_else(|| json!({"type": "object"})),
                })
                .collect()
        },
        tool_choice: match &req.tool_choice {
            Some(choice) if !drop_tools => Some(map_tool_choice(choice)),
            _ => None,
        },
    };
    to_value(request)
}

/// Translate an Anthropic `/v1/messages` response into a canonical
/// `chat.completion` body.
///
/// `model` replaces the upstream model with the client-facing name. Text
/// blocks are concatenated into the assistant message; `tool_use` blocks
/// become `tool_calls` with their `input` re-encoded as an arguments string;
/// `stop_reason` maps to the canonical `finish_reason`.
pub fn a2o_response(body: Value, model: &str) -> Value {
    let message: AnthropicResponse = serde_json::from_value(body).unwrap_or_default();

    let mut text = String::new();
    let mut tool_calls: Vec<Value> = Vec::new();
    for block in &message.content {
        match block.kind.as_str() {
            "text" => text.push_str(block.text.as_deref().unwrap_or_default()),
            "tool_use" => tool_calls.push(json!({
                "id": block.id.clone().unwrap_or_default(),
                "type": "function",
                "function": {
                    "name": block.name.clone().unwrap_or_default(),
                    "arguments": block
                        .input
                        .clone()
                        .unwrap_or_else(|| json!({}))
                        .to_string(),
                },
            })),
            // Block kinds without a canonical equivalent (thinking, …).
            _ => {}
        }
    }

    let mut message_obj = json!({"role": "assistant"});
    if !text.is_empty() || tool_calls.is_empty() {
        // Keep `content` even when empty, but drop it on tool-only replies —
        // that is what OpenAI sends alongside `tool_calls`.
        message_obj["content"] = json!(text);
    }
    if !tool_calls.is_empty() {
        message_obj["tool_calls"] = json!(tool_calls);
    }

    let finish_reason = canonical_finish_reason(message.stop_reason.as_deref());
    let input = message.usage.input_tokens;
    let output = message.usage.output_tokens;

    json!({
        "id": message.id,
        "object": "chat.completion",
        "created": now_secs(),
        "model": model,
        "choices": [{
            "index": 0,
            "message": message_obj,
            "finish_reason": finish_reason,
        }],
        "usage": {
            "prompt_tokens": input,
            "completion_tokens": output,
            "total_tokens": input + output,
        },
    })
}

/// Translate a canonical OpenAI `/v1/chat/completions` response into an
/// Anthropic `/v1/messages` response body — the inverse of [`a2o_response`].
pub fn o2a_response(body: Value, model: &str) -> Value {
    let id = body.get("id").and_then(Value::as_str).unwrap_or("msg_0").to_string();
    let choices = body.get("choices").and_then(Value::as_array).cloned().unwrap_or_default();
    let choice = choices.first();
    let message = choice.and_then(|c| c.get("message")).cloned().unwrap_or(Value::Null);
    let finish_reason = choice
        .and_then(|c| c.get("finish_reason"))
        .and_then(Value::as_str);

    let mut content: Vec<Value> = Vec::new();
    // Text content.
    match message.get("content") {
        Some(Value::String(s)) if !s.is_empty() => {
            content.push(json!({"type": "text", "text": s}));
        }
        Some(Value::Array(parts)) => {
            for part in parts {
                if let Some(text) = part.get("text").and_then(Value::as_str) {
                    if !text.is_empty() {
                        content.push(json!({"type": "text", "text": text}));
                    }
                }
            }
        }
        _ => {}
    }
    // Tool calls → tool_use blocks.
    if let Some(calls) = message.get("tool_calls").and_then(Value::as_array) {
        for call in calls {
            let tool_id = call.get("id").and_then(Value::as_str).unwrap_or("").to_string();
            let name = call
                .get("function")
                .and_then(|f| f.get("name"))
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_string();
            let args_str = call
                .get("function")
                .and_then(|f| f.get("arguments"))
                .and_then(Value::as_str)
                .unwrap_or("");
            let input: Value = if args_str.is_empty() {
                json!({})
            } else {
                serde_json::from_str(args_str).unwrap_or_else(|_| json!({}))
            };
            content.push(json!({
                "type": "tool_use",
                "id": tool_id,
                "name": name,
                "input": input
            }));
        }
    }

    let stop_reason = match finish_reason {
        Some("tool_calls") => "tool_use",
        Some("length") => "max_tokens",
        _ => "end_turn",
    };

    let usage = body.get("usage").cloned().unwrap_or(Value::Null);
    let input_tokens = usage
        .get("prompt_tokens")
        .and_then(Value::as_u64)
        .unwrap_or(0);
    let output_tokens = usage
        .get("completion_tokens")
        .and_then(Value::as_u64)
        .unwrap_or(0);

    json!({
        "id": id,
        "type": "message",
        "role": "assistant",
        "model": model,
        "content": content,
        "stop_reason": stop_reason,
        "stop_sequence": null,
        "usage": {
            "input_tokens": input_tokens,
            "output_tokens": output_tokens
        }
    })
}

/// Anthropic `stop_reason` → canonical `finish_reason`. Unknown reasons
/// degrade to `stop`, which every OpenAI client understands.
pub(crate) fn canonical_finish_reason(stop_reason: Option<&str>) -> &'static str {
    match stop_reason {
        Some("tool_use") => "tool_calls",
        Some("max_tokens") => "length",
        _ => "stop",
    }
}

/// The plain text of a system message: a bare string, or the concatenated
/// `text` parts of an OpenAI content array. `None` when there is no text.
fn plain_text(content: &Option<Value>) -> Option<String> {
    match content.as_ref()? {
        Value::String(s) => Some(s.clone()),
        Value::Array(parts) => {
            let texts: Vec<&str> = parts
                .iter()
                .filter_map(|part| part.get("text").and_then(Value::as_str))
                .collect();
            if texts.is_empty() {
                None
            } else {
                Some(texts.join("\n"))
            }
        }
        _ => None,
    }
}

/// The content blocks of an assistant message: its text (when a bare string)
/// plus one `tool_use` block per tool call, with `input` parsed from the
/// JSON-encoded arguments.
fn assistant_blocks(msg: &ChatMessage) -> Vec<Value> {
    let mut blocks: Vec<Value> = Vec::new();

    if let Some(content) = &msg.content {
        match content {
            Value::String(s) if !s.is_empty() => blocks.push(to_value(AnthropicContentBlock {
                kind: "text".to_string(),
                text: Some(s.clone()),
                ..Default::default()
            })),
            // OpenAI text parts already carry Anthropic's `{"type","text"}`
            // shape, so they pass through as blocks verbatim.
            Value::Array(parts) => blocks.extend(parts.iter().cloned()),
            _ => {}
        }
    }

    for call in msg.tool_calls.iter().flatten() {
        let input = call
            .function
            .as_ref()
            .and_then(|function| function.arguments.as_deref())
            .filter(|args| !args.is_empty())
            .and_then(|args| serde_json::from_str(args).ok())
            .unwrap_or_else(|| json!({}));
        blocks.push(to_value(AnthropicContentBlock {
            kind: "tool_use".to_string(),
            id: Some(call.id.clone().unwrap_or_default()),
            name: Some(
                call.function
                    .as_ref()
                    .and_then(|function| function.name.clone())
                    .unwrap_or_default(),
            ),
            input: Some(input),
            ..Default::default()
        }));
    }

    blocks
}

/// Map an OpenAI `tool_choice` onto Anthropic's shape: `"auto"` →
/// `{"type":"auto"}`, `"required"` → `{"type":"any"}`, and
/// `{"type":"function",…}` → `{"type":"tool",…}`. Anything else passes
/// through unchanged (including Anthropic-native values).
fn map_tool_choice(choice: &Value) -> Value {
    match choice.as_str() {
        Some("auto") => return json!({"type": "auto"}),
        Some("required") => return json!({"type": "any"}),
        _ => {}
    }
    if let Some(name) = choice
        .get("function")
        .and_then(|function| function.get("name"))
        .and_then(Value::as_str)
    {
        return json!({"type": "tool", "name": name});
    }
    choice.clone()
}

/// Serialize a wire type whose shapes are static; serialization of these
/// structs cannot fail, so a failure would be a programming error.
fn to_value<T: serde::Serialize>(value: T) -> Value {
    serde_json::to_value(value).expect("anthropic wire types always serialize")
}

/// Translate an Anthropic `/v1/messages` body into a canonical
/// `ChatCompletionRequest` so the gateway can route it like any other.
pub fn inbound_messages(body: Value) -> Result<ChatCompletionRequest, serde_json::Error> {
    let model = body
        .get("model")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string();

    let mut messages: Vec<Value> = Vec::new();

    // `system` → first system message.
    if let Some(system) = body.get("system") {
        if let Some(text) = system_to_text(system) {
            messages.push(json!({"role": "system", "content": text}));
        }
    }

    if let Some(arr) = body.get("messages").and_then(Value::as_array) {
        for m in arr {
            let role = m.get("role").and_then(Value::as_str).unwrap_or("user");
            let content = m.get("content").cloned().unwrap_or(Value::Null);
            match role {
                "assistant" => {
                    let (text_content, tool_calls) = convert_assistant_content(&content);
                    let mut msg = json!({"role": "assistant"});
                    if let Some(c) = text_content {
                        msg["content"] = c;
                    }
                    if !tool_calls.is_empty() {
                        msg["tool_calls"] = Value::Array(tool_calls);
                    }
                    messages.push(msg);
                }
                "user" => {
                    match &content {
                        Value::String(s) => {
                            messages.push(json!({"role": "user", "content": s}));
                        }
                        Value::Array(blocks) => {
                            let mut text_blocks: Vec<Value> = Vec::new();
                            let mut other_blocks: Vec<Value> = Vec::new();
                            let mut tool_results: Vec<Value> = Vec::new();
                            for b in blocks {
                                let kind = b.get("type").and_then(Value::as_str).unwrap_or("");
                                if kind == "tool_result" {
                                    tool_results.push(b.clone());
                                } else if kind == "text" {
                                    text_blocks.push(b.clone());
                                } else {
                                    other_blocks.push(b.clone());
                                }
                            }
                            let mut user_blocks: Vec<Value> = Vec::new();
                            user_blocks.extend(text_blocks);
                            user_blocks.extend(other_blocks);
                            if !user_blocks.is_empty() {
                                if user_blocks.len() == 1
                                    && user_blocks[0].get("type") == Some(&json!("text"))
                                {
                                    if let Some(t) = user_blocks[0].get("text").and_then(Value::as_str) {
                                        messages.push(json!({"role": "user", "content": t}));
                                    } else {
                                        messages.push(json!({"role": "user", "content": user_blocks}));
                                    }
                                } else {
                                    messages.push(json!({"role": "user", "content": user_blocks}));
                                }
                            }
                            for tr in tool_results {
                                let tool_call_id = tr
                                    .get("tool_use_id")
                                    .and_then(Value::as_str)
                                    .unwrap_or("")
                                    .to_string();
                                let c = tr.get("content").cloned().unwrap_or(Value::String(String::new()));
                                let content_str = content_to_string(&c);
                                messages.push(json!({
                                    "role": "tool",
                                    "tool_call_id": tool_call_id,
                                    "content": content_str
                                }));
                            }
                        }
                        _ => {
                            messages.push(json!({"role": "user", "content": content}));
                        }
                    }
                }
                other => {
                    messages.push(json!({"role": other, "content": content}));
                }
            }
        }
    }

    let mut req = json!({
        "model": model,
        "messages": messages,
        "stream": body.get("stream").and_then(Value::as_bool).unwrap_or(false),
    });
    if let Some(mt) = body.get("max_tokens") {
        req["max_tokens"] = mt.clone();
    }
    if let Some(t) = body.get("temperature") {
        req["temperature"] = t.clone();
    }
    if let Some(p) = body.get("top_p") {
        req["top_p"] = p.clone();
    }
    if let Some(tools) = body.get("tools").and_then(Value::as_array) {
        let out: Vec<Value> = tools
            .iter()
            .map(|t| {
                json!({
                    "type": "function",
                    "function": {
                        "name": t.get("name").cloned().unwrap_or(Value::Null),
                        "description": t.get("description").cloned(),
                        "parameters": t.get("input_schema").cloned().unwrap_or_else(|| json!({"type": "object"}))
                    }
                })
            })
            .collect();
        req["tools"] = Value::Array(out);
    }
    if let Some(tc) = body.get("tool_choice") {
        req["tool_choice"] = convert_anthropic_tool_choice(tc);
    }
    if let Some(thinking) = body.get("thinking") {
        req["thinking"] = thinking.clone();
    }

    ChatCompletionRequest::from_value(req)
}

fn system_to_text(system: &Value) -> Option<String> {
    match system {
        Value::String(s) => Some(s.clone()),
        Value::Array(blocks) => {
            let texts: Vec<&str> = blocks
                .iter()
                .filter_map(|b| b.get("text").and_then(Value::as_str))
                .collect();
            if texts.is_empty() {
                None
            } else {
                Some(texts.join("\n"))
            }
        }
        _ => None,
    }
}

fn convert_assistant_content(content: &Value) -> (Option<Value>, Vec<Value>) {
    match content {
        Value::String(s) => {
            if s.is_empty() {
                (None, Vec::new())
            } else {
                (Some(json!(s)), Vec::new())
            }
        }
        Value::Array(blocks) => {
            let mut text_parts: Vec<String> = Vec::new();
            let mut tool_calls: Vec<Value> = Vec::new();
            for b in blocks {
                let kind = b.get("type").and_then(Value::as_str).unwrap_or("");
                match kind {
                    "text" => {
                        if let Some(t) = b.get("text").and_then(Value::as_str) {
                            if !t.is_empty() {
                                text_parts.push(t.to_string());
                            }
                        }
                    }
                    "tool_use" => {
                        let id = b.get("id").cloned().unwrap_or(Value::Null);
                        let name = b
                            .get("name")
                            .and_then(Value::as_str)
                            .unwrap_or("")
                            .to_string();
                        let input = b.get("input").cloned().unwrap_or_else(|| json!({}));
                        tool_calls.push(json!({
                            "id": id,
                            "type": "function",
                            "function": {
                                "name": name,
                                "arguments": input.to_string()
                            }
                        }));
                    }
                    _ => {}
                }
            }
            let text_content = if text_parts.is_empty() {
                None
            } else {
                Some(json!(text_parts.join("")))
            };
            (text_content, tool_calls)
        }
        _ => (None, Vec::new()),
    }
}

fn content_to_string(v: &Value) -> String {
    match v {
        Value::String(s) => s.clone(),
        Value::Array(blocks) => blocks
            .iter()
            .filter_map(|b| b.get("text").and_then(Value::as_str))
            .collect::<Vec<_>>()
            .join(""),
        _ => v.to_string(),
    }
}

fn convert_anthropic_tool_choice(tc: &Value) -> Value {
    if let Some(t) = tc.get("type").and_then(Value::as_str) {
        match t {
            "auto" => return json!("auto"),
            "any" => return json!("required"),
            "tool" => {
                if let Some(name) = tc.get("name").and_then(Value::as_str) {
                    return json!({"type": "function", "function": {"name": name}});
                }
            }
            _ => {}
        }
    }
    tc.clone()
}
