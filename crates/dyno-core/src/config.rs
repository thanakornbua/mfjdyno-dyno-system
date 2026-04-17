//! Runtime configuration loaded from environment variables with sane defaults.
//!
//! All settings can be overridden at startup by exporting the corresponding
//! `DYNO_*` variable before launching `dynod`. No config file is required.

use std::env;
use std::fmt;
use std::str::FromStr;

use crate::correction::CorrectionMode;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SourceMode {
    Live,
    Replay,
}

impl Default for SourceMode {
    fn default() -> Self {
        Self::Live
    }
}

impl fmt::Display for SourceMode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Live => f.write_str("live"),
            Self::Replay => f.write_str("replay"),
        }
    }
}

impl FromStr for SourceMode {
    type Err = ParseSourceModeError;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match s.trim().to_ascii_lowercase().as_str() {
            "live" => Ok(Self::Live),
            "replay" | "sim" | "simulation" => Ok(Self::Replay),
            _ => Err(ParseSourceModeError),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ParseSourceModeError;

impl fmt::Display for ParseSourceModeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("invalid source mode")
    }
}

impl std::error::Error for ParseSourceModeError {}

/// Full runtime configuration for the dyno backend.
///
/// Construct with [`Config::from_env`]; do not build manually outside tests.
#[derive(Debug, Clone)]
pub struct Config {
    // ── Serial (ESP32 UART) ──────────────────────────────────────────────────
    /// Device path of the UART port connected to the ESP32.
    pub serial_port: String,
    /// Baud rate; must match the ESP32 firmware setting.
    pub serial_baud: u32,

    // ── WebSocket ────────────────────────────────────────────────────────────
    /// `host:port` the WebSocket server will bind to.
    pub ws_bind: String,
    /// `host:port` the HTTP history/query API will bind to.
    pub api_bind: String,

    // ── Storage ──────────────────────────────────────────────────────────────
    /// Path to the SQLite database file (created on first run).
    pub db_path: String,
    /// Desired ESP32 device-config file path on the Pi.
    pub esp32_config_path: String,
    /// Persisted last-known applied ESP32 config state file path.
    pub esp32_applied_config_path: String,
    /// Timeout for ESP32 command/response round-trips.
    pub esp32_command_timeout_ms: u64,
    /// Number of retries for ESP32 command/response operations.
    pub esp32_command_retries: u32,

    // ── BME280 ───────────────────────────────────────────────────────────────
    /// Whether to initialise and poll the BME280 ambient sensor over I2C.
    pub bme280_enabled: bool,
    /// Active telemetry source mode.
    pub source_mode: SourceMode,
    /// Ambient correction model to report alongside live telemetry.
    pub correction_mode: CorrectionMode,
    /// Roller diameter used for surface-speed conversion.
    pub roller_diameter_m: f32,
    /// Encoder pulses per full roller revolution.
    pub encoder_pulses_per_rev: f32,
    /// Effective roller inertia for the inertia-only power model.
    pub roller_inertia_kg_m2: f32,
    /// Measurement window used by `encoder_delta`, in milliseconds.
    pub sample_window_ms: u32,

    // ── Broadcast ────────────────────────────────────────────────────────────
    /// Target rate at which fused `LiveFrame`s are pushed to WebSocket clients.
    pub ui_broadcast_rate_hz: u32,

    // ── Run state thresholds (engine RPM) ────────────────────────────────────
    /// Engine RPM above which the state machine transitions Idle → Armed.
    pub arm_rpm: f32,
    /// Engine RPM above which the state machine transitions Armed → Recording.
    pub record_rpm: f32,
    /// Engine RPM below which the state machine transitions Recording → Stopping.
    pub stop_rpm: f32,
}

