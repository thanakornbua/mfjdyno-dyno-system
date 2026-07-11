//! Operational startup checks and lightweight health reporting.
//!
//! These checks are intentionally lightweight. They verify the filesystem and
//! device paths the dyno service expects at boot and provide operator-readable
//! status without probing deep runtime behavior.

use std::env;
use std::fs::{self, OpenOptions};
use std::io::ErrorKind;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::Serialize;
use tracing::{error, info, warn};

use crate::config::{Config, SourceMode};

const BME280_I2C_PATH: &str = "/dev/i2c-1";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum HealthStatus {
    Ok,
    Degraded,
    Error,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct StartupCheck {
    pub name: String,
    pub required: bool,
    pub status: HealthStatus,
    pub summary: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct StartupHealth {
    pub status: HealthStatus,
    pub source_mode: String,
    pub checks: Vec<StartupCheck>,
}

impl StartupHealth {
    pub fn has_errors(&self) -> bool {
        self.checks.iter().any(|check| check.status == HealthStatus::Error)
    }
}

pub fn collect_startup_health(config: &Config) -> StartupHealth {
    let mut checks = vec![check_database_path(&config.db_path)];

    if config.source_mode == SourceMode::Live {
        checks.push(check_serial_path(&config.serial_port));
        let uart_bridge_path = env::var("DYNO_UART_BRIDGE_PATH")
            .unwrap_or_else(|_| config.serial_port.clone());
        checks.push(check_named_device(
            "uart_bridge",
            "UART bridge",
            Path::new(&uart_bridge_path),
            true,
            "ESP32 UART bridge ingest will keep retrying until it appears",
        ));
    }

    if config.bme280_enabled {
        checks.push(check_optional_i2c(Path::new(BME280_I2C_PATH)));
    }

    let status = if checks.iter().any(|check| check.status == HealthStatus::Error) {
        HealthStatus::Error
    } else if checks
        .iter()
        .any(|check| check.status == HealthStatus::Degraded)
    {
        HealthStatus::Degraded
    } else {
        HealthStatus::Ok
    };

    StartupHealth {
        status,
        source_mode: config.source_mode.to_string(),
        checks,
    }
}

pub fn log_startup_health(health: &StartupHealth) {
    for check in &health.checks {
        match check.status {
            HealthStatus::Ok => info!("startup check [{}]: {}", check.name, check.summary),
            HealthStatus::Degraded => warn!("startup check [{}]: {}", check.name, check.summary),
            HealthStatus::Error => error!("startup check [{}]: {}", check.name, check.summary),
        }
    }
}

fn check_database_path(db_path: &str) -> StartupCheck {
    let db_path = PathBuf::from(db_path);
    let parent = database_parent(&db_path);

    if let Err(err) = fs::create_dir_all(&parent) {
        return StartupCheck {
            name: "database_path".to_owned(),
            required: true,
            status: HealthStatus::Error,
            summary: format!(
                "database directory {} is not usable: {err}",
                parent.display()
            ),
        };
    }

    let probe_name = format!(".dynod-write-check-{}", current_time_ms());
    let probe_path = parent.join(probe_name);
    match OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(&probe_path)
    {
        Ok(file) => {
            drop(file);
            match fs::remove_file(&probe_path) {
                Ok(()) => StartupCheck {
                    name: "database_path".to_owned(),
                    required: true,
                    status: HealthStatus::Ok,
                    summary: format!(
                        "database path {} is writable",
                        db_path.display()
                    ),
                },
                Err(err) => StartupCheck {
                    name: "database_path".to_owned(),
                    required: true,
                    status: HealthStatus::Degraded,
                    summary: format!(
                        "database path {} is writable, but cleanup of {} failed: {err}",
                        db_path.display(),
                        probe_path.display()
                    ),
                },
            }
        }
        Err(err) if err.kind() == ErrorKind::AlreadyExists => StartupCheck {
            name: "database_path".to_owned(),
            required: true,
            status: HealthStatus::Ok,
            summary: format!(
                "database path {} is writable",
                db_path.display()
            ),
        },
        Err(err) => StartupCheck {
            name: "database_path".to_owned(),
            required: true,
            status: HealthStatus::Error,
            summary: format!(
                "database path {} is not writable: {err}",
                db_path.display()
            ),
        },
    }
}

fn check_serial_path(serial_port: &str) -> StartupCheck {
    let serial_path = Path::new(serial_port);
    if serial_path.exists() {
        return StartupCheck {
            name: "serial_port".to_owned(),
            required: true,
            status: HealthStatus::Ok,
            summary: format!("serial device {} is present", serial_path.display()),
        };
    }

    StartupCheck {
        name: "serial_port".to_owned(),
        required: true,
        status: HealthStatus::Degraded,
        summary: format!(
            "serial device {} is missing; live ingest will keep retrying until it appears",
            serial_path.display()
        ),
    }
}

fn check_optional_i2c(i2c_path: &Path) -> StartupCheck {
    if i2c_path.exists() {
        return StartupCheck {
            name: "bme280_i2c".to_owned(),
            required: false,
            status: HealthStatus::Ok,
            summary: format!("optional I2C device {} is present", i2c_path.display()),
        };
    }

    StartupCheck {
        name: "bme280_i2c".to_owned(),
        required: false,
        status: HealthStatus::Degraded,
        summary: format!(
            "optional I2C device {} is missing; ambient reads will fall back to stub values",
            i2c_path.display()
        ),
    }
}

fn check_named_device(
    name: &str,
    label: &str,
    path: &Path,
    required: bool,
    missing_detail: &str,
) -> StartupCheck {
    if path.exists() {
        return StartupCheck {
            name: name.to_owned(),
            required,
            status: HealthStatus::Ok,
            summary: format!("{label} {} is present", path.display()),
        };
    }

    StartupCheck {
        name: name.to_owned(),
        required,
        status: HealthStatus::Degraded,
        summary: format!("{label} {} is missing; {missing_detail}", path.display()),
    }
}

fn database_parent(db_path: &Path) -> PathBuf {
    match db_path.parent() {
        Some(parent) if !parent.as_os_str().is_empty() => parent.to_path_buf(),
        _ => PathBuf::from("."),
    }
}

fn current_time_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::correction::CorrectionMode;
    use std::sync::atomic::{AtomicU64, Ordering};

    static NEXT_TEST_ID: AtomicU64 = AtomicU64::new(1);

    fn test_config(db_path: &str) -> Config {
        Config {
            serial_port: "/definitely/missing/serial".to_owned(),
            serial_baud: 921_600,
            can_iface: "can0".to_owned(),
            profile: "production".to_owned(),
            modbus_afr_enabled: false,
            ws_bind: "127.0.0.1:0".to_owned(),
            api_bind: "127.0.0.1:0".to_owned(),
            data_dir: ".".to_owned(),
            db_path: db_path.to_owned(),
            esp32_config_path: "esp32-device-config.json".to_owned(),
            esp32_applied_config_path: "esp32-last-applied.json".to_owned(),
            esp32_command_timeout_ms: 1_500,
            esp32_command_retries: 3,
            bme280_enabled: true,
            source_mode: SourceMode::Live,
            correction_mode: CorrectionMode::None,
            roller_diameter_m: 0.318,
            encoder_pulses_per_rev: 60.0,
            roller_inertia_kg_m2: 3.5,
            sample_window_ms: 100,
            ui_broadcast_rate_hz: 20,
            arm_rpm: 1500.0,
            record_rpm: 2000.0,
            stop_rpm: 1000.0,
            engine_noise_mains_hz: 50.0,
        }
    }

    #[test]
    fn startup_health_marks_missing_live_serial_as_degraded() {
        let unique = NEXT_TEST_ID.fetch_add(1, Ordering::Relaxed);
        let db_path = std::env::temp_dir()
            .join(format!("dyno-health-{unique}.sqlite"))
            .display()
            .to_string();
        let health = collect_startup_health(&test_config(&db_path));

        assert_eq!(health.status, HealthStatus::Degraded);
        assert!(health
            .checks
            .iter()
            .any(|check| check.name == "serial_port" && check.status == HealthStatus::Degraded));
        assert!(health
            .checks
            .iter()
            .any(|check| check.name == "database_path" && check.status == HealthStatus::Ok));
    }

    #[test]
    fn startup_health_skips_serial_check_in_replay_mode() {
        let unique = NEXT_TEST_ID.fetch_add(1, Ordering::Relaxed);
        let db_path = std::env::temp_dir()
            .join(format!("dyno-health-replay-{unique}.sqlite"))
            .display()
            .to_string();
        let mut config = test_config(&db_path);
        config.source_mode = SourceMode::Replay;
        config.bme280_enabled = false;

        let health = collect_startup_health(&config);

        assert_eq!(health.status, HealthStatus::Ok);
        assert!(!health
            .checks
            .iter()
            .any(|check| check.name == "serial_port"));
    }

    #[test]
    fn startup_health_reports_database_path_errors() {
        let unique = NEXT_TEST_ID.fetch_add(1, Ordering::Relaxed);
        let blocking_path = std::env::temp_dir().join(format!("dyno-health-block-{unique}"));
        fs::write(&blocking_path, "block").expect("create blocking file");
        let db_path = blocking_path.join("dyno.sqlite");
        let config = test_config(&db_path.display().to_string());

        let health = collect_startup_health(&config);

        assert_eq!(health.status, HealthStatus::Error);
        assert!(health.has_errors());
        assert!(health
            .checks
            .iter()
            .any(|check| check.name == "database_path" && check.status == HealthStatus::Error));

        let _ = fs::remove_file(blocking_path);
    }
}
