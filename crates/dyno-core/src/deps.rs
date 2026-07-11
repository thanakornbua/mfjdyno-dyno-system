//! Consolidated external dependency/package check, surfaced to the operator
//! at first boot.
//!
//! Dependency checks were previously scattered — [`crate::flash`] has its own
//! `arduino-cli` preflight and [`crate::detect`] enumerates serial/CAN
//! devices. This module wires those existing checks into a single report
//! ([`check_dependencies`]) instead of reimplementing PATH scanning or device
//! enumeration.

use std::path::Path;
use std::time::Duration;

use serde::Serialize;

use crate::config::{Config, SourceMode};
use crate::detect::SerialDevice;
use crate::flash::{CommandRunner, FlashOptions, SystemCommandRunner};

/// Result status of a single dependency check.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum DependencyStatus {
    Ok,
    Missing,
    Unknown,
}

/// One structured dependency-check result.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct DependencyCheck {
    pub name: String,
    pub category: String,
    pub required: bool,
    pub status: DependencyStatus,
    pub detail: String,
    pub remediation: String,
    /// True when this dependency being `missing` makes an ESP flash attempt
    /// pointless. The flash endpoint gate and the setup wizard both key off
    /// this flag instead of hard-coding dependency names.
    pub blocks_flashing: bool,
}

const CATEGORY_FLASH_TOOLCHAIN: &str = "flash-toolchain";
const CATEGORY_DEVICE: &str = "device";

/// How long to wait for `arduino-cli core list` before giving up and
/// reporting `unknown` — this must never hang startup or an API request.
const CORE_LIST_TIMEOUT: Duration = Duration::from_secs(5);

/// Run every dependency check and return the full report.
pub fn check_dependencies(config: &Config) -> Vec<DependencyCheck> {
    let flash_opts = FlashOptions::from_env();
    let runner = SystemCommandRunner;
    let arduino_cli_available = runner.tool_available(&flash_opts.tool);

    vec![
        check_arduino_cli(arduino_cli_available, &flash_opts.tool),
        check_esp32_core(arduino_cli_available, &flash_opts.tool),
        check_firmware_sketch(&flash_opts.sketch),
        check_serial_devices(
            crate::detect::list_serial_devices(),
            config.source_mode == SourceMode::Live,
        ),
        check_can_interface(Path::new("/sys/class/net"), &config.can_iface),
    ]
}

fn check_arduino_cli(available: bool, tool: &str) -> DependencyCheck {
    if available {
        DependencyCheck {
            name: "arduino_cli".to_owned(),
            category: CATEGORY_FLASH_TOOLCHAIN.to_owned(),
            required: false,
            status: DependencyStatus::Ok,
            detail: format!("'{tool}' found on PATH"),
            remediation: String::new(),
            blocks_flashing: true,
        }
    } else {
        DependencyCheck {
            name: "arduino_cli".to_owned(),
            category: CATEGORY_FLASH_TOOLCHAIN.to_owned(),
            required: false,
            status: DependencyStatus::Missing,
            detail: format!("'{tool}' was not found on PATH; ESP32 firmware flashing is unavailable"),
            remediation: format!(
                "Install arduino-cli (https://arduino.github.io/arduino-cli) and ensure '{tool}' \
                 is on PATH, or set DYNO_FLASH_TOOL to its path. Flashing is optional; live \
                 telemetry does not require it."
            ),
            blocks_flashing: true,
        }
    }
}

fn check_esp32_core(arduino_cli_available: bool, tool: &str) -> DependencyCheck {
    if !arduino_cli_available {
        return DependencyCheck {
            name: "arduino_esp32_core".to_owned(),
            category: CATEGORY_FLASH_TOOLCHAIN.to_owned(),
            required: false,
            status: DependencyStatus::Unknown,
            detail: "arduino-cli is not on PATH; cannot check whether the esp32 core is installed"
                .to_owned(),
            remediation: "Install arduino-cli first, then run: arduino-cli core install esp32:esp32"
                .to_owned(),
            blocks_flashing: true,
        };
    }

    match run_core_list(tool, CORE_LIST_TIMEOUT) {
        Some(output) if output.contains("esp32:esp32") => DependencyCheck {
            name: "arduino_esp32_core".to_owned(),
            category: CATEGORY_FLASH_TOOLCHAIN.to_owned(),
            required: false,
            status: DependencyStatus::Ok,
            detail: "esp32:esp32 core is installed for arduino-cli".to_owned(),
            remediation: String::new(),
            blocks_flashing: true,
        },
        Some(_) => DependencyCheck {
            name: "arduino_esp32_core".to_owned(),
            category: CATEGORY_FLASH_TOOLCHAIN.to_owned(),
            required: false,
            status: DependencyStatus::Missing,
            detail: "esp32:esp32 core is not installed for arduino-cli".to_owned(),
            remediation: "Run: arduino-cli core install esp32:esp32".to_owned(),
            blocks_flashing: true,
        },
        None => DependencyCheck {
            name: "arduino_esp32_core".to_owned(),
            category: CATEGORY_FLASH_TOOLCHAIN.to_owned(),
            required: false,
            status: DependencyStatus::Unknown,
            detail: "could not determine esp32 core installation status (arduino-cli core list \
                      did not respond in time)"
                .to_owned(),
            remediation: "Run manually: arduino-cli core list".to_owned(),
            blocks_flashing: true,
        },
    }
}

