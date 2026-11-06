//! Shared gateway state: live counters, per-provider stats, and the log buffer.

use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Mutex, RwLock};
use std::time::Instant;

use serde::Serialize;
use time::format_description::well_known::Rfc3339;
use time::OffsetDateTime;

use crate::config::GatewayConfig;

/// Maximum number of log entries kept in the ring buffer.
const LOG_CAP: usize = 100;

/// Severity of a [`LogEntry`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum LogLevel {
    Info,
    Warn,
    Error,
}

/// One entry of the in-memory log buffer.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct LogEntry {
    /// RFC3339 UTC timestamp of when the entry was recorded.
    pub ts: String,
    pub level: LogLevel,
    pub message: String,
}

/// Live per-provider stats, updated by record operations.
#[derive(Debug, Default)]
struct ProviderStats {
    /// Set to `true` by [`Stats::record_success`], `false` by
    /// [`Stats::record_failure`]; no entry means "no evidence yet" (treated
    /// as healthy by the snapshot).
    healthy: bool,
    requests: u64,
    tokens_in: u64,
    tokens_out: u64,
    last_latency_ms: u64,
    last_error: Option<String>,
}

impl ProviderStats {
    fn record_success(&mut self, tokens_in: u64, tokens_out: u64, latency_ms: u64) {
        self.healthy = true;
        self.requests += 1;
        self.tokens_in += tokens_in;
        self.tokens_out += tokens_out;
        self.last_latency_ms = latency_ms;
    }

    fn record_failure(&mut self, error: &str) {
        self.healthy = false;
        self.requests += 1;
        self.last_error = Some(error.to_string());
    }
}

/// Point-in-time view of the stats, serialized as the `/health` body.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct StatsSnapshot {
    pub status: String,
    pub version: String,
    pub uptime_seconds: u64,
    pub lan_enabled: bool,
    pub requests_total: u64,
    pub requests_failed: u64,
    pub tokens_in: u64,
    pub tokens_out: u64,
    pub providers: Vec<ProviderSnapshot>,
    pub combos: Vec<String>,
    pub logs: Vec<LogEntry>,
}

/// Per-provider view inside a [`StatsSnapshot`].
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct ProviderSnapshot {
    pub id: String,
    pub healthy: bool,
    pub requests: u64,
    pub tokens_in: u64,
    pub tokens_out: u64,
    pub last_latency_ms: u64,
    pub last_error: Option<String>,
}

/// Live counters and the bounded log buffer shared across handlers.
pub struct Stats {
    started: Instant,
    requests_total: std::sync::atomic::AtomicU64,
    requests_failed: std::sync::atomic::AtomicU64,
    tokens_in: std::sync::atomic::AtomicU64,
    tokens_out: std::sync::atomic::AtomicU64,
    providers: Mutex<HashMap<String, ProviderStats>>,
    logs: Mutex<VecDeque<LogEntry>>,
}

impl Default for Stats {
    fn default() -> Self {
        Self::new()
    }
}

impl Stats {
    /// Fresh stats with uptime counting from now and empty provider/log state.
    pub fn new() -> Self {
        Self {
            started: Instant::now(),
            requests_total: Default::default(),
            requests_failed: Default::default(),
            tokens_in: Default::default(),
            tokens_out: Default::default(),
            providers: Mutex::new(HashMap::new()),
            logs: Mutex::new(VecDeque::new()),
        }
    }

    /// Record that a request arrived at the gateway.
    pub fn record_request(&self) {
        use std::sync::atomic::Ordering::Relaxed;
        self.requests_total.fetch_add(1, Relaxed);
    }

    /// Record a failed upstream attempt against `provider`.
    pub fn record_failure(&self, provider: &str, error: &str) {
        use std::sync::atomic::Ordering::Relaxed;
        self.requests_failed.fetch_add(1, Relaxed);
        let mut providers = lock_recover(&self.providers);
        providers
            .entry(provider.to_string())
            .or_default()
            .record_failure(error);
    }