impl Config {
    /// Build a `Config` by reading `DYNO_*` environment variables.
    ///
    /// Missing variables fall back to hard-coded defaults that are safe for
    /// a standard Raspberry Pi 5 + ESP32 setup.
    pub fn from_env() -> Self {
        Self {
            serial_port:          env_str("DYNO_SERIAL_PORT",        "/dev/serial0"),
            serial_baud:          env_parse("DYNO_SERIAL_BAUD",      921_600u32),
            ws_bind:              env_str("DYNO_WS_BIND",            "0.0.0.0:9000"),
            api_bind:             env_str("DYNO_API_BIND",           "0.0.0.0:9001"),
            db_path:              env_str("DYNO_DB_PATH",            "dyno.db"),
            esp32_config_path:    env_str("DYNO_ESP32_CONFIG_PATH",  "esp32-device-config.json"),
            esp32_applied_config_path: env_str("DYNO_ESP32_APPLIED_CONFIG_PATH", "esp32-last-applied.json"),
            esp32_command_timeout_ms: env_parse("DYNO_ESP32_COMMAND_TIMEOUT_MS", 1_500u64),
            esp32_command_retries: env_parse("DYNO_ESP32_COMMAND_RETRIES", 3u32),
            bme280_enabled:       env_parse("DYNO_BME280_ENABLED",   true),
            source_mode:          env_parse("DYNO_SOURCE_MODE",      SourceMode::Live),
            correction_mode:      env_parse("DYNO_CORRECTION_MODE",  CorrectionMode::None),
            roller_diameter_m:    env_parse("DYNO_ROLLER_DIAMETER_M", 0.318f32),
            encoder_pulses_per_rev: env_parse("DYNO_ENCODER_PULSES_PER_REV", 60.0f32),
            roller_inertia_kg_m2: env_parse("DYNO_ROLLER_INERTIA_KG_M2", 3.5f32),
            sample_window_ms:     env_parse("DYNO_SAMPLE_WINDOW_MS", 100u32),
            ui_broadcast_rate_hz: env_parse("DYNO_UI_RATE_HZ",       20u32),
            arm_rpm:              env_parse("DYNO_ARM_RPM",          1500.0f32),
            record_rpm:           env_parse("DYNO_RECORD_RPM",       2000.0f32),
            stop_rpm:             env_parse("DYNO_STOP_RPM",         1000.0f32),
        }
    }
}

impl fmt::Display for Config {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        writeln!(f, "  serial_port          = {}", self.serial_port)?;
        writeln!(f, "  serial_baud          = {}", self.serial_baud)?;
        writeln!(f, "  ws_bind              = {}", self.ws_bind)?;
        writeln!(f, "  api_bind             = {}", self.api_bind)?;
        writeln!(f, "  db_path              = {}", self.db_path)?;
        writeln!(f, "  esp32_config_path    = {}", self.esp32_config_path)?;
        writeln!(f, "  esp32_applied_config_path = {}", self.esp32_applied_config_path)?;
        writeln!(f, "  esp32_command_timeout_ms = {}", self.esp32_command_timeout_ms)?;
        writeln!(f, "  esp32_command_retries = {}", self.esp32_command_retries)?;
        writeln!(f, "  bme280_enabled       = {}", self.bme280_enabled)?;
        writeln!(f, "  source_mode          = {}", self.source_mode)?;
        writeln!(f, "  correction_mode      = {}", self.correction_mode)?;
        writeln!(f, "  roller_diameter_m    = {}", self.roller_diameter_m)?;
        writeln!(f, "  encoder_pulses_per_rev = {}", self.encoder_pulses_per_rev)?;
        writeln!(f, "  roller_inertia_kg_m2 = {}", self.roller_inertia_kg_m2)?;
        writeln!(f, "  sample_window_ms     = {}", self.sample_window_ms)?;
        writeln!(f, "  ui_broadcast_rate_hz = {}", self.ui_broadcast_rate_hz)?;
        writeln!(f, "  arm_rpm              = {}", self.arm_rpm)?;
        writeln!(f, "  record_rpm           = {}", self.record_rpm)?;
        write!(f,   "  stop_rpm             = {}", self.stop_rpm)
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

fn env_str(key: &str, default: &str) -> String {
    env::var(key).unwrap_or_else(|_| default.to_owned())
}

fn env_parse<T>(key: &str, default: T) -> T
where
    T: FromStr + Copy,
{
    env::var(key)
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(default)
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Mutex, OnceLock};

