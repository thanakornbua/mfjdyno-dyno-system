use serde::{Deserialize, Serialize};

/// ESP32-side acquisition metadata carried alongside a fused `LiveFrame`.
///
/// These fields preserve the raw DAQ status words while also exposing the
/// backend's current interpretation of per-signal validity. They describe only
/// ESP32-owned acquisition paths; Pi-owned sensors such as the BME280 remain
/// separate top-level fields on `LiveFrame`.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
pub struct Esp32TelemetryStatus {
    /// Raw ESP32 signal status bitfield from the UART telemetry frame.
    pub signal_flags: u16,
    /// Raw ESP32 fault bitfield from the UART telemetry frame.
    pub fault_flags: u16,
    /// Raw ESP32 CAN/AFR status code from the UART telemetry frame.
    pub can_status: u16,

    /// `true` when the engine pulse signal is currently considered valid.
    pub engine_signal_valid: bool,
    /// `true` when the roller encoder signal is currently considered valid.
    pub roller_signal_valid: bool,
    /// `true` when AFR is currently considered valid.
    pub afr_valid: bool,
    /// `true` when lambda is currently considered valid.
    pub lambda_valid: bool,
    /// `true` when the ESP32 reports the CAN subsystem as active.
    pub can_active: bool,

    /// `true` when the ESP32 flagged a one-frame engine stall transition.
    pub engine_stalled: bool,
    /// `true` when the ESP32 flagged a one-frame roller-stop transition.
    pub roller_stopped: bool,
}
