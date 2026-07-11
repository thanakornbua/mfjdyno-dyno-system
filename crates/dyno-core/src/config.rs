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
    /// Device path of the UART port connected to the ESP32, or `auto` to
    /// detect the CP210x bridge at startup (see [`crate::detect`]).
    pub serial_port: String,
    /// Baud rate; must match the ESP32 firmware setting.
    pub serial_baud: u32,
    /// SocketCAN interface for production AFR, or `auto` to detect the first
    /// CAN network interface at startup (see [`crate::detect`]).
    pub can_iface: String,
    /// Runtime profile selector. Production means ESP32 JSON + SocketCAN + no Modbus AFR.
    pub profile: String,
    /// Legacy Modbus AFR path. Disabled by default for production.
    pub modbus_afr_enabled: bool,

    // ── WebSocket ────────────────────────────────────────────────────────────
    /// `host:port` the WebSocket server will bind to.
    pub ws_bind: String,
    /// `host:port` the HTTP history/query API will bind to.
    pub api_bind: String,

    // ── Storage ──────────────────────────────────────────────────────────────
    /// Fixed per-machine data directory that `db_path` and the ESP32 config
    /// paths are anchored inside of, unless explicitly overridden. See
    /// [`crate::paths::resolve_data_dir`].
    pub data_dir: String,
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
    /// Deprecated/no-op compatibility setting. Runs are operator-bounded now;
    /// RPM below `record_rpm` pauses collection instead of stopping the run.
    pub stop_rpm: f32,
}

const DEFAULT_DB_PATH: &str = "dyno.db";
const DEFAULT_ESP32_CONFIG_PATH: &str = "esp32-device-config.json";
const DEFAULT_ESP32_APPLIED_CONFIG_PATH: &str = "esp32-last-applied.json";

impl Config {
    /// Build a `Config` by reading `DYNO_*` environment variables.
    ///
    /// Missing variables fall back to hard-coded defaults that are safe for
    /// a standard Raspberry Pi 5 + ESP32 setup. Resolves and creates the
    /// self-provisioned data directory (see [`crate::paths`]) and anchors
    /// any storage paths still at their built-in relative default inside it,
    /// so the backend's state no longer depends on its launch directory.
    pub fn from_env() -> anyhow::Result<Self> {
        let data_dir = crate::paths::resolve_data_dir()?;

        let db_path = crate::paths::anchor_default(
            &data_dir,
            env_str_any(&["DYNO_STORAGE_DB_PATH", "DYNO_DB_PATH"], DEFAULT_DB_PATH),
            DEFAULT_DB_PATH,
        );
        crate::paths::warn_if_legacy_cwd_db_exists(&db_path, DEFAULT_DB_PATH);

        let esp32_config_path = crate::paths::anchor_default(
            &data_dir,
            env_str("DYNO_ESP32_CONFIG_PATH", DEFAULT_ESP32_CONFIG_PATH),
            DEFAULT_ESP32_CONFIG_PATH,
        );
        let esp32_applied_config_path = crate::paths::anchor_default(
            &data_dir,
            env_str("DYNO_ESP32_APPLIED_CONFIG_PATH", DEFAULT_ESP32_APPLIED_CONFIG_PATH),
            DEFAULT_ESP32_APPLIED_CONFIG_PATH,
        );

        Ok(Self {
            serial_port:          env_str("DYNO_SERIAL_PORT",        "auto"),
            serial_baud:          env_parse("DYNO_SERIAL_BAUD",      115_200u32),
            can_iface:            env_str("DYNO_CAN_IFACE",          "auto"),
            profile:              env_str("DYNO_PROFILE",            "production"),
            modbus_afr_enabled:   env_parse("DYNO_MODBUS_AFR_ENABLED", false),
            ws_bind:              env_str("DYNO_WS_BIND",            "0.0.0.0:9000"),
            api_bind:             env_str("DYNO_API_BIND",           "0.0.0.0:9001"),
            data_dir:             data_dir.display().to_string(),
            db_path,
            esp32_config_path,
            esp32_applied_config_path,
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
        })
    }
}

