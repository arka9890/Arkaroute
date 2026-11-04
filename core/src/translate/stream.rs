//! Stream mapping: `a2o_stream` (Anthropic events → canonical chunks, ending
//! in `[DONE]`) and `o2a_stream` (canonical chunks → Anthropic events, which
//! end at `message_stop` with no `[DONE]` counterpart).
//!
//! Both are pure `Vec<SseEvent> → Vec<SseEvent>` functions so tests stay
//! synchronous; the async adapters feed them incrementally, keeping the same
//! per-event state (the open content block, `input_tokens`, `stop_reason`).

use serde_json::{json, Value};

use crate::protocol::anthropic::{
    AnthropicContentBlock, AnthropicRole, AnthropicStreamEvent, AnthropicUsage, BlockDelta,
    MessageDeltaInfo, MessageDeltaUsage, MessageStartInfo,
};
use crate::sse::SseEvent;

use super::canonical_finish_reason;

/// Translate a decoded Anthropic event stream into canonical chat chunks,
/// always terminated by the `[DONE]` sentinel. Frame events the gateway does
/// not model (`ping`, `error`, …) are ignored.
pub fn a2o_stream(events: Vec<SseEvent>, model: &str) -> Vec<SseEvent> {
    let mut out = Vec::new();
    let created = super::now_secs();

    // Stream-wide state carried across events.
    let mut id = String::new();
    let mut input_tokens: u64 = 0;
    let mut role_sent = false;
    // The open `tool_use` block: (block index, tool id, function name).
    let mut tool: Option<(u64, String, String)> = None;
    let mut finished = false;

    for ev in events {
        let Some(event) = decode_anthropic(&ev) else {
            continue;
        };
        match event {
            AnthropicStreamEvent::MessageStart { message } => {
                id = message.id;
                input_tokens = message.usage.input_tokens;
            }
            AnthropicStreamEvent::ContentBlockStart {
                index,
                content_block,
            } => {
                if content_block.kind == "tool_use" {
                    // The block start announces the call's identity with
                    // empty arguments; argument deltas follow.
                    let tool_id = content_block.id.unwrap_or_default();
                    let name = content_block.name.unwrap_or_default();
                    out.push(canonical_chunk(
                        &id,
                        created,
                        model,
                        json!({
                            "tool_calls": [{
                                "index": index,
                                "id": tool_id,
                                "type": "function",
                                "function": {"name": name, "arguments": ""},
                            }]
                        }),
                        Value::Null,
                    ));
                    tool = Some((index, tool_id, name));
                }
            }
            AnthropicStreamEvent::ContentBlockDelta { delta, .. } => match delta.kind.as_str() {
                "text_delta" => {
                    let text = delta.text.unwrap_or_default();
                    if text.is_empty() {
                        continue;
                    }
                    // The role rides on the first text chunk; later chunks
                    // carry content only.
                    let mut content = json!({"content": text});
                    if !role_sent {
                        content["role"] = json!("assistant");
                        role_sent = true;
                    }
                    out.push(canonical_chunk(&id, created, model, content, Value::Null));
                }
                "input_json_delta" => {
                    let arguments = delta.partial_json.unwrap_or_default();
                    let Some((index, ..)) = &tool else {
                        continue; // delta without a preceding block start
                    };
                    if arguments.is_empty() {
                        continue;
                    }
                    out.push(canonical_chunk(
                        &id,
                        created,
                        model,
                        json!({
                            "tool_calls": [{
                                "index": index,
                                "function": {"arguments": arguments},
                            }]
                        }),
                        Value::Null,
                    ));
                }
                _ => {} // thinking/signature deltas have no canonical shape
            },
            AnthropicStreamEvent::ContentBlockStop { .. } => {
                tool = None;
            }
            AnthropicStreamEvent::MessageDelta { delta, usage } => {
                if finished {
                    continue;
                }
                finished = true;
                // The usage totals are complete exactly here: emit them
                // before the closing chunk, then finish.
                let output_tokens = usage.output_tokens;
                if input_tokens > 0 || output_tokens > 0 {
                    out.push(canonical_usage_chunk(
                        &id,
                        created,
                        model,
                        input_tokens,
                        output_tokens,
                    ));
                }
                out.push(canonical_chunk(
                    &id,
                    created,
                    model,
                    json!({}),
                    json!(canonical_finish_reason(delta.stop_reason.as_deref())),
                ));
            }
            AnthropicStreamEvent::MessageStop {}
            | AnthropicStreamEvent::Ping {}
            | AnthropicStreamEvent::Unknown => {}
        }
    }

    out.push(SseEvent {
        event: None,
        data: "[DONE]".to_string(),
    });
    out
}