/// Run `<tool> core list` and wait up to `timeout` for its combined output.
/// Returns `None` on timeout or launch failure so the caller degrades to
/// `unknown` instead of hanging. On timeout the child process is killed and
/// reaped — nothing is left running in the background.
fn run_core_list(tool: &str, timeout: Duration) -> Option<String> {
    use std::process::{Command, Stdio};

    let mut child = Command::new(tool)
        .args(["core", "list"])
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .ok()?;

    let deadline = std::time::Instant::now() + timeout;
    loop {
        match child.try_wait() {
            Ok(Some(_status)) => break,
            Ok(None) => {
                if std::time::Instant::now() >= deadline {
                    let _ = child.kill();
                    let _ = child.wait();
                    return None;
                }
                std::thread::sleep(Duration::from_millis(50));
            }
            Err(_) => {
                let _ = child.kill();
                let _ = child.wait();
                return None;
            }
        }
    }

    let output = child.wait_with_output().ok()?;
    Some(format!(
        "{}{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    ))
}

/// The firmware sketch is always available: either the configured path exists
/// on disk, or [`crate::flash`] falls back to the sketch embedded in this
/// binary. This mirrors that fallback rather than duplicating it.
fn check_firmware_sketch(sketch_path: &str) -> DependencyCheck {
    let detail = if Path::new(sketch_path).exists() {
        format!("firmware sketch present on disk at {sketch_path}")
    } else {
        format!(
            "configured sketch '{sketch_path}' not found on disk; the firmware embedded in the \
             backend binary will be used instead"
        )
    };
    DependencyCheck {
        name: "firmware_sketch".to_owned(),
        category: CATEGORY_FLASH_TOOLCHAIN.to_owned(),
        required: false,
        status: DependencyStatus::Ok,
        detail,
        remediation: String::new(),
        blocks_flashing: false,
    }
}

fn check_serial_devices(devices: Vec<SerialDevice>, required: bool) -> DependencyCheck {
    if devices.is_empty() {
        DependencyCheck {
            name: "serial_device".to_owned(),
            category: CATEGORY_DEVICE.to_owned(),
            required,
            status: DependencyStatus::Missing,
            detail: "no serial devices detected under /dev/serial/by-id or /dev/tty{USB,ACM}*"
                .to_owned(),
            remediation: "Connect the ESP32 over USB and re-run setup, or select a port manually \
                          once connected."
                .to_owned(),
            blocks_flashing: false,
        }
    } else {
        DependencyCheck {
            name: "serial_device".to_owned(),
            category: CATEGORY_DEVICE.to_owned(),
            required,
            status: DependencyStatus::Ok,
            detail: format!("{} serial device(s) detected", devices.len()),
            remediation: String::new(),
            blocks_flashing: false,
        }
    }
}

/// Best-effort CAN interface presence check. `unknown` is acceptable: some
/// deployments never wire up CAN, so a missing interface here does not imply
/// misconfiguration the way a missing serial device does.
fn check_can_interface(sys_net_dir: &Path, iface: &str) -> DependencyCheck {
    if sys_net_dir.join(iface).exists() {
        DependencyCheck {
            name: "can_interface".to_owned(),
            category: CATEGORY_DEVICE.to_owned(),
            required: false,
            status: DependencyStatus::Ok,
            detail: format!("CAN interface '{iface}' is present"),
            remediation: String::new(),
            blocks_flashing: false,
        }
    } else {
        DependencyCheck {
            name: "can_interface".to_owned(),
            category: CATEGORY_DEVICE.to_owned(),
            required: false,
            status: DependencyStatus::Unknown,
            detail: format!(
                "CAN interface '{iface}' was not found under {}; this is expected on \
                 deployments without a CAN AFR source",
                sys_net_dir.display()
            ),
            remediation: "If this dyno uses a CAN-based AFR/wideband source, verify the \
                          gs_usb/slcand adapter is connected and dyno-canable.service is running."
                .to_owned(),
            blocks_flashing: false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::sync::atomic::{AtomicU64, Ordering};

    static NEXT_TEST_ID: AtomicU64 = AtomicU64::new(1);

    fn temp_dir(prefix: &str) -> std::path::PathBuf {
        let unique = NEXT_TEST_ID.fetch_add(1, Ordering::Relaxed);
        let dir = std::env::temp_dir().join(format!("dyno-deps-{prefix}-{unique}"));
        fs::create_dir_all(&dir).expect("create temp dir");
        dir
    }

    fn test_config(source_mode: SourceMode, can_iface: &str) -> Config {
        Config {
            serial_port: "/dev/null".to_owned(),
            serial_baud: 921_600,
            can_iface: can_iface.to_owned(),
            profile: "production".to_owned(),
            modbus_afr_enabled: false,
            ws_bind: "127.0.0.1:0".to_owned(),
            api_bind: "127.0.0.1:0".to_owned(),
            data_dir: ".".to_owned(),
            db_path: ":memory:".to_owned(),
            esp32_config_path: "esp32-device-config.json".to_owned(),
            esp32_applied_config_path: "esp32-last-applied.json".to_owned(),
            esp32_command_timeout_ms: 1_500,
            esp32_command_retries: 3,
            bme280_enabled: false,
            source_mode,
            correction_mode: crate::correction::CorrectionMode::None,
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
    fn missing_arduino_cli_marks_flash_toolchain_optional_and_esp32_core_unknown() {
        let arduino = check_arduino_cli(false, "arduino-cli");
        assert_eq!(arduino.status, DependencyStatus::Missing);
        assert!(!arduino.required);
        assert_eq!(arduino.category, "flash-toolchain");
        assert!(arduino.blocks_flashing);

        let core = check_esp32_core(false, "arduino-cli");
        assert_eq!(core.status, DependencyStatus::Unknown);
        assert!(!core.required);
        assert!(core.blocks_flashing);
    }

    #[test]
    fn present_arduino_cli_is_ok() {
        let arduino = check_arduino_cli(true, "arduino-cli");
        assert_eq!(arduino.status, DependencyStatus::Ok);
        assert!(arduino.detail.contains("arduino-cli"));
    }

    #[test]
    fn firmware_sketch_on_disk_reports_ok_with_path_detail() {
        let dir = temp_dir("sketch-present");
        let check = check_firmware_sketch(&dir.display().to_string());
        assert_eq!(check.status, DependencyStatus::Ok);
        assert!(check.detail.contains("present on disk"));
        fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn firmware_sketch_missing_falls_back_to_embedded_and_still_ok() {
        let check = check_firmware_sketch("/nonexistent/dyno-sketch-dir");
        assert_eq!(check.status, DependencyStatus::Ok);
        assert!(check.detail.contains("embedded"));
    }

    #[test]
    fn serial_devices_present_reports_ok() {
        let devices = vec![SerialDevice {
            path: "/dev/ttyUSB0".to_owned(),
            label: "usb-Silicon_Labs_CP2102N".to_owned(),
            is_esp32_guess: true,
        }];
        let check = check_serial_devices(devices, true);
        assert_eq!(check.status, DependencyStatus::Ok);
        assert!(check.required);
    }

    #[test]
    fn serial_devices_absent_reports_missing_and_not_required_in_replay() {
        let check = check_serial_devices(Vec::new(), false);
        assert_eq!(check.status, DependencyStatus::Missing);
        assert!(!check.required);
        assert!(!check.blocks_flashing, "a missing serial device must not block flashing");
    }

    #[test]
    fn can_interface_present_reports_ok() {
        let dir = temp_dir("can-present");
        fs::create_dir_all(dir.join("can0")).unwrap();
        let check = check_can_interface(&dir, "can0");
        assert_eq!(check.status, DependencyStatus::Ok);
        fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn can_interface_absent_reports_unknown_not_missing() {
        let dir = temp_dir("can-absent");
        let check = check_can_interface(&dir, "can0");
        assert_eq!(check.status, DependencyStatus::Unknown);
        assert!(!check.required);
        fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn check_dependencies_returns_five_checks_without_hanging() {
        let config = test_config(SourceMode::Replay, "can0");
        let checks = check_dependencies(&config);
        assert_eq!(checks.len(), 5);
        let names: Vec<&str> = checks.iter().map(|c| c.name.as_str()).collect();
        assert!(names.contains(&"arduino_cli"));
        assert!(names.contains(&"arduino_esp32_core"));
        assert!(names.contains(&"firmware_sketch"));
        assert!(names.contains(&"serial_device"));
        assert!(names.contains(&"can_interface"));
        // Replay mode: serial device is never required.
        let serial = checks.iter().find(|c| c.name == "serial_device").unwrap();
        assert!(!serial.required);
    }

    #[test]
    fn check_dependencies_requires_serial_device_in_live_mode() {
        let config = test_config(SourceMode::Live, "can0");
        let checks = check_dependencies(&config);
        let serial = checks.iter().find(|c| c.name == "serial_device").unwrap();
        assert!(serial.required);
    }
}