impl fmt::Display for Config {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        writeln!(f, "  serial_port          = {}", self.serial_port)?;
        writeln!(f, "  serial_baud          = {}", self.serial_baud)?;
        writeln!(f, "  can_iface            = {}", self.can_iface)?;
        writeln!(f, "  profile              = {}", self.profile)?;
        writeln!(f, "  modbus_afr_enabled   = {}", self.modbus_afr_enabled)?;
        writeln!(f, "  ws_bind              = {}", self.ws_bind)?;
        writeln!(f, "  api_bind             = {}", self.api_bind)?;
        writeln!(f, "  data_dir             = {}", self.data_dir)?;
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

fn env_str_any(keys: &[&str], default: &str) -> String {
    keys.iter()
        .find_map(|key| env::var(key).ok())
        .unwrap_or_else(|| default.to_owned())
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
    use crate::test_env_lock;

    #[test]
    fn defaults_are_sane() {
        let _guard = test_env_lock();
        // Run with no DYNO_* vars set; just ensure it doesn't panic and the
        // defaults match the expected production values. DYNO_DATA_DIR is
        // pinned to a throwaway temp dir so the test doesn't depend on
        // whether /var/lib/dyno is writable in the sandbox.
        env::remove_var("DYNO_SERIAL_PORT");
        env::remove_var("DYNO_SERIAL_BAUD");
        env::remove_var("DYNO_CAN_IFACE");
        env::remove_var("DYNO_PROFILE");
        env::remove_var("DYNO_MODBUS_AFR_ENABLED");
        env::remove_var("DYNO_WS_BIND");
        env::remove_var("DYNO_API_BIND");
        env::remove_var("DYNO_STORAGE_DB_PATH");
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

        let data_dir = std::env::temp_dir().join("dyno-config-test-defaults-are-sane");
        let _ = std::fs::remove_dir_all(&data_dir);
        env::set_var("DYNO_DATA_DIR", data_dir.display().to_string());

        let cfg = Config::from_env().expect("build config");
        env::remove_var("DYNO_DATA_DIR");

        assert_eq!(cfg.serial_port, "auto");
        assert_eq!(cfg.serial_baud, 115_200);
        assert_eq!(cfg.can_iface, "auto");
        assert_eq!(cfg.profile, "production");
        assert!(!cfg.modbus_afr_enabled);
        assert_eq!(cfg.ws_bind, "0.0.0.0:9000");
        assert_eq!(cfg.api_bind, "0.0.0.0:9001");
        assert_eq!(cfg.data_dir, data_dir.display().to_string());
        assert_eq!(cfg.db_path, data_dir.join("dyno.db").display().to_string());
        assert_eq!(
            cfg.esp32_config_path,
            data_dir.join("esp32-device-config.json").display().to_string()
        );
        assert_eq!(
            cfg.esp32_applied_config_path,
            data_dir.join("esp32-last-applied.json").display().to_string()
        );
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

        let _ = std::fs::remove_dir_all(&data_dir);
    }

    #[test]
    fn env_override_is_applied() {
        let _guard = test_env_lock();
        // Safety: tests run in the same process; set then immediately unset.
        env::set_var("DYNO_SERIAL_BAUD", "115200");
        env::set_var("DYNO_CAN_IFACE", "can1");
        env::set_var("DYNO_PROFILE", "bench");
        env::set_var("DYNO_MODBUS_AFR_ENABLED", "true");
        env::set_var("DYNO_API_BIND", "127.0.0.1:9101");
        env::set_var("DYNO_STORAGE_DB_PATH", "/tmp/runs-rust.db");
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

        let data_dir = std::env::temp_dir().join("dyno-config-test-env-override");
        let _ = std::fs::remove_dir_all(&data_dir);
        env::set_var("DYNO_DATA_DIR", data_dir.display().to_string());

        let cfg = Config::from_env().expect("build config");
        env::remove_var("DYNO_DATA_DIR");
        env::remove_var("DYNO_SERIAL_BAUD");
        env::remove_var("DYNO_CAN_IFACE");
        env::remove_var("DYNO_PROFILE");
        env::remove_var("DYNO_MODBUS_AFR_ENABLED");
        env::remove_var("DYNO_API_BIND");
        env::remove_var("DYNO_STORAGE_DB_PATH");
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
        assert_eq!(cfg.can_iface, "can1");
        assert_eq!(cfg.profile, "bench");
        assert!(cfg.modbus_afr_enabled);
        assert_eq!(cfg.api_bind, "127.0.0.1:9101");
        assert_eq!(cfg.db_path, "/tmp/runs-rust.db");
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
        assert_eq!(cfg.data_dir, data_dir.display().to_string());

        let _ = std::fs::remove_dir_all(&data_dir);
    }
}
