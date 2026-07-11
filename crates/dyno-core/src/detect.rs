//! Boot-time autodetection of the ESP32 serial port and SocketCAN interface.
//!
//! Setting `DYNO_SERIAL_PORT=auto` (the default) scans `/dev/serial/by-id`
//! for the ESP32's USB-UART bridge. Recognized bridge chips include Silicon
//! Labs CP210x (common on devkits' onboard bridge), CH340 (also common on
//! devkits), and native USB-CDC boards exposing an Espressif `by-id` name
//! (enumerated under `/dev/ttyACM*`). Setting `DYNO_CAN_IFACE=auto` (the
//! default) scans `/sys/class/net` for the first CAN-type network interface
//! — both gs_usb adapters and the slcand-created interface from
//! `dyno-canable.service` register as ARPHRD_CAN.
//!
//! Detection runs once at startup; when nothing matches, the previous fixed
//! defaults (`/dev/ttyUSB0`, `can0`) are used so behavior degrades gracefully.

use std::fs;
use std::path::{Path, PathBuf};

use tracing::{info, warn};

use crate::config::Config;

pub const AUTO: &str = "auto";

const SERIAL_BY_ID_DIR: &str = "/dev/serial/by-id";
const FALLBACK_SERIAL_PORT: &str = "/dev/ttyUSB0";
const FALLBACK_CAN_IFACE: &str = "can0";

/// `by-id` name fragments that identify a devkit's onboard USB-UART bridge
/// (or native USB-CDC). Other USB serial devices on the Pi (e.g. the FT232)
/// must not match.
const ESP32_ID_MARKERS: [&str; 6] =
    ["CP210", "Silicon_Labs", "CH340", "1a86", "QinHeng", "Espressif"];

/// ARPHRD_CAN from `<linux/if_arp.h>`, as exposed in `/sys/class/net/*/type`.
const ARPHRD_CAN: &str = "280";

/// Resolve any `auto` device settings in `config` in place.
pub fn resolve_devices(config: &mut Config) {
    if config.serial_port.eq_ignore_ascii_case(AUTO) {
        config.serial_port = match find_esp32_port_in(Path::new(SERIAL_BY_ID_DIR)) {
            Some(port) => {
                info!("autodetected ESP32 serial port: {}", port.display());
                port.display().to_string()
            }
            None => {
                warn!(
                    "no CP210x serial device found under {SERIAL_BY_ID_DIR}; \
                     falling back to {FALLBACK_SERIAL_PORT}"
                );
                FALLBACK_SERIAL_PORT.to_owned()
            }
        };
    }

    if config.can_iface.eq_ignore_ascii_case(AUTO) {
        config.can_iface = match find_can_iface_in(Path::new("/sys/class/net")) {
            Some(iface) => {
                info!("autodetected CAN interface: {iface}");
                iface
            }
            None => {
                warn!(
                    "no CAN network interface found under /sys/class/net; \
                     falling back to {FALLBACK_CAN_IFACE}"
                );
                FALLBACK_CAN_IFACE.to_owned()
            }
        };
    }
}

/// Find the ESP32's CP210x entry in a `/dev/serial/by-id`-style directory.
///
/// Returns the resolved device node (e.g. `/dev/ttyUSB0`) when the symlink
/// canonicalizes, otherwise the matching by-id path itself. Entries are
/// scanned in sorted order so the choice is stable across boots.
fn find_esp32_port_in(by_id_dir: &Path) -> Option<PathBuf> {
    let mut entries: Vec<PathBuf> = fs::read_dir(by_id_dir)
        .ok()?
        .filter_map(|entry| entry.ok())
        .map(|entry| entry.path())
        .filter(|path| {
            path.file_name()
                .and_then(|name| name.to_str())
                .map(|name| ESP32_ID_MARKERS.iter().any(|marker| name.contains(marker)))
                .unwrap_or(false)
        })
        .collect();
    entries.sort();

    let by_id_path = entries.into_iter().next()?;
    Some(fs::canonicalize(&by_id_path).unwrap_or(by_id_path))
}

