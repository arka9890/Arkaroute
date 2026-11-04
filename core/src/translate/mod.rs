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