    /// Record a successful upstream response from `provider`.
    pub fn record_success(&self, provider: &str, tokens_in: u64, tokens_out: u64, latency_ms: u64) {
        use std::sync::atomic::Ordering::Relaxed;
        self.tokens_in.fetch_add(tokens_in, Relaxed);
        self.tokens_out.fetch_add(tokens_out, Relaxed);
        let mut providers = lock_recover(&self.providers);
        providers
            .entry(provider.to_string())
            .or_default()
            .record_success(tokens_in, tokens_out, latency_ms);
    }

    /// Append an entry to the bounded log buffer (newest kept, cap 100).
    pub fn log(&self, level: LogLevel, message: impl Into<String>) {
        let entry = LogEntry {
            ts: OffsetDateTime::now_utc().format(&Rfc3339).unwrap_or_default(),
            level,
            message: message.into(),
        };
        let mut logs = lock_recover(&self.logs);
        if logs.len() >= LOG_CAP {
            logs.pop_front();
        }
        logs.push_back(entry);
    }

    /// A point-in-time snapshot for reporting.
    pub fn snapshot(&self, cfg: &GatewayConfig) -> StatsSnapshot {
        use std::sync::atomic::Ordering::Relaxed;
        let providers = lock_recover(&self.providers);
        let logs = lock_recover(&self.logs);

        // Configured providers first, in config order; any provider that only
        // exists in the stats map (traffic for an unconfigured id) follows,
        // sorted for determinism.
        let mut seen: Vec<String> = Vec::with_capacity(cfg.providers.len());
        let mut provider_snapshots: Vec<ProviderSnapshot> = Vec::with_capacity(cfg.providers.len());
        for provider in &cfg.providers {
            if seen.contains(&provider.id) {
                continue;
            }
            seen.push(provider.id.clone());
            provider_snapshots.push(provider_snapshot(&provider.id, providers.get(&provider.id)));
        }
        let mut extra: Vec<String> = providers
            .keys()
            .filter(|id| !seen.contains(*id))
            .map(|id| id.clone())
            .collect();
        extra.sort();
        for id in extra {
            provider_snapshots.push(provider_snapshot(&id, providers.get(&id)));
        }

        StatsSnapshot {
            status: "ok".to_string(),
            version: crate::VERSION.to_string(),
            uptime_seconds: self.started.elapsed().as_secs(),
            lan_enabled: cfg.lan_enabled,
            requests_total: self.requests_total.load(Relaxed),
            requests_failed: self.requests_failed.load(Relaxed),
            tokens_in: self.tokens_in.load(Relaxed),
            tokens_out: self.tokens_out.load(Relaxed),
            providers: provider_snapshots,
            combos: cfg.combos.iter().map(|c| c.name.clone()).collect(),
            logs: logs.iter().cloned().collect(),
        }
    }
}

/// Lock a stats mutex, recovering the guard if a previous holder panicked
/// (the guarded data never mutates via a panicking path, so it stays sound).
fn lock_recover<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    match m.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    }
}

fn provider_snapshot(id: &str, stats: Option<&ProviderStats>) -> ProviderSnapshot {
    ProviderSnapshot {
        id: id.to_string(),
        healthy: stats.map_or(true, |s| s.healthy),
        requests: stats.map_or(0, |s| s.requests),
        tokens_in: stats.map_or(0, |s| s.tokens_in),
        tokens_out: stats.map_or(0, |s| s.tokens_out),
        last_latency_ms: stats.map_or(0, |s| s.last_latency_ms),
        last_error: stats.and_then(|s| s.last_error.clone()),
    }
}

/// State shared with every handler.
#[derive(Clone)]
pub struct AppState {
    pub config: Arc<RwLock<GatewayConfig>>,
    pub stats: Arc<Stats>,
    pub client: reqwest::Client,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn log_buffer_keeps_newest_100() {
        let stats = Stats::new();
        for i in 0..105 {
            stats.log(LogLevel::Info, format!("entry-{i}"));
        }
        let logs = stats.snapshot(&GatewayConfig::default()).logs;
        assert_eq!(logs.len(), 100);
        // entries 0..=4 dropped, entry 5 is the oldest kept
        assert_eq!(logs[0].message, "entry-5");
        assert_eq!(logs[99].message, "entry-104");
    }
}
