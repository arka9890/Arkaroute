//! Tests for the incremental SSE parser and formatter.

use omniroute_core::sse::{data_only, format_event, SseEvent, SseParser, DONE};

#[test]
fn parses_single_event() {
    let mut p = SseParser::new();
    let events = p.push(b"data: hello\n\n");
    assert_eq!(events.len(), 1);
    assert_eq!(events[0].event, None);
    assert_eq!(events[0].data, "hello");
}

#[test]
fn parses_event_split_across_arbitrary_chunk_boundaries() {
    let bytes = b"data: {\"a\":1}\n\n";
    let mut p = SseParser::new();
    let mut events = Vec::new();
    for &b in bytes.iter() {
        events.extend(p.push(&[b]));
    }
    assert_eq!(events.len(), 1, "byte-by-byte feed must yield exactly one event");
    assert_eq!(events[0].event, None);
    assert_eq!(events[0].data, "{\"a\":1}");
}

#[test]
fn parses_named_event() {
    let mut p = SseParser::new();
    let events = p.push(b"event: message_start\ndata: {\"x\":1}\n\n");
    assert_eq!(events.len(), 1);
    assert_eq!(events[0].event.as_deref(), Some("message_start"));
    assert_eq!(events[0].data, "{\"x\":1}");
}

#[test]
fn joins_multi_line_data_with_newline() {
    let mut p = SseParser::new();
    let events = p.push(b"data: line1\ndata: line2\n\n");
    assert_eq!(events.len(), 1);
    assert_eq!(events[0].event, None);
    assert_eq!(events[0].data, "line1\nline2");
}

#[test]
fn finish_emits_incomplete_trailing_event() {
    let mut p = SseParser::new();
    let events = p.push(b"data: tail");
    assert!(events.is_empty(), "no blank line yet, nothing to dispatch");
    let events = p.finish();
    assert_eq!(events.len(), 1);
    assert_eq!(events[0].event, None);
    assert_eq!(events[0].data, "tail");
}

#[test]
fn format_roundtrips() {
    let cases = vec![
        SseEvent { event: None, data: "hello".into() },
        SseEvent { event: Some("message_start".into()), data: "{\"a\":1}".into() },
        SseEvent { event: None, data: "line1\nline2".into() },
    ];
    for case in cases {
        let wire = format_event(&case);
        let mut p = SseParser::new();
        let mut events = p.push(wire.as_bytes());
        events.extend(p.finish());
        assert_eq!(events.len(), 1, "wire form: {wire:?}");
        assert_eq!(events[0], case, "wire form: {wire:?}");
    }
    // data_only / DONE constants parse back too.
    let mut p = SseParser::new();
    let events = p.push(data_only("[DONE]").as_bytes());
    assert_eq!(events.len(), 1);
    assert_eq!(events[0].data, "[DONE]");
    let mut p = SseParser::new();
    let events = p.push(DONE.as_bytes());
    assert_eq!(events.len(), 1);
    assert_eq!(events[0].data, "[DONE]");
}
