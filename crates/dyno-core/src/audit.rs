//! Append-only audit trail for calibration lock events and machine configuration.

use std::sync::Arc;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::storage::Storage;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AuditEvent {
    LockCalibration,
    UnlockCalibration,
    ApplyMachineConfig,
    StartRun,
    StopRun,
    ResetFault,
}

impl std::fmt::Display for AuditEvent {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let s = match self {
            Self::LockCalibration => "lock_calibration",
            Self::UnlockCalibration => "unlock_calibration",
            Self::ApplyMachineConfig => "apply_machine_config",
            Self::StartRun => "start_run",
            Self::StopRun => "stop_run",
            Self::ResetFault => "reset_fault",
        };
        f.write_str(s)
    }
}

impl std::str::FromStr for AuditEvent {
    type Err = ();

    fn from_str(s: &str) -> Result<Self, ()> {
        match s {
            "lock_calibration" => Ok(Self::LockCalibration),
            "unlock_calibration" => Ok(Self::UnlockCalibration),
            "apply_machine_config" => Ok(Self::ApplyMachineConfig),
            "start_run" => Ok(Self::StartRun),
            "stop_run" => Ok(Self::StopRun),
            "reset_fault" => Ok(Self::ResetFault),
            _ => Err(()),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuditRecord {
    pub id: i64,
    pub occurred_at: DateTime<Utc>,
    pub event: AuditEvent,
    pub calibration_profile_id: Option<i64>,
    pub params_snapshot: Value,
}

struct AuditLoggerInner {
    storage: Storage,
}

/// Append-only audit logger backed by the shared SQLite storage worker.
///
/// `Clone` is cheap — the inner storage channel sender is reference-counted
/// behind the `Arc`.
#[derive(Clone)]
pub struct AuditLogger {
    inner: Arc<AuditLoggerInner>,
}

impl AuditLogger {
    pub fn new(storage: Storage) -> Self {
        Self {
            inner: Arc::new(AuditLoggerInner { storage }),
        }
    }

    pub async fn log(
        &self,
        event: AuditEvent,
        profile_id: Option<i64>,
        snapshot: Value,
    ) -> anyhow::Result<()> {
        self.inner
            .storage
            .insert_audit_record(event, profile_id, snapshot)
            .await
    }
}
