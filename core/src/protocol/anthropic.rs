//! Anthropic Messages API wire types: request, response, and stream events.
//!
//! These mirror the JSON shapes exchanged over `POST /v1/messages` and its
//! `text/event-stream` response. Field names, defaults, and key order follow
//! the pinned wire format: optional fields are omitted when absent, while
//! `stop_reason`/`stop_sequence` serialize as an explicit `null` and the
//! `type` tag of every frame is always present.

use serde::{Deserialize, Serialize};

/// `false` for present-but-false bools (omit `stream: false` on the wire).
fn is_false(v: &bool) -> bool {
    !*v
}

/// The `role` of an Anthropic message; responses are always `assistant`.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AnthropicRole {
    #[default]
    Assistant,
    User,
}

/// A request body for `POST /v1/messages`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct AnthropicRequest {
    pub model: String,
    /// System prompts folded out of the message list; joined with `\n\n`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub system: Option<String>,
    pub messages: Vec<AnthropicMessage>,
    /// Anthropic has no default for this: the gateway falls back to 4096.
    pub max_tokens: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub temperature: Option<f64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub top_p: Option<f64>,
    /// Defaults to `false`, like the canonical shape.
    #[serde(default, skip_serializing_if = "is_false")]
    pub stream: bool,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub tools: Vec<AnthropicTool>,
    /// `{"type": "auto" | "any" | "tool", "name": …}`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tool_choice: Option<serde_json::Value>,
}

/// One message in [`AnthropicRequest::messages`]: a string (simple turns) or
/// a list of content blocks (tool results, tool use, mixed content), so
/// `content` stays a `Value`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct AnthropicMessage {
    pub role: AnthropicRole,
    pub content: serde_json::Value,
}

/// A tool offered to the model: the OpenAI `parameters` schema arrives as
/// Anthropic's `input_schema` under the tool's own name.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct AnthropicTool {
    pub name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    pub input_schema: serde_json::Value,
}

/// A non-streaming `/v1/messages` response.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct AnthropicResponse {
    pub id: String,
    #[serde(rename = "type", default)]
    pub kind: String,
    #[serde(default)]
    pub role: AnthropicRole,
    #[serde(default)]
    pub model: String,
    #[serde(default)]
    pub content: Vec<AnthropicContentBlock>,
    /// `"end_turn" | "max_tokens" | "stop_sequence" | "tool_use" | …`.
    #[serde(default)]
    pub stop_reason: Option<String>,
    #[serde(default)]
    pub stop_sequence: Option<String>,
    #[serde(default)]
    pub usage: AnthropicUsage,
}

/// One content block: `text`/`tool_use` in responses, `tool_result` in
/// requests. Only the fields of the block's kind are serialized.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct AnthropicContentBlock {
    #[serde(rename = "type")]
    pub kind: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub input: Option<serde_json::Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tool_use_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub content: Option<serde_json::Value>,
}

/// Token accounting on the Anthropic side (`input_tokens` → prompt,
/// `output_tokens` → completion).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct AnthropicUsage {
    #[serde(default)]
    pub input_tokens: u64,
    #[serde(default)]
    pub output_tokens: u64,
}

/// One decoded Anthropic streaming event: the JSON `data:` payload of an SSE
/// frame, whose `type` tag matches the frame's `event:` name.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum AnthropicStreamEvent {
    MessageStart {
        message: MessageStartInfo,
    },
    ContentBlockStart {
        index: u64,
        content_block: AnthropicContentBlock,
    },
    ContentBlockDelta {
        index: u64,
        delta: BlockDelta,
    },
    ContentBlockStop {
        index: u64,
    },
    MessageDelta {
        delta: MessageDeltaInfo,
        usage: MessageDeltaUsage,
    },
    MessageStop {},
    Ping {},
    /// Any event type the gateway does not model (e.g. `error`).
    #[serde(other)]
    Unknown,
}

/// The `message` object inside a `message_start` event (an empty message the
/// stream then fills block by block).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct MessageStartInfo {
    pub id: String,
    #[serde(rename = "type", default)]
    pub kind: String,
    pub role: AnthropicRole,
    pub model: String,
    #[serde(default)]
    pub content: serde_json::Value,
    #[serde(default)]
    pub stop_reason: Option<String>,
    #[serde(default)]
    pub usage: AnthropicUsage,
}

/// The `delta` of a `content_block_delta` event: `text_delta` or
/// `input_json_delta`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct BlockDelta {
    #[serde(rename = "type")]
    pub kind: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub partial_json: Option<String>,
}

/// The `delta` of the closing `message_delta` event. Both fields serialize
/// even when `None` (the wire shows an explicit `null`).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct MessageDeltaInfo {
    #[serde(default)]
    pub stop_reason: Option<String>,
    #[serde(default)]
    pub stop_sequence: Option<String>,
}

/// The `usage` of the closing `message_delta` event: only the output side is
/// known at that point (the input side arrived with `message_start`).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct MessageDeltaUsage {
    #[serde(default)]
    pub output_tokens: u64,
}
