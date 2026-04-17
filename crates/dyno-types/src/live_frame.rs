use serde::{Deserialize, Serialize};

use crate::{Esp32TelemetryStatus, FaultCode, LiveAlerts, RunState};

/// A single timestamped snapshot of all live dyno measurements.
///
/// This is the primary wire type sent over WebSocket to the Java frontend.
/// All computed fields are `Option` because they may not yet be valid
/// (e.g., speed requires at least two encoder pulses).
///
/// `engine_rpm` and `roller_rpm` are deliberately separate fields —
/// the engine tachometer and the roller encoder are independent signal paths
/// and must never be conflated.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct LiveFrame {
    /// Unix epoch timestamp in milliseconds (source: ESP32 frame header).
    pub ts_ms: i64,

    // ── RPM ─────────────────────────────────────────────────────────────────
    /// Crankshaft RPM derived from the ignition pulse signal via ESP32.
    pub engine_rpm: Option<f32>,
    /// Dyno roller surface RPM derived from the encoder via ESP32.
    pub roller_rpm: Option<f32>,

    // ── Kinematics ───────────────────────────────────────────────────────────
    /// Vehicle speed at the roller contact patch, converted from roller RPM.
    pub speed_kmh: Option<f32>,

    // ── Power & torque ───────────────────────────────────────────────────────
    pub power_hp: Option<f32>,
    pub torque_nm: Option<f32>,
    /// Ambient correction multiplier for future corrected power/torque values.
    pub correction_factor: f32,

    // ── Fuelling ─────────────────────────────────────────────────────────────
    /// Air/fuel ratio (stoichiometric reference: 14.7 for petrol).
    pub afr: Option<f32>,
    /// Lambda (AFR normalised to stoichiometric; 1.0 = stoich).
    pub lambda: Option<f32>,

    // ── Ambient (BME280, Pi-side I2C) ─────────────────────────────────────
    pub ambient_temp_c: Option<f32>,
    pub humidity_pct: Option<f32>,
    /// Barometric pressure in hPa (= mbar).
    pub pressure_hpa: Option<f32>,

    // ── ESP32 acquisition status ───────────────────────────────────────────
    /// Raw + interpreted acquisition status reported by the ESP32 DAQ.
    pub esp32_status: Esp32TelemetryStatus,

    // ── State ────────────────────────────────────────────────────────────────
    pub run_state: RunState,
    pub faults: Vec<FaultCode>,
    pub alerts: LiveAlerts,
}

impl LiveFrame {
    /// Construct a minimal idle frame at the given timestamp.
    pub fn idle(ts_ms: i64) -> Self {
        Self {
            ts_ms,
            engine_rpm: None,
            roller_rpm: None,
            speed_kmh: None,
            power_hp: None,
            torque_nm: None,
            correction_factor: 1.0,
            afr: None,
            lambda: None,
            ambient_temp_c: None,
            humidity_pct: None,
            pressure_hpa: None,
            esp32_status: Esp32TelemetryStatus::default(),
            run_state: RunState::Idle,
            faults: Vec::new(),
            alerts: LiveAlerts::default(),
        }
    }
}
