//! Host-testable JNI backing logic. The JNI layer in `lib.rs` only
//! adapts these functions to/from Java strings; all lifecycle logic
//! is here.

use omniroute_core::config::GatewayConfig;
use omniroute_core::lifecycle::{LifecycleError, ServerHandle};

/// Process-wide singleton holding the running gateway, if any.
static SERVER: std::sync::Mutex<Option<ServerHandle>> = std::sync::Mutex::new(None);

fn lock_server() -> std::sync::MutexGuard<'static, Option<ServerHandle>> {
    SERVER.lock().unwrap_or_else(|p| p.into_inner())
}

/// Parse, validate, and start the embedded gateway server on a dedicated
/// thread. Returns `Err("already_running")` if a server is already
/// running, `Err("invalid_config: ...")` for bad JSON or a failed
/// [`GatewayConfig::validate`], and `Err("Bind: ...")` for bind
/// failures (preserving the OS error text).
pub fn start(config_json: &str) -> Result<(), String> {
    let cfg: GatewayConfig = serde_json::from_str(config_json)
        .map_err(|e| format!("invalid_config: {e}"))?;
    cfg.validate()
        .map_err(|e| format!("invalid_config: {e}"))?;
    let mut guard = lock_server();
    if guard.is_some() {
        return Err("already_running".to_string());
    }
    let handle = ServerHandle::start(cfg).map_err(|e| match e {
        LifecycleError::Bind(msg) => format!("Bind: {msg}"),
    })?;
    *guard = Some(handle);
    Ok(())
}

/// Stop the embedded server, if running. Returns `Ok(true)` when a
/// server was running (and is now stopped), `Ok(false)` otherwise.
pub fn stop() -> Result<bool, String> {
    let handle = {
        let mut guard = lock_server();
        guard.take()
    };
    match handle {
        Some(h) => {
            h.shutdown();
            Ok(true)
        }
        None => Ok(false),
    }
}

/// Whether the embedded server is currently running.
pub fn is_running() -> bool {
    lock_server().is_some()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};

    static TEST_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

    /// Drop guard that guarantees the singleton is stopped when the test
    /// ends, even on assertion failure, so the next test gets a clean slate.
    struct Cleanup;
    impl Drop for Cleanup {
        fn drop(&mut self) {
            let _ = stop();
        }
    }

    #[test]
    fn start_twice_returns_already_running() {
        let _g = TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        let _c = Cleanup;
        let cfg = r#"{"port":41021}"#;
        start(cfg).expect("first start should succeed");
        assert!(is_running());
        let err = start(cfg).expect_err("second start must fail");
        assert!(
            err.contains("already_running"),
            "expected already_running, got: {err}"
        );
    }

    #[test]
    fn stop_reports_was_running() {
        let _g = TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        let _c = Cleanup;
        let cfg = r#"{"port":41024}"#;
        start(cfg).expect("start should succeed");
        assert!(is_running());
        let was = stop().expect("first stop");
        assert!(was, "first stop should report was_running=true");
        // JSON shape the JNI layer emits for this case.
        let json = serde_json::json!({"ok": true, "was_running": was}).to_string();
        assert!(json.contains(r#""was_running":true"#), "json: {json}");
        let was2 = stop().expect("second stop");
        assert!(!was2, "second stop should report was_running=false");
        let json2 = serde_json::json!({"ok": true, "was_running": was2}).to_string();
        assert!(json2.contains(r#""was_running":false"#), "json: {json2}");
    }

    #[test]
    fn invalid_json_returns_error() {
        let _g = TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        let _c = Cleanup;
        let err = start("{not json").expect_err("invalid json must fail");
        assert!(
            err.contains("invalid_config"),
            "expected invalid_config, got: {err}"
        );
    }

    #[test]
    fn lan_without_key_rejected() {
        let _g = TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        let _c = Cleanup;
        let err = start(r#"{"lan_enabled":true}"#).expect_err("lan without key must fail");
        assert!(err.contains("LAN"), "expected LAN, got: {err}");
    }

    #[test]
    fn start_on_busy_port_returns_error() {
        let _g = TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        let _c = Cleanup;
        let listener =
            std::net::TcpListener::bind("127.0.0.1:41022").expect("bind busy-port listener");
        let err = start(r#"{"port":41022}"#).expect_err("start on busy port must fail");
        assert!(
            err.contains("in use") || err.contains("Bind"),
            "expected 'in use' or 'Bind', got: {err}"
        );
        drop(listener);
    }

    #[test]
    fn health_after_start_is_ok() {
        let _g = TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        let _c = Cleanup;
        start(r#"{"port":41023}"#).expect("start should succeed");
        assert!(is_running());
        // Minimal HTTP GET over a raw socket; retry while the server warms up.
        let mut body = String::new();
        let mut last_err = None;
        for _ in 0..50 {
            match std::net::TcpStream::connect("127.0.0.1:41023") {
                Ok(mut stream) => {
                    let _ = stream.set_read_timeout(Some(std::time::Duration::from_millis(200)));
                    if stream
                        .write_all(b"GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                        .is_ok()
                    {
                        let mut buf = String::new();
                        if stream.read_to_string(&mut buf).is_ok() {
                            body = buf;
                            if body.contains("\"status\"") {
                                break;
                            }
                            last_err = Some(format!("unexpected body: {body}"));
                        }
                    }
                    std::thread::sleep(std::time::Duration::from_millis(50));
                }
                Err(e) => {
                    last_err = Some(e.to_string());
                    std::thread::sleep(std::time::Duration::from_millis(50));
                }
            }
        }
        assert!(
            body.contains("\"status\""),
            "health body should contain status, last_err={last_err:?}, body={body}"
        );
    }
}
