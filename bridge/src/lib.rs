//! Thin JNI shim: adapts the `NativeGateway` Kotlin object to
//! the host-testable functions in [`api`]. No business logic lives here;
//! every entry point catches panics so a Rust panic can never unwind
//! into the JVM.

mod api;

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring};
use jni::JNIEnv;

/// Build the `{"ok":true}` payload for a successful start.
fn ok_json() -> String {
    serde_json::json!({"ok": true}).to_string()
}

/// Build the `{"ok":false,"error":...}` payload.
fn err_json(msg: &str) -> String {
    serde_json::json!({"ok": false, "error": msg}).to_string()
}

fn to_jstring(env: &mut JNIEnv<'_>, json: String) -> jstring {
    match env.new_string(json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_omniroute_app_bridge_NativeGateway_startServer<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    config_json: JString<'local>,
) -> jstring {
    let config: String = match env.get_string(&config_json) {
        Ok(java_str) => java_str.into(),
        Err(_) => return to_jstring(&mut env, err_json("internal")),
    };
    let result = catch_unwind(AssertUnwindSafe(|| api::start(&config)));
    let json = match result {
        Ok(Ok(())) => ok_json(),
        Ok(Err(e)) => err_json(&e),
        Err(_) => err_json("internal"),
    };
    to_jstring(&mut env, json)
}

#[no_mangle]
pub extern "system" fn Java_com_omniroute_app_bridge_NativeGateway_stopServer<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(api::stop));
    let json = match result {
        Ok(Ok(was)) => serde_json::json!({"ok": true, "was_running": was}).to_string(),
        Ok(Err(e)) => err_json(&e),
        Err(_) => err_json("internal"),
    };
    to_jstring(&mut env, json)
}

#[no_mangle]
pub extern "system" fn Java_com_omniroute_app_bridge_NativeGateway_isRunning<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jboolean {
    let result = catch_unwind(AssertUnwindSafe(api::is_running));
    match result {
        Ok(true) => 1,
        _ => 0,
    }
}
