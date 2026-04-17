use serde::{Deserialize, Serialize};

/// Hardware or software fault codes that can be attached to a `LiveFrame`.
///
/// Multiple faults may be active simultaneously; consumers should treat
/// `Vec<FaultCode>` as a bitset-style flag collection.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FaultCode {
    /// CAN bus message did not arrive within the expected window.
    CanTimeout,
    /// CAN bus message arrived but failed validation (length, range, CRC).
    CanInvalid,
    /// Roller encoder pulse stream is missing or implausible.
    EncoderInvalid,
    /// Engine ignition pulse stream is missing or implausible.
    EnginePulseInvalid,
    /// Serial frame CRC did not match computed value.
    SerialCrcMismatch,
    /// Serial stream lost sync; magic header not found at expected offset.
    SerialDesync,
    /// BME280 I2C read failed or returned out-of-range data.
    AmbientSensorFault,
    /// Internal queue or ring buffer overflowed; frames were dropped.
    Overflow,
    /// A subsystem did not respond within its deadline.
    Timeout,
    /// Unclassified fault; inspect logs for context.
    Unknown,
}

impl std::fmt::Display for FaultCode {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Reuse the debug representation for human-readable display.
        write!(f, "{:?}", self)
    }
}
