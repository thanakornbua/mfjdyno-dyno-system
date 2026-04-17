use serde::{Deserialize, Serialize};

/// Severity level for a single monitored parameter.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AlertLevel {
    /// Parameter is within acceptable bounds.
    Ok,
    /// Parameter is approaching a limit; operator should monitor.
    Warning,
    /// Parameter has exceeded a safe limit; operator must act.
    Critical,
}

impl Default for AlertLevel {
    fn default() -> Self {
        Self::Ok
    }
}

/// Per-frame alert state broadcast to the frontend.
///
/// Each field corresponds to one monitored sensor domain.
/// Additional fields should be added here as new limits are defined.
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
pub struct LiveAlerts {
    pub exhaust_temp: AlertLevel,
    pub o2_ratio: AlertLevel,
    pub lambda: AlertLevel,
}
