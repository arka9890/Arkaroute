//! Command-line argument parsing for the `omniroute` binary.

use std::path::PathBuf;

use crate::config::GatewayConfig;

/// Parsed CLI options.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CliOptions {
    pub bind: String,
    pub port: u16,
    pub config: PathBuf,
}

/// Parse `--bind`, `--port`, `--config` flags from `args`.
pub fn parse_args(args: &[String]) -> Result<CliOptions, String> {
    let defaults = GatewayConfig::default();
    let mut bind = defaults.bind;
    let mut port = defaults.port;
    let mut config = PathBuf::from("./omniroute.json");

    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--bind" => {
                i += 1;
                if i >= args.len() {
                    return Err("--bind requires a value".to_string());
                }
                bind = args[i].clone();
            }
            "--port" => {
                i += 1;
                if i >= args.len() {
                    return Err("--port requires a value".to_string());
                }
                port = args[i]
                    .parse::<u16>()
                    .map_err(|_| format!("invalid port: {}", args[i]))?;
            }
            "--config" => {
                i += 1;
                if i >= args.len() {
                    return Err("--config requires a value".to_string());
                }
                config = PathBuf::from(&args[i]);
            }
            other => {
                return Err(format!("unknown argument: {other}"));
            }
        }
        i += 1;
    }

    Ok(CliOptions { bind, port, config })
}