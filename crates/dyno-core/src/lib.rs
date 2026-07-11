pub mod api;
pub mod app;
pub mod audit;
pub mod bme280;
pub mod calibration;
pub mod can;
pub mod config;
pub mod correction;
pub mod deps;
pub mod detect;
pub mod esp32_config;
pub mod esp32_json;
pub mod flash;
pub mod fusion;
pub mod health;
pub mod paths;
pub mod physics;
pub mod replay;
pub mod run_control;
pub mod serial;
pub mod serial_gate;
pub mod serial_link;
pub mod sources;
pub mod state;
pub mod storage;
pub mod ws;

/// Acquire the process-wide lock serializing tests that read or mutate
/// process-global environment variables (`DYNO_*`). Shared across modules so
/// env-mutating tests in different files cannot race each other.
#[cfg(test)]
pub(crate) fn test_env_lock() -> std::sync::MutexGuard<'static, ()> {
    static ENV_LOCK: std::sync::OnceLock<std::sync::Mutex<()>> = std::sync::OnceLock::new();
    ENV_LOCK
        .get_or_init(|| std::sync::Mutex::new(()))
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}
