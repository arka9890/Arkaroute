//! Incremental Server-Sent Events parser and formatter (WHATWG EventSource wire format).

/// A single decoded SSE event.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SseEvent {
    pub event: Option<String>,
    pub data: String,
}

/// Incremental SSE parser. Chunks from a network stream can split a line
/// anywhere (including mid-`data:` payload); `push` reassembles across
/// arbitrary byte boundaries.
#[derive(Debug, Default)]
pub struct SseParser {
    /// Bytes of the current line not yet terminated by `\n`.
    line: Vec<u8>,
    /// `event:` field of the event being accumulated.
    event: Option<String>,
    /// `data:` field lines of the event being accumulated (joined with `\n` on dispatch).
    data: Vec<String>,
}

impl SseParser {
    pub fn new() -> Self {
        Self::default()
    }

    /// Feed the next chunk of the stream, returning every event completed by it.
    pub fn push(&mut self, bytes: &[u8]) -> Vec<SseEvent> {
        let mut out = Vec::new();
        self.line.extend_from_slice(bytes);
        while let Some(nl) = self.line.iter().position(|&b| b == b'\n') {
            let mut line: Vec<u8> = self.line.drain(..=nl).collect();
            line.pop(); // the `\n` itself
            if line.last() == Some(&b'\r') {
                line.pop(); // strip one trailing `\r` (CRLF line ending)
            }
            if let Some(ev) = self.process_line(&line) {
                out.push(ev);
            }
        }
        out
    }

    /// End of input: process any unterminated final line, then flush the
    /// accumulated event even though no blank line followed.
    pub fn finish(&mut self) -> Vec<SseEvent> {
        let mut out = Vec::new();
        if !self.line.is_empty() {
            let mut line = std::mem::take(&mut self.line);
            if line.last() == Some(&b'\r') {
                line.pop();
            }
            if let Some(ev) = self.process_line(&line) {
                out.push(ev);
            }
        }
        if let Some(ev) = self.dispatch() {
            out.push(ev);
        }
        out
    }

    /// Apply one complete line (already stripped of `\n` / trailing `\r`).
    /// Returns an event when a blank line dispatches the accumulated event.
    fn process_line(&mut self, line: &[u8]) -> Option<SseEvent> {
        if line.is_empty() {
            return self.dispatch();
        }
        if line[0] == b':' {
            return None; // comment line
        }
        let (name, value) = match line.iter().position(|&b| b == b':') {
            Some(colon) => {
                let mut value = &line[colon + 1..];
                if value.first() == Some(&b' ') {
                    value = &value[1..]; // strip exactly one leading space
                }
                (&line[..colon], value)
            }
            None => (line, &line[line.len()..]), // field with empty value
        };
        if name == b"event" {
            self.event = Some(String::from_utf8_lossy(value).into_owned());
        } else if name == b"data" {
            self.data.push(String::from_utf8_lossy(value).into_owned());
        }
        // Other fields (id, retry, unknown) are ignored.
        None
    }

    /// Blank line (or `finish`): emit the accumulated event, if any field was seen.
    fn dispatch(&mut self) -> Option<SseEvent> {
        if self.event.is_none() && self.data.is_empty() {
            return None;
        }
        let data = std::mem::take(&mut self.data);
        Some(SseEvent {
            event: self.event.take(),
            data: data.join("\n"),
        })
    }
}

/// Wire form of an event: `event: x\ndata: y\n\n` (multi-line data emits one
/// `data:` line per line; the `event` line is omitted when `None`).
pub fn format_event(ev: &SseEvent) -> String {
    let mut out = String::new();
    if let Some(name) = &ev.event {
        out.push_str("event: ");
        out.push_str(name);
        out.push('\n');
    }
    out.push_str(&data_only(&ev.data));
    out
}

/// Wire form of a data-only event: `data: y\n\n` (one `data:` line per line).
pub fn data_only(data: &str) -> String {
    let mut out = String::new();
    for line in data.split('\n') {
        out.push_str("data: ");
        out.push_str(line);
        out.push('\n');
    }
    out.push('\n');
    out
}

/// Terminal sentinel event: `data: [DONE]\n\n`.
pub const DONE: &str = "data: [DONE]\n\n";