/// A serial device candidate the operator can pick for read or flash use.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct SerialDevice {
    /// Resolved device node, e.g. `/dev/ttyUSB0`.
    pub path: String,
    /// Human-friendly label (the `by-id` name when available, else the path).
    pub label: String,
    /// True when the entry looks like the ESP32's CP210x bridge.
    pub is_esp32_guess: bool,
}

/// Enumerate serial device candidates for the operator to choose from.
///
/// Scans `/dev/serial/by-id` (stable, well-labelled, carries the ESP32 guess)
/// and raw `/dev/ttyUSB*` / `/dev/ttyACM*` nodes, de-duplicated by resolved
/// path. Results are stable-sorted with ESP32 guesses first.
pub fn list_serial_devices() -> Vec<SerialDevice> {
    list_serial_devices_in(Path::new(SERIAL_BY_ID_DIR), Path::new("/dev"))
}

fn list_serial_devices_in(by_id_dir: &Path, dev_dir: &Path) -> Vec<SerialDevice> {
    let mut devices: Vec<SerialDevice> = Vec::new();
    let mut seen_paths: std::collections::HashSet<String> = std::collections::HashSet::new();

    // by-id entries first: they carry meaningful labels and the ESP32 guess.
    if let Ok(entries) = fs::read_dir(by_id_dir) {
        let mut by_id: Vec<PathBuf> = entries
            .filter_map(|entry| entry.ok())
            .map(|entry| entry.path())
            .collect();
        by_id.sort();
        for link in by_id {
            let label = link
                .file_name()
                .and_then(|name| name.to_str())
                .unwrap_or_default()
                .to_owned();
            let resolved = fs::canonicalize(&link).unwrap_or_else(|_| link.clone());
            let path = resolved.display().to_string();
            let is_esp32_guess = ESP32_ID_MARKERS.iter().any(|marker| label.contains(marker));
            if seen_paths.insert(path.clone()) {
                devices.push(SerialDevice {
                    path,
                    label: if label.is_empty() { link.display().to_string() } else { label },
                    is_esp32_guess,
                });
            }
        }
    }

    // Raw tty nodes not already covered by a by-id symlink.
    if let Ok(entries) = fs::read_dir(dev_dir) {
        let mut nodes: Vec<PathBuf> = entries
            .filter_map(|entry| entry.ok())
            .map(|entry| entry.path())
            .filter(|path| {
                path.file_name()
                    .and_then(|name| name.to_str())
                    .map(|name| name.starts_with("ttyUSB") || name.starts_with("ttyACM"))
                    .unwrap_or(false)
            })
            .collect();
        nodes.sort();
        for node in nodes {
            let path = node.display().to_string();
            if seen_paths.insert(path.clone()) {
                devices.push(SerialDevice {
                    label: path.clone(),
                    path,
                    is_esp32_guess: false,
                });
            }
        }
    }

    // ESP32 guesses first, then stable by path.
    devices.sort_by(|a, b| {
        b.is_esp32_guess
            .cmp(&a.is_esp32_guess)
            .then_with(|| a.path.cmp(&b.path))
    });
    devices
}

