//! In-process server lifecycle: start/stop the gateway server on a
//! dedicated thread so Android (via a later JNI bridge) can embed it.

use std::net::SocketAddr;
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::Duration;

use tokio::sync::Notify;

use crate::config::GatewayConfig;
use crate::state::{AppState, Stats};

/// Errors from starting the embedded server.
#[derive(Debug, thiserror::Error)]
pub enum LifecycleError {
    /// Binding or resolving the local address failed.
    #[error("bind failed: {0}")]
    Bind(String),
}

/// Handle to a running embedded server. The server runs on a dedicated
/// thread; [`ServerHandle::shutdown`] blocks until that thread exits so
/// the port is immediately rebindable.
pub struct ServerHandle {
    notify: Arc<Notify>,
    thread: Option<JoinHandle<()>>,
    local_addr: SocketAddr,
}

impl ServerHandle {
    /// Bind and serve the gateway before returning. Returns
    /// [`LifecycleError::Bind`] if the address cannot be parsed or bound.
    pub fn start(cfg: GatewayConfig) -> Result<Self, LifecycleError> {
        let addr: SocketAddr = format!("{}:{}", cfg.bind, cfg.port)
            .parse::<SocketAddr>()
            .map_err(|e: std::net::AddrParseError| LifecycleError::Bind(e.to_string()))?;

        let (tx, rx) = std::sync::mpsc::channel::<Result<SocketAddr, String>>();
        let notify = Arc::new(Notify::new());
        let notify_thread = Arc::clone(&notify);

        let thread = std::thread::spawn(move || {
            let rt = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .unwrap();
            rt.block_on(async move {
                let listener = match tokio::net::TcpListener::bind(addr).await {
                    Ok(l) => l,
                    Err(e) => {
                        let _ = tx.send(Err(e.to_string()));
                        return;
                    }
                };
                let bound = match listener.local_addr() {
                    Ok(a) => a,
                    Err(e) => {
                        let _ = tx.send(Err(e.to_string()));
                        return;
                    }
                };
                let _ = tx.send(Ok(bound));

                let router = crate::server::build_router(make_state(&cfg));
                let _ = axum::serve(
                    listener,
                    router.into_make_service_with_connect_info::<SocketAddr>(),
                )
                .with_graceful_shutdown(async move {
                    notify_thread.notified().await;
                })
                .await;
            });
        });

        let bound = rx
            .recv()
            .map_err(|_| LifecycleError::Bind("server thread died".into()))?
            .map_err(LifecycleError::Bind)?;

        Ok(ServerHandle {
            notify,
            thread: Some(thread),
            local_addr: bound,
        })
    }

    /// The address the server is bound to.
    pub fn local_addr(&self) -> SocketAddr {
        self.local_addr
    }

    /// Signal graceful shutdown and block until the listener thread exits.
    /// On return the port is guaranteed free for rebinding.
    pub fn shutdown(mut self) {
        self.notify.notify_one();
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

/// Build `AppState` from `cfg` exactly as `main.rs` does.
fn make_state(cfg: &GatewayConfig) -> AppState {
    let client = reqwest::Client::builder()
        .timeout(Duration::from_millis(cfg.request_timeout_ms))
        .build()
        .unwrap_or_else(|_| reqwest::Client::new());

    AppState {
        config: Arc::new(std::sync::RwLock::new(cfg.clone())),
        stats: Arc::new(Stats::new()),
        client,
    }
}
