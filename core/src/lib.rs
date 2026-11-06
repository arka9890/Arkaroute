//! OmniRoute core gateway: library crate embedded in the Android app.

pub mod auth;
pub mod cli;
pub mod config;
pub mod dashboard;
pub mod error;
pub mod protocol;
pub mod providers;
pub mod routing;
pub mod server;
pub mod sse;
pub mod state;
pub mod translate;

pub use cli::{parse_args, CliOptions};

pub const VERSION: &str = env!("CARGO_PKG_VERSION");