/// Find the first CAN-type interface in a `/sys/class/net`-style directory.
fn find_can_iface_in(sys_net_dir: &Path) -> Option<String> {
    let mut ifaces: Vec<String> = fs::read_dir(sys_net_dir)
        .ok()?
        .filter_map(|entry| entry.ok())
        .filter(|entry| {
            fs::read_to_string(entry.path().join("type"))
                .map(|kind| kind.trim() == ARPHRD_CAN)
                .unwrap_or(false)
        })
        .filter_map(|entry| entry.file_name().into_string().ok())
        .collect();
    ifaces.sort();
    ifaces.into_iter().next()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};

    static NEXT_TEST_ID: AtomicU64 = AtomicU64::new(1);

    fn temp_dir(prefix: &str) -> PathBuf {
        let unique = NEXT_TEST_ID.fetch_add(1, Ordering::Relaxed);
        let dir = std::env::temp_dir().join(format!("dyno-detect-{prefix}-{unique}"));
        fs::create_dir_all(&dir).expect("create temp dir");
        dir
    }

    #[test]
    fn picks_cp210x_entry_and_ignores_other_serial_devices() {
        let dir = temp_dir("by-id");
        fs::write(
            dir.join("usb-Silicon_Labs_CP2102N_USB_to_UART_Bridge_Controller_1234-if00-port0"),
            "",
        )
        .unwrap();
        fs::write(dir.join("usb-FTDI_FT232R_USB_UART_A5XK3RJT-if00-port0"), "").unwrap();

        let port = find_esp32_port_in(&dir).expect("CP210x entry found");
        assert!(port
            .file_name()
            .unwrap()
            .to_str()
            .unwrap()
            .contains("CP2102N"));

        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn returns_none_when_no_cp210x_present() {
        let dir = temp_dir("by-id-none");
        fs::write(dir.join("usb-FTDI_FT232R_USB_UART_A5XK3RJT-if00-port0"), "").unwrap();

        assert_eq!(find_esp32_port_in(&dir), None);

        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn picks_ch340_devkit_bridge() {
        let dir = temp_dir("by-id-ch340");
        fs::write(
            dir.join("usb-1a86_USB_Serial-if00-port0"),
            "",
        )
        .unwrap();
        fs::write(dir.join("usb-FTDI_FT232R_USB_UART_A5XK3RJT-if00-port0"), "").unwrap();

        let port = find_esp32_port_in(&dir).expect("CH340 entry found");
        assert!(port.file_name().unwrap().to_str().unwrap().contains("1a86"));

        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn picks_espressif_native_usb_cdc_bridge() {
        let dir = temp_dir("by-id-espressif");
        fs::write(dir.join("usb-Espressif_USB_JTAG_serial_debug_unit_1234-if00"), "").unwrap();

        let port = find_esp32_port_in(&dir).expect("Espressif entry found");
        assert!(port
            .file_name()
            .unwrap()
            .to_str()
            .unwrap()
            .contains("Espressif"));

        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn finds_first_can_type_interface() {
        let dir = temp_dir("sys-net");
        for (name, kind) in [("eth0", "1"), ("can1", "280"), ("can0", "280"), ("lo", "772")] {
            let iface_dir = dir.join(name);
            fs::create_dir_all(&iface_dir).unwrap();
            fs::write(iface_dir.join("type"), kind).unwrap();
        }

        assert_eq!(find_can_iface_in(&dir), Some("can0".to_owned()));

        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn returns_none_when_no_can_interface() {
        let dir = temp_dir("sys-net-none");
        let iface_dir = dir.join("eth0");
        fs::create_dir_all(&iface_dir).unwrap();
        fs::write(iface_dir.join("type"), "1").unwrap();

        assert_eq!(find_can_iface_in(&dir), None);

        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn lists_serial_devices_with_esp32_guess_first_and_dedups() {
        let by_id = temp_dir("list-by-id");
        let dev = temp_dir("list-dev");
        // Two raw tty nodes and one unrelated file.
        fs::write(dev.join("ttyUSB0"), "").unwrap();
        fs::write(dev.join("ttyUSB1"), "").unwrap();
        fs::write(dev.join("not-a-serial"), "").unwrap();
        // A by-id symlink for the ESP32 pointing at ttyUSB0 (so it de-dups).
        #[cfg(unix)]
        std::os::unix::fs::symlink(
            dev.join("ttyUSB0"),
            by_id.join("usb-Silicon_Labs_CP2102N_USB_to_UART_Bridge_Controller_1234-if00-port0"),
        )
        .unwrap();

        let devices = list_serial_devices_in(&by_id, &dev);

        // ttyUSB0 appears once (via by-id, ESP guess), ttyUSB1 once (raw).
        assert_eq!(devices.len(), 2);
        assert!(devices[0].is_esp32_guess, "esp32 guess should sort first");
        assert!(devices[0].path.ends_with("ttyUSB0"));
        assert!(devices[0].label.contains("CP2102N"));
        assert!(!devices[1].is_esp32_guess);
        assert!(devices[1].path.ends_with("ttyUSB1"));

        fs::remove_dir_all(by_id).unwrap();
        fs::remove_dir_all(dev).unwrap();
    }
}
