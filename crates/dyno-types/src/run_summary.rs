use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

/// Persistent summary record for a completed dyno run, stored in SQLite
/// and returned by the run-history API.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct RunSummary {
    /// Stable database identifier for this run.
    pub run_id: i64,
    /// Human-readable label assigned by the operator (or auto-generated).
    pub run_name: String,
    /// Wall-clock time at which recording started.
    pub date: DateTime<Utc>,
    /// Peak wheel power recorded during the run, in horsepower.
    pub peak_power_hp: f32,
    /// Engine RPM at which `peak_power_hp` was observed.
    pub peak_power_rpm: f32,
    /// Peak wheel torque recorded during the run, in Nm.
    pub peak_torque_nm: f32,
    /// Engine RPM at which `peak_torque_nm` was observed.
    pub peak_torque_rpm: f32,
}
