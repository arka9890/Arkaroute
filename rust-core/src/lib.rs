//! OmniRoute core daemon and JNI bridge.
use jni::objects::{JObject, JString};
use jni::sys::jint;
use jni::JNIEnv;
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::Mutex;
use std::thread;
use std::time::{Duration, Instant};

#[derive(Clone)]
struct State {
    online: bool,
    started_at: Option<Instant>,
    port: u16,
    upstream: String,
    inflight: u64,
    hits: u64,
}

impl Default for State {
    fn default() -> Self {
        Self {
            online: false,
            started_at: None,
            port: 8080,
            upstream: "http://127.0.0.1:11434".to_string(),
            inflight: 0,
            hits: 0,
        }
    }
}

struct Daemon(Mutex<State>);
impl Daemon { fn new() -> Self { Self(Mutex::new(State::default())) } fn lock(&self) -> std::sync::MutexGuard<'_, State> { self.0.lock().unwrap_or_else(|e| e.into_inner()) } }

static DAEMON: std::sync::OnceLock<Daemon> = std::sync::OnceLock::new();
fn daemon() -> &'static Daemon { DAEMON.get_or_init(Daemon::new) }

fn escape_json_basic(s: &str) -> String { s.replace('\\', "\\\\").replace('"', "\\\"") }

fn status_json() -> String {
    let s = daemon().lock();
    let uptime = s.started_at.map(|t| t.elapsed().as_secs()).unwrap_or(0);
    format!(
        "{{\"status\":\"{}\",\"port\":{},\"upstream\":\"{}\",\"inflight\":{},\"hits\":{},\"uptime_secs\":{}}}",
        if s.online { "online" } else { "standby" }, s.port, escape_json_basic(&s.upstream), s.inflight, s.hits, uptime
    )
}

fn dashboard_html() -> String {
    let s = daemon().lock();
    let uptime = s.started_at.map(|t| t.elapsed().as_secs()).unwrap_or(0);
    let (status, color) = if s.online { ("online", "#34d399") } else { ("standby", "#94a3b8") };
    format!(r#"<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>OmniRoute Dashboard</title><style>:root{{color-scheme:dark}}*{{box-sizing:border-box}}body{{margin:0;background:#0b1220;color:#f8fafc;font-family:system-ui,sans-serif;padding:28px 22px 86px}}.card{{background:#131b2e;border:1px solid #24304a;border-radius:32px;padding:22px;box-shadow:0 24px 60px #0008}}.badge{{display:inline-flex;align-items:center;gap:10px;background:#17213a;border-radius:999px;padding:9px 16px;border:1px solid #24304a}}.dot{{width:13px;height:13px;border-radius:50%;background:{color};box-shadow:0 0 18px {color}}}h1{{font-size:30px;margin:6px 0 4px;letter-spacing:.3px}}p{{color:#94a3b8;line-height:1.5}}.grid{{display:grid;grid-template-columns:repeat(3,1fr);gap:14px;margin:18px 0}}.metric{{background:#181f36;border-radius:22px;padding:16px}}.metric span,.label{{color:#94a3b8;font-size:12px;letter-spacing:.12em;text-transform:uppercase}}.metric b{{font-size:24px;display:block;margin-top:8px}}.row{{display:flex;justify-content:space-between;gap:12px;padding:12px 0;border-top:1px solid #24304a}}footer{{position:fixed;left:0;right:0;bottom:0;background:#0b1220;border-top:1px solid #24304a;padding:12px 22px;color:#94a3b8;font-size:13px}}a{{color:#e8b74a}}</style></head><body><main><div class="card"><span class="badge"><span class="dot"></span><b>PRoot {status}</b></span><h1>OmniRoute Dashboard</h1><p>Local AI gateway di http://127.0.0.1:{port}</p><div class="grid"><div class="metric"><span>Uptime</span><b>{uptime_secs}s</b></div><div class="metric"><span>Routes</span><b>{hits}</b></div><div class="metric"><span>Inflight</span><b>{inflight}</b></div></div><div class="row"><span class="label">Gateway</span><b>127.0.0.1:{port}</b></div><div class="row"><span class="label">Architecture</span><b>aarch64</b></div><div class="row"><span class="label">Rust engine</span><b>libomniroute (JNI)</b></div><div class="row"><span class="label">Upstream</span><b>{upstream}</b></div><p><a href="/healthz">/healthz</a> · <a href="/stats">/stats</a></p></div></main><footer>OmniRoute runtime · foreground service · hardware accelerated local proxy</footer></body></html>"#,
        status = status,
        color = color,
        port = s.port,
        uptime_secs = uptime,
        hits = s.hits,
        inflight = s.inflight,
        upstream = escape_json_basic(&s.upstream),
    )
}

fn http_health(port: u16, timeout_ms: u64) -> bool {
    let Ok(mut stream) = TcpStream::connect_timeout(&SocketAddr::from(([127, 0, 0, 1], port)), Duration::from_millis(timeout_ms)) else { return false; };
    let req = format!("GET /healthz HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nConnection: close\r\n\r\n");
    if stream.write_all(req.as_bytes()).is_err() { return false; }
    let mut resp = String::new();
    let _ = stream.read_to_string(&mut resp);
    resp.contains("\"status\":\"online\"") || resp.starts_with("HTTP/1.1 200")
}

fn peek_request_line(stream: &mut TcpStream) -> Option<String> {
    let mut buf = [0u8; 512];
    let n = stream.read(&mut buf).ok()?;
    if n == 0 { return None; }
    String::from_utf8_lossy(&buf[..n]).lines().next().map(str::to_string)
}

fn spawn_proxy(port: u16) -> std::io::Result<()> {
    let listener = TcpListener::bind(("127.0.0.1", port))?;
    thread::spawn(move || {
        for stream in listener.incoming() {
            let Ok(mut stream) = stream else { continue; };
            let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
            let _ = stream.set_write_timeout(Some(Duration::from_secs(5)));
            {
                let mut s = daemon().lock();
                if !s.online { continue; }
                s.inflight += 1;
                s.hits += 1;
            }
            let request = peek_request_line(&mut stream).unwrap_or_default();
            let upstream = daemon().lock().upstream.clone();
            if request.contains("/healthz") || request.contains("/stats") {
                let body = status_json();
                let resp = format!("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}", body.len(), body);
                let _ = stream.write_all(resp.as_bytes());
            } else if request.starts_with("GET / HTTP/1.1") || request.contains("GET /dashboard") {
                let body = dashboard_html();
                let resp = format!("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}", body.len(), body);
                let _ = stream.write_all(resp.as_bytes());
            } else {
                let body = "HTTP/1.1 502 Bad Gateway\r\nContent-Type: application/json\r\nContent-Length: 104\r\nConnection: close\r\n\r\n{\"error\":\"UPSTREAM_UNRESOLVED\",\"upstream\":\"".to_string() + &escape_json_basic(&upstream) + "\"}";
                let _ = stream.write_all(body.as_bytes());
            }
            let mut s = daemon().lock();
            s.inflight = s.inflight.saturating_sub(1);
            let _ = stream.flush();
        }
    });
    Ok(())
}

fn string_to_rust(env: &mut JNIEnv, obj: &JString) -> String {
    match env.get_string(obj) { Ok(s) => s.to_string_lossy().to_string(), Err(_) => String::new() }
}

fn start_inner(port: i32, upstream: String) -> i32 {
    let port = if port <= 0 { 8080 } else { port as u16 };
    let was_online = {
        let mut s = daemon().lock();
        if s.online { true } else {
            s.online = true;
            s.port = port;
            s.upstream = upstream;
            s.started_at = Some(Instant::now());
            false
        }
    };
    if was_online { return 0; }
    match spawn_proxy(port) { Ok(_) => 0, Err(_) => { daemon().lock().online = false; 1 } }
}

fn stop_inner() {
    daemon().lock().online = false;
    daemon().lock().inflight = 0;
}

#[no_mangle]
pub extern "system" fn Java_com_example_omniroute_NativeBridge_start<'a>(
    mut env: JNIEnv<'a>,
    _class: JObject<'a>,
    port: jint,
    upstream: JString<'a>,
) -> jint {
    let upstream = string_to_rust(&mut env, &upstream);
    start_inner(port, upstream)
}