    fn env_lock() -> &'static Mutex<()> {
        static ENV_LOCK: OnceLock<Mutex<()>> = OnceLock::new();
        ENV_LOCK.get_or_init(|| Mutex::new(()))
    }

    #[test]
    fn defaults_are_sane() {
        let _guard = env_lock().lock().expect("env lock");
        // Run with no DYNO_* vars set; just ensure it doesn't panic and the
        // defaults match the expected production values.
        env::remove_var("DYNO_SERIAL_PORT");
        env::remove_var("DYNO_SERIAL_BAUD");
        env::remove_var("DYNO_WS_BIND");
        env::remove_var("DYNO_API_BIND");
        env::remove_var("DYNO_DB_PATH");
        env::remove_var("DYNO_ESP32_CONFIG_PATH");
        env::remove_var("DYNO_ESP32_APPLIED_CONFIG_PATH");
        env::remove_var("DYNO_ESP32_COMMAND_TIMEOUT_MS");
        env::remove_var("DYNO_ESP32_COMMAND_RETRIES");
        env::remove_var("DYNO_BME280_ENABLED");
        env::remove_var("DYNO_SOURCE_MODE");
        env::remove_var("DYNO_CORRECTION_MODE");
        env::remove_var("DYNO_ROLLER_DIAMETER_M");
        env::remove_var("DYNO_ENCODER_PULSES_PER_REV");
        env::remove_var("DYNO_ROLLER_INERTIA_KG_M2");
        env::remove_var("DYNO_SAMPLE_WINDOW_MS");
        env::remove_var("DYNO_UI_RATE_HZ");
        env::remove_var("DYNO_ARM_RPM");
        env::remove_var("DYNO_RECORD_RPM");
        env::remove_var("DYNO_STOP_RPM");
        let cfg = Config::from_env();
        assert_eq!(cfg.serial_port, "/dev/serial0");
        assert_eq!(cfg.serial_baud, 921_600);
        assert_eq!(cfg.ws_bind, "0.0.0.0:9000");
        assert_eq!(cfg.api_bind, "0.0.0.0:9001");
        assert_eq!(cfg.db_path, "dyno.db");
        assert_eq!(cfg.esp32_config_path, "esp32-device-config.json");
        assert_eq!(cfg.esp32_applied_config_path, "esp32-last-applied.json");
        assert_eq!(cfg.esp32_command_timeout_ms, 1_500);
        assert_eq!(cfg.esp32_command_retries, 3);
        assert!(cfg.bme280_enabled);
        assert_eq!(cfg.source_mode, SourceMode::Live);
        assert_eq!(cfg.correction_mode, CorrectionMode::None);
        assert_eq!(cfg.roller_diameter_m, 0.318);
        assert_eq!(cfg.encoder_pulses_per_rev, 60.0);
        assert_eq!(cfg.roller_inertia_kg_m2, 3.5);
        assert_eq!(cfg.sample_window_ms, 100);
        assert_eq!(cfg.ui_broadcast_rate_hz, 20);
        assert_eq!(cfg.arm_rpm, 1500.0);
        assert_eq!(cfg.record_rpm, 2000.0);
        assert_eq!(cfg.stop_rpm, 1000.0);
    }

    #[test]
    fn env_override_is_applied() {
        let _guard = env_lock().lock().expect("env lock");
        // Safety: tests run in the same process; set then immediately unset.
        env::set_var("DYNO_SERIAL_BAUD", "115200");
        env::set_var("DYNO_API_BIND", "127.0.0.1:9101");
        env::set_var("DYNO_ESP32_CONFIG_PATH", "/tmp/esp32-config.json");
        env::set_var("DYNO_ESP32_APPLIED_CONFIG_PATH", "/tmp/esp32-state.json");
        env::set_var("DYNO_ESP32_COMMAND_TIMEOUT_MS", "2500");
        env::set_var("DYNO_ESP32_COMMAND_RETRIES", "5");
        env::set_var("DYNO_SOURCE_MODE", "replay");
        env::set_var("DYNO_CORRECTION_MODE", "sae_j1349");
        env::set_var("DYNO_ROLLER_DIAMETER_M", "0.400");
        env::set_var("DYNO_ENCODER_PULSES_PER_REV", "48");
        env::set_var("DYNO_ROLLER_INERTIA_KG_M2", "4.2");
        env::set_var("DYNO_SAMPLE_WINDOW_MS", "125");
        let cfg = Config::from_env();
        env::remove_var("DYNO_SERIAL_BAUD");
        env::remove_var("DYNO_API_BIND");
        env::remove_var("DYNO_ESP32_CONFIG_PATH");
        env::remove_var("DYNO_ESP32_APPLIED_CONFIG_PATH");
        env::remove_var("DYNO_ESP32_COMMAND_TIMEOUT_MS");
        env::remove_var("DYNO_ESP32_COMMAND_RETRIES");
        env::remove_var("DYNO_SOURCE_MODE");
        env::remove_var("DYNO_CORRECTION_MODE");
        env::remove_var("DYNO_ROLLER_DIAMETER_M");
        env::remove_var("DYNO_ENCODER_PULSES_PER_REV");
        env::remove_var("DYNO_ROLLER_INERTIA_KG_M2");
        env::remove_var("DYNO_SAMPLE_WINDOW_MS");
        assert_eq!(cfg.serial_baud, 115_200);
        assert_eq!(cfg.api_bind, "127.0.0.1:9101");
        assert_eq!(cfg.esp32_config_path, "/tmp/esp32-config.json");
        assert_eq!(cfg.esp32_applied_config_path, "/tmp/esp32-state.json");
        assert_eq!(cfg.esp32_command_timeout_ms, 2_500);
        assert_eq!(cfg.esp32_command_retries, 5);
        assert_eq!(cfg.source_mode, SourceMode::Replay);
        assert_eq!(cfg.correction_mode, CorrectionMode::SAEJ1349);
        assert_eq!(cfg.roller_diameter_m, 0.4);
        assert_eq!(cfg.encoder_pulses_per_rev, 48.0);
        assert_eq!(cfg.roller_inertia_kg_m2, 4.2);
        assert_eq!(cfg.sample_window_ms, 125);
    }
}