/// Translate canonical chat chunks into an Anthropic event sequence:
/// `message_start`, content block lifecycle, `message_delta`, `message_stop`.
/// The `[DONE]` sentinel is consumed without a counterpart — Anthropic
/// streams end at `message_stop`.
pub fn o2a_stream(events: Vec<SseEvent>, model: &str) -> Vec<SseEvent> {
    let mut out = Vec::new();

    // Synthesis state carried across chunks.
    let mut id: Option<String> = None;
    let mut started = false;
    let mut open: Option<OpenBlock> = None;
    let mut next_index: u64 = 0;
    let mut stop_reason: Option<&'static str> = None;
    let mut input_tokens: u64 = 0;
    let mut output_tokens: u64 = 0;

    for ev in events {
        if ev.data == "[DONE]" {
            break;
        }
        let Ok(chunk) = serde_json::from_str::<Value>(&ev.data) else {
            continue;
        };
        if id.is_none() {
            id = chunk["id"].as_str().map(str::to_string);
        }
        if let Some(usage) = chunk.get("usage") {
            if let Some(tokens) = usage["prompt_tokens"].as_u64() {
                input_tokens = tokens;
            }
            if let Some(tokens) = usage["completion_tokens"].as_u64() {
                output_tokens = tokens;
            }
        }
        let Some(choices) = chunk.get("choices").and_then(Value::as_array) else {
            continue;
        };
        if choices.is_empty() {
            continue; // usage-only frame, already captured above
        }
        let choice = &choices[0];
        if let Some(reason) = choice["finish_reason"].as_str() {
            stop_reason = Some(anthropic_stop_reason(reason));
        }
        if !started {
            started = true;
            out.push(message_start_frame(
                id.as_deref().unwrap_or("msg_0"),
                model,
                input_tokens,
            ));
        }

        let delta = &choice["delta"];
        if let Some(content) = delta["content"].as_str().filter(|c| !c.is_empty()) {
            if !matches!(open, Some(OpenBlock::Text { .. })) {
                close_block(&mut open, &mut out);
                let index = next_index;
                next_index += 1;
                out.push(block_start_frame(
                    index,
                    AnthropicContentBlock {
                        kind: "text".to_string(),
                        text: Some(String::new()),
                        ..Default::default()
                    },
                ));
                open = Some(OpenBlock::Text { index });
            }
            let Some(block) = &open else {
                continue; // unreachable: the block was just opened
            };
            out.push(block_delta_frame(
                block.index(),
                BlockDelta {
                    kind: "text_delta".to_string(),
                    text: Some(content.to_string()),
                    partial_json: None,
                },
            ));
        }

        if let Some(calls) = delta["tool_calls"].as_array() {
            for call in calls {
                let call_index = call["index"].as_u64();
                // A new call index opens a new block; an index-less delta
                // continues the open one.
                let same_tool = match (&open, call_index) {
                    (Some(OpenBlock::Tool { call_index: open_call, .. }), Some(index)) => {
                        *open_call == Some(index)
                    }
                    (Some(OpenBlock::Tool { .. }), None) => true,
                    _ => false,
                };
                if !same_tool {
                    close_block(&mut open, &mut out);
                    let index = next_index;
                    next_index += 1;
                    out.push(block_start_frame(
                        index,
                        AnthropicContentBlock {
                            kind: "tool_use".to_string(),
                            id: Some(call["id"].as_str().unwrap_or_default().to_string()),
                            name: Some(call["function"]["name"].as_str().unwrap_or_default().to_string()),
                            input: Some(json!({})),
                            ..Default::default()
                        },
                    ));
                    open = Some(OpenBlock::Tool { index, call_index });
                }
                // Each chunk's `arguments` is one increment of the JSON
                // string, which becomes one `input_json_delta`.
                let Some(arguments) =
                    call["function"]["arguments"].as_str().filter(|a| !a.is_empty())
                else {
                    continue;
                };
                let Some(OpenBlock::Tool { index, .. }) = &open else {
                    continue;
                };
                out.push(block_delta_frame(
                    *index,
                    BlockDelta {
                        kind: "input_json_delta".to_string(),
                        text: None,
                        partial_json: Some(arguments.to_string()),
                    },
                ));
            }
        }
    }

    if started {
        close_block(&mut open, &mut out);
        out.push(message_delta_frame(stop_reason, output_tokens));
        out.push(event_frame(
            "message_stop",
            AnthropicStreamEvent::MessageStop {},
        ));
    }
    out
}