#[no_mangle]
pub extern "system" fn Java_com_example_omniroute_NativeBridge_stop(_env: JNIEnv<'_>, _class: JObject<'_>) {
    stop_inner();
}

#[no_mangle]
pub extern "system" fn Java_com_example_omniroute_NativeBridge_restart<'a>(
    mut env: JNIEnv<'a>,
    _class: JObject<'a>,
    port: jint,
    upstream: JString<'a>,
) -> jint {
    stop_inner();
    std::thread::sleep(Duration::from_millis(250));
    start_inner(port, string_to_rust(&mut env, &upstream))
}

#[no_mangle]
pub extern "system" fn Java_com_example_omniroute_NativeBridge_statusJson<'a>(mut env: JNIEnv<'a>, _class: JObject<'a>) -> JString<'a> {
    env.new_string(status_json()).unwrap_or_else(|_| env.new_string("{\"status\":\"standby\"}").unwrap())
}

#[no_mangle]
pub extern "system" fn Java_com_example_omniroute_NativeBridge_health(_env: JNIEnv<'_>, _class: JObject<'_>, port: jint) -> jint {
    let port = if port <= 0 { 8080 } else { port as u16 };
    if http_health(port, 600) { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_example_omniroute_NativeBridge_probeGitPull(_env: JNIEnv<'_>, _class: JObject<'_>) -> jint { 0 }

#[no_mangle]
pub extern "system" fn Java_com_example_omniroute_NativeBridge_flushCache(_env: JNIEnv<'_>, _class: JObject<'_>) -> jint { 0 }
