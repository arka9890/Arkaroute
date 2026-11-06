use std::net::SocketAddr;
use std::process::ExitCode;

use omniroute_core::cli::parse_args;
use omniroute_core::config::GatewayConfig;
use omniroute_core::server::build_router;
use omniroute_core::state::AppState;

#[tokio::main]
async fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let opts = match parse_args(&args) {
        Ok(o) => o,
        Err(e) => {
            eprintln!("error: {e}");
            return ExitCode::from(2);
        }
    };

    let mut cfg = if opts.config.exists() {
        match GatewayConfig::load(&opts.config) {
            Ok(c) => c,
            Err(e) => {
                eprintln!("error: {e}");
                return ExitCode::from(2);
            }
        }
    } else {
        let c = GatewayConfig::default();
        let _ = c.save(&opts.config);
        c
    };

    // CLI overrides for bind/port.
    cfg.bind = opts.bind.clone();
    cfg.port = opts.port;

    if let Err(e) = cfg.validate() {
        eprintln!("error: {e}");
        return ExitCode::from(2);
    }

    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::from_default_env())
        .init();

    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_millis(cfg.request_timeout_ms))
        .build()
        .unwrap_or_else(|_| reqwest::Client::new());

    let state = AppState {
        config: std::sync::Arc::new(std::sync::RwLock::new(cfg.clone())),
        stats: std::sync::Arc::new(omniroute_core::state::Stats::new()),
        client,
    };

    let router = build_router(state);
    let bind = cfg.bind.clone();
    let port = cfg.port;

    let addr: SocketAddr = match format!("{bind}:{port}").parse() {
        Ok(a) => a,
        Err(e) => {
            eprintln!("error: {e}");
            return ExitCode::from(2);
        }
    };
    let listener = match tokio::net::TcpListener::bind(addr).await {
        Ok(l) => l,
        Err(e) => {
            eprintln!("error: {e}");
            return ExitCode::from(2);
        }
    };

    tracing::info!("omniroute listening on http://{bind}:{port}");
    println!("omniroute listening on http://{bind}:{port}");

    if let Err(e) = axum::serve(listener, router.into_make_service_with_connect_info::<SocketAddr>()).await {
        eprintln!("error: {e}");
        return ExitCode::from(1);
    }
    ExitCode::SUCCESS
}