/// The content block being synthesized onto the Anthropic side.
enum OpenBlock {
    Text {
        index: u64,
    },
    Tool {
        index: u64,
        /// The canonical `tool_calls[].index` this block corresponds to.
        call_index: Option<u64>,
    },
}

impl OpenBlock {
    fn index(&self) -> u64 {
        match self {
            Self::Text { index } | Self::Tool { index, .. } => *index,
        }
    }
}

/// Close the open block, if any, with its `content_block_stop`.
fn close_block(open: &mut Option<OpenBlock>, out: &mut Vec<SseEvent>) {
    if let Some(block) = open.take() {
        out.push(block_stop_frame(block.index()));
    }
}

/// Decode one Anthropic SSE frame: the payload's `type` tag is preferred,
/// falling back to the frame's `event:` name when the tag is absent.
fn decode_anthropic(ev: &SseEvent) -> Option<AnthropicStreamEvent> {
    let mut value: Value = serde_json::from_str(&ev.data).ok()?;
    if value.is_object() && value.get("type").is_none() {
        if let Some(name) = &ev.event {
            value["type"] = Value::String(name.clone());
        }
    }
    serde_json::from_value(value).ok()
}

/// One canonical `chat.completion.chunk` frame (data-only, explicit
/// `finish_reason`, `null` until the closing chunk).
fn canonical_chunk(
    id: &str,
    created: i64,
    model: &str,
    delta: Value,
    finish_reason: Value,
) -> SseEvent {
    SseEvent {
        event: None,
        data: json!({
            "id": id,
            "object": "chat.completion.chunk",
            "created": created,
            "model": model,
            "choices": [{"index": 0, "delta": delta, "finish_reason": finish_reason}],
        })
        .to_string(),
    }
}

/// The optional usage frame: token counts with an empty `choices` array.
fn canonical_usage_chunk(id: &str, created: i64, model: &str, input: u64, output: u64) -> SseEvent {
    SseEvent {
        event: None,
        data: json!({
            "id": id,
            "object": "chat.completion.chunk",
            "created": created,
            "model": model,
            "choices": [],
            "usage": {"prompt_tokens": input, "completion_tokens": output},
        })
        .to_string(),
    }
}

/// One Anthropic SSE frame: `event:` name matching the payload's `type`.
fn event_frame(name: &str, event: AnthropicStreamEvent) -> SseEvent {
    SseEvent {
        event: Some(name.to_string()),
        data: serde_json::to_string(&event).expect("anthropic stream events always serialize"),
    }
}

fn message_start_frame(id: &str, model: &str, input_tokens: u64) -> SseEvent {
    event_frame(
        "message_start",
        AnthropicStreamEvent::MessageStart {
            message: MessageStartInfo {
                id: id.to_string(),
                kind: "message".to_string(),
                role: AnthropicRole::Assistant,
                model: model.to_string(),
                content: json!([]),
                stop_reason: None,
                usage: AnthropicUsage {
                    input_tokens,
                    output_tokens: 0,
                },
            },
        },
    )
}

fn block_start_frame(index: u64, content_block: AnthropicContentBlock) -> SseEvent {
    event_frame(
        "content_block_start",
        AnthropicStreamEvent::ContentBlockStart {
            index,
            content_block,
        },
    )
}

fn block_delta_frame(index: u64, delta: BlockDelta) -> SseEvent {
    event_frame(
        "content_block_delta",
        AnthropicStreamEvent::ContentBlockDelta { index, delta },
    )
}

fn block_stop_frame(index: u64) -> SseEvent {
    event_frame(
        "content_block_stop",
        AnthropicStreamEvent::ContentBlockStop { index },
    )
}

/// The closing `message_delta`: the final `stop_reason` (defaulting to
/// `end_turn` when the stream never carried one) plus output token totals.
fn message_delta_frame(stop_reason: Option<&'static str>, output_tokens: u64) -> SseEvent {
    event_frame(
        "message_delta",
        AnthropicStreamEvent::MessageDelta {
            delta: MessageDeltaInfo {
                stop_reason: Some(stop_reason.unwrap_or("end_turn").to_string()),
                stop_sequence: None,
            },
            usage: MessageDeltaUsage { output_tokens },
        },
    )
}

/// Canonical `finish_reason` → Anthropic `stop_reason`.
fn anthropic_stop_reason(finish_reason: &str) -> &'static str {
    match finish_reason {
        "length" => "max_tokens",
        "tool_calls" => "tool_use",
        _ => "end_turn",
    }
}
