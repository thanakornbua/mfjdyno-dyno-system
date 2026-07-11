//! SQLite persistence layer and run recorder task.
//!
//! Storage is kept off the async hot path by routing all SQLite access through a
//! single bounded command queue consumed by a blocking worker thread. A small
//! async `StorageTask` watches the fused `LiveFrame` stream and forwards frames
//! into that worker.

use std::collections::VecDeque;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use anyhow::{anyhow, Context};
use chrono::{DateTime, TimeZone, Utc};
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use tokio::sync::{mpsc, oneshot, watch};
use tokio::task::JoinHandle;
use tracing::{debug, info, warn};

use dyno_types::{LiveFrame, RunState, RunSummary};

use crate::{
    audit::{AuditEvent, AuditRecord},
    calibration::{
        CalibrationProfile, CalibrationProfileChange, CalibrationProfileEvent,
        CalibrationProfileEventType, CalibrationProfileInput,
    },
    config::{Config, SourceMode},
    correction::CorrectionMode,
};

pub const SCHEMA_SQL: &str = r#"
CREATE TABLE IF NOT EXISTS runs (
    run_id INTEGER PRIMARY KEY AUTOINCREMENT,
    started_at_ms INTEGER NOT NULL,
    ended_at_ms INTEGER NULL,
    source_mode TEXT NOT NULL,
    correction_mode TEXT NOT NULL,
    calibration_profile_id INTEGER NULL,
    calibration_profile_name TEXT NULL,
    roller_diameter_m REAL NOT NULL,
    encoder_pulses_per_rev REAL NOT NULL,
    roller_inertia_kg_m2 REAL NOT NULL,
    sample_window_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS calibration_profiles (
    profile_id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    is_active INTEGER NOT NULL DEFAULT 0,
    roller_diameter_m REAL NOT NULL,
    encoder_pulses_per_rev REAL NOT NULL,
    roller_inertia_kg_m2 REAL NOT NULL,
    sample_window_ms INTEGER NOT NULL,
    engine_pulses_per_rev_hint REAL NULL,
    engine_rpm_scale REAL NULL,
    notes TEXT NULL
);

CREATE TABLE IF NOT EXISTS calibration_profile_events (
    event_id INTEGER PRIMARY KEY AUTOINCREMENT,
    profile_id INTEGER NOT NULL,
    event_type TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    summary TEXT NOT NULL,
    previous_values_json TEXT NULL,
    new_values_json TEXT NULL,
    FOREIGN KEY (profile_id) REFERENCES calibration_profiles(profile_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS frames (
    frame_id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER NOT NULL,
    ts_ms INTEGER NOT NULL,
    engine_rpm REAL NULL,
    roller_rpm REAL NULL,
    speed_kmh REAL NULL,
    power_hp REAL NULL,
    torque_nm REAL NULL,
    afr REAL NULL,
    lambda REAL NULL,
    ambient_temp_c REAL NULL,
    humidity_pct REAL NULL,
    pressure_hpa REAL NULL,
    correction_factor REAL NOT NULL,
    run_state TEXT NOT NULL,
    FOREIGN KEY (run_id) REFERENCES runs(run_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_runs_started_at_ms ON runs(started_at_ms DESC);
CREATE INDEX IF NOT EXISTS idx_frames_run_id_ts_ms ON frames(run_id, ts_ms);
CREATE INDEX IF NOT EXISTS idx_calibration_profiles_updated_at_ms ON calibration_profiles(updated_at_ms DESC);
CREATE INDEX IF NOT EXISTS idx_calibration_profile_events_profile_id_created_at_ms
ON calibration_profile_events(profile_id, created_at_ms DESC, event_id DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_calibration_profiles_single_active
ON calibration_profiles(is_active)
WHERE is_active = 1;

CREATE TABLE IF NOT EXISTS audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    occurred_at_ms INTEGER NOT NULL,
    event TEXT NOT NULL,
    calibration_profile_id INTEGER NULL,
    params_snapshot TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_log_occurred_at_ms ON audit_log(occurred_at_ms DESC);

CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"#;

const STORAGE_QUEUE_CAPACITY: usize = 1024;
const DEFAULT_LIST_LIMIT: usize = 20;
/// Maximum number of frames held in the pre-run ring buffer (≈3 s at 100 Hz).
const PRE_RUN_BUFFER_CAP: usize = 300;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct StoredFrame {
    pub run_id: i64,
    pub ts_ms: i64,
    pub engine_rpm: Option<f32>,
    pub roller_rpm: Option<f32>,
    pub speed_kmh: Option<f32>,
    pub power_hp: Option<f32>,
    pub torque_nm: Option<f32>,
    pub afr: Option<f32>,
    pub lambda: Option<f32>,
    pub ambient_temp_c: Option<f32>,
    pub humidity_pct: Option<f32>,
    pub pressure_hpa: Option<f32>,
    pub correction_factor: f32,
    pub run_state: RunState,
}

#[derive(Debug, Clone, PartialEq)]
pub struct StoredRun {
    pub run_id: i64,
    pub started_at_ms: i64,
    pub ended_at_ms: Option<i64>,
    pub source_mode: SourceMode,
    pub correction_mode: CorrectionMode,
    pub calibration_profile_id: Option<i64>,
    pub calibration_profile_name: Option<String>,
    pub vehicle_name: Option<String>,
    pub license_plate: Option<String>,
    pub run_no: Option<i64>,
    pub customer_name: Option<String>,
    pub customer_phone: Option<String>,
    pub notes: Option<String>,
    pub roller_diameter_m: f32,
    pub encoder_pulses_per_rev: f32,
    pub roller_inertia_kg_m2: f32,
    pub sample_window_ms: u32,
    /// Engine config snapshotted from the active calibration profile at run
    /// creation, so edits to the profile afterward don't change a
    /// historical run's recorded engine setup.
    pub engine_pulses_per_rev_hint: Option<f32>,
    pub engine_rpm_scale: Option<f32>,
    pub engine_stroke: Option<u8>,
    pub engine_cylinders: Option<u8>,
    pub peak_power_hp: f32,
    pub peak_power_rpm: f32,
    pub peak_torque_nm: f32,
    pub peak_torque_rpm: f32,
}

impl StoredRun {
    /// Operator-facing run identifier: `{plate}-{run_no}` when both are
    /// recorded, otherwise a `RUN-{run_id}` fallback.
    pub fn display_id(&self) -> String {
        match (&self.license_plate, self.run_no) {
            (Some(plate), Some(run_no)) => format!("{plate}-{run_no}"),
            _ => format!("RUN-{:05}", self.run_id),
        }
    }
}

#[derive(Clone)]
pub struct Storage {
    tx: mpsc::Sender<Command>,
}

/// Watches the fused `LiveFrame` channel and forwards updates into storage.
pub struct StorageTask {
    handle: JoinHandle<()>,
}

#[derive(Debug, Clone)]
struct RecordingConfig {
    source_mode: SourceMode,
    correction_mode: CorrectionMode,
}

/// Operator-entered metadata configured before a run starts; stamped onto the
/// run row when the recorder opens it.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct PendingRunMetadata {
    pub license_plate: Option<String>,
    pub vehicle_name: Option<String>,
    pub customer_name: Option<String>,
    pub customer_phone: Option<String>,
    pub notes: Option<String>,
}

#[derive(Debug, Default)]
struct RecorderState {
    active_run_id: Option<i64>,
    pending_metadata: PendingRunMetadata,
    last_frame_ts_ms: Option<i64>,
    /// Set after a synthetic operator-stop idle frame closes a run so stale
    /// in-flight Recording frames cannot immediately open a second run.
    open_blocked_until_non_recording: bool,
    /// Rolling window of recent frames kept regardless of run state.
    /// Drained into DB at the moment a new run opens so the chart has context
    /// before the official recording start.
    pre_run_buffer: VecDeque<LiveFrame>,
}

enum Command {
    RecordFrame {
        frame: LiveFrame,
        reply: oneshot::Sender<anyhow::Result<()>>,
    },
    ListRecentRuns {
        limit: usize,
        search: Option<String>,
        reply: oneshot::Sender<anyhow::Result<Vec<StoredRun>>>,
    },
    SetPendingRunMetadata {
        metadata: PendingRunMetadata,
        reply: oneshot::Sender<anyhow::Result<()>>,
    },
    FetchRun {
        run_id: i64,
        reply: oneshot::Sender<anyhow::Result<Option<StoredRun>>>,
    },
    FetchFrames {
        run_id: i64,
        reply: oneshot::Sender<anyhow::Result<Vec<StoredFrame>>>,
    },
    FetchActiveCalibration {
        reply: oneshot::Sender<anyhow::Result<Option<CalibrationProfile>>>,
    },
    FetchCalibrationProfile {
        profile_id: i64,
        reply: oneshot::Sender<anyhow::Result<Option<CalibrationProfile>>>,
    },
    CreateCalibrationProfile {
        input: CalibrationProfileInput,
        activate_after_save: bool,
        reply: oneshot::Sender<anyhow::Result<CalibrationProfileChange>>,
    },
    UpdateCalibrationProfile {
        profile_id: i64,
        input: CalibrationProfileInput,
        activate_after_save: bool,
        reply: oneshot::Sender<anyhow::Result<Option<CalibrationProfileChange>>>,
    },
    DuplicateCalibrationProfile {
        profile_id: i64,
        duplicate_name: Option<String>,
        activate_after_save: bool,
        reply: oneshot::Sender<anyhow::Result<Option<CalibrationProfileChange>>>,
    },
    ListCalibrationProfiles {
        reply: oneshot::Sender<anyhow::Result<Vec<CalibrationProfile>>>,
    },
    ListCalibrationProfileEvents {
        profile_id: i64,
        reply: oneshot::Sender<anyhow::Result<Vec<CalibrationProfileEvent>>>,
    },
    SetActiveCalibration {
        profile_id: i64,
        reply: oneshot::Sender<anyhow::Result<bool>>,
    },
    DeleteRun {
        run_id: i64,
        reply: oneshot::Sender<anyhow::Result<bool>>,
    },
    UpdateRunMetadata {
        run_id: i64,
        metadata: PendingRunMetadata,
        reply: oneshot::Sender<anyhow::Result<Option<StoredRun>>>,
    },
    InsertAuditRecord {
        event: AuditEvent,
        profile_id: Option<i64>,
        snapshot: serde_json::Value,
        reply: oneshot::Sender<anyhow::Result<()>>,
    },
    ListAuditRecords {
        reply: oneshot::Sender<anyhow::Result<Vec<AuditRecord>>>,
    },
    GetPeakValuesForRuns {
        run_ids: Vec<i64>,
        reply: oneshot::Sender<anyhow::Result<Vec<(i64, f64, f64, Option<f64>)>>>,
    },
    GetSetting {
        key: String,
        reply: oneshot::Sender<anyhow::Result<Option<String>>>,
    },
    SetSetting {
        key: String,
        value: String,
        reply: oneshot::Sender<anyhow::Result<()>>,
    },
    SetSettingIfAbsent {
        key: String,
        value: String,
        reply: oneshot::Sender<anyhow::Result<bool>>,
    },
    Flush {
        reply: oneshot::Sender<anyhow::Result<()>>,
    },
}

impl Storage {
    /// Open the database, apply schema, and start the blocking SQLite worker.
    pub async fn open(config: &Config) -> anyhow::Result<Self> {
        let (tx, rx) = mpsc::channel(STORAGE_QUEUE_CAPACITY);
        let (init_tx, init_rx) = oneshot::channel();
        let db_path = PathBuf::from(&config.db_path);
        let recording = RecordingConfig::from_config(config);
        let bootstrap_profile = CalibrationProfile::bootstrap_default(config, current_time_ms());

        tokio::task::spawn_blocking(move || {
            storage_worker(db_path, recording, bootstrap_profile, rx, init_tx)
        });

        init_rx
            .await
            .context("storage worker init response dropped")??;

        Ok(Self { tx })
    }

    pub async fn record_live_frame(&self, frame: LiveFrame) -> anyhow::Result<()> {
        let (reply_tx, reply_rx) = oneshot::channel();
        self.tx
            .send(Command::RecordFrame {
                frame,
                reply: reply_tx,
            })
            .await
            .map_err(|_| anyhow!("storage worker is not running"))?;
        reply_rx
            .await
            .context("storage worker dropped frame reply")?
    }

    pub async fn list_runs(&self) -> anyhow::Result<Vec<RunSummary>> {
        let runs = self.list_recent_runs(DEFAULT_LIST_LIMIT).await?;
        Ok(runs.into_iter().map(stored_run_to_summary).collect())
    }

    pub async fn list_recent_runs(&self, limit: usize) -> anyhow::Result<Vec<StoredRun>> {
        self.search_recent_runs(None, limit).await
    }

    /// List recent runs, optionally filtered by a case-insensitive substring
    /// match on license plate, customer name, or customer phone.
    pub async fn search_recent_runs(
        &self,
        search: Option<String>,
        limit: usize,
    ) -> anyhow::Result<Vec<StoredRun>> {
        let (reply_tx, reply_rx) = oneshot::channel();
        self.tx
            .send(Command::ListRecentRuns {
                limit,
                search,
                reply: reply_tx,
            })
            .await
            .map_err(|_| anyhow!("storage worker is not running"))?;
        reply_rx
            .await
            .context("storage worker dropped recent-runs reply")?
    }

    pub async fn fetch_run(&self, run_id: i64) -> anyhow::Result<Option<StoredRun>> {
        let (reply_tx, reply_rx) = oneshot::channel();
        self.tx
            .send(Command::FetchRun {
                run_id,
                reply: reply_tx,
            })
            .await
            .map_err(|_| anyhow!("storage worker is not running"))?;
        reply_rx
            .await
            .context("storage worker dropped fetch-run reply")?
    }

    pub async fn fetch_frames(&self, run_id: i64) -> anyhow::Result<Vec<StoredFrame>> {
        let (reply_tx, reply_rx) = oneshot::channel();
        self.tx
            .send(Command::FetchFrames {
                run_id,
                reply: reply_tx,
            })
            .await
            .map_err(|_| anyhow!("storage worker is not running"))?;
        reply_rx
            .await
            .context("storage worker dropped fetch-frames reply")?
    }

    pub async fn fetch_active_calibration(&self) -> anyhow::Result<Option<CalibrationProfile>> {
        let (reply_tx, reply_rx) = oneshot::channel();
        self.tx
            .send(Command::FetchActiveCalibration { reply: reply_tx })
            .await
            .map_err(|_| anyhow!("storage worker is not running"))?;
        reply_rx
            .await
            .context("storage worker dropped active-calibration reply")?
    }

    pub async fn fetch_calibration_profile(
        &self,
        profile_id: i64,
    ) -> anyhow::Result<Option<CalibrationProfile>> {
        let (reply_tx, reply_rx) = oneshot::channel();
        self.tx
            .send(Command::FetchCalibrationProfile {
                profile_id,
                reply: reply_tx,
            })
            .await
            .map_err(|_| anyhow!("storage worker is not running"))?;
        reply_rx
            .await
            .context("storage worker dropped fetch-calibration reply")?
    }

    pub async fn create_calibration_profile(
        &self,
        input: CalibrationProfileInput,
        activate_after_save: bool,
    ) -> anyhow::Result<CalibrationProfileChange> {
        let (reply_tx, reply_rx) = oneshot::channel();
        self.tx
            .send(Command::CreateCalibrationProfile {
                input,
                activate_after_save,
                reply: reply_tx,
            })
            .await
            .map_err(|_| anyhow!("storage worker is not running"))?;
        reply_rx
            .await
            .context("storage worker dropped create-calibration reply")?
    }

    pub async fn update_calibration_profile(
        &self,
        profile_id: i64,
        input: CalibrationProfileInput,
        activate_after_save: bool,
    ) -> anyhow::Result<Option<CalibrationProfileChange>> {
        let (reply_tx, reply_rx) = oneshot::channel();
        self.tx
            .send(Command::UpdateCalibrationProfile {
                profile_id,
                input,
                activate_after_save,
                reply: reply_tx,
            })
            .await
            .map_err(|_| anyhow!("storage worker is not running"))?;
        reply_rx
            .await
            .context("storage worker dropped update-calibration reply")?
    }

    pub async fn duplicate_calibration_profile(
        &self,
        profile_id: i64,
        duplicate_name: Option<String>,
        activate_after_save: bool,
    ) -> anyhow::Result<Option<CalibrationProfileChange>> {
        let (reply_tx, reply_rx) = oneshot::channel();
        self.tx
            .send(Command::DuplicateCalibrationProfile {
                profile_id,
                duplicate_name,
                activate_after_save,
                reply: reply_tx,
            })
            .await
            .map_err(|_| anyhow!("storage worker is not running"))?;
        reply_rx
            .await
            .context("storage worker dropped duplicate-calibration reply")?
    }

    pub async fn list_calibration_profiles(&self) -> anyhow::Result<Vec<CalibrationProfile>> {
        let (reply_tx, reply_rx) = oneshot::channel();
        self.tx
            .send(Command::ListCalibrationProfiles { reply: reply_tx })
            .await
            .map_err(|_| anyhow!("storage worker is not running"))?;
        reply_rx
            .await
            .context("storage worker dropped list-calibration reply")?
    }

    pub async fn list_calibration_profile_events(
        &self,
        profile_id: i64,
    ) -> anyhow::Result<Vec<CalibrationProfileEvent>> {
        let (reply_tx, reply_rx) = oneshot::channel();
        self.tx
            .send(Command::ListCalibrationProfileEvents {
                profile_id,
                reply: reply_tx,
            })
            .await
            .map_err(|_| anyhow!("storage worker is not running"))?;
        reply_rx
            .await
            .context("storage worker dropped list-calibration-events reply")?
    }

    pub async fn set_active_calibration(&self, profile_id: i64) -> anyhow::Result<bool> {
        let (reply_tx, reply_rx) = oneshot::channel();
        self.tx
            .send(Command::SetActiveCalibration {
                profile_id,
                reply: reply_tx,
            })
            .await
            .map_err(|_| anyhow!("storage worker is not running"))?;
        reply_rx
            .await
            .context("storage worker dropped set-active-calibration reply")?
    }

    pub async fn update_run_metadata(
        &self,
        run_id: i64,
        metadata: PendingRunMetadata,
    ) -> anyhow::Result<Option<StoredRun>> {
        let (reply_tx, reply_rx) = oneshot::channel();
        self.tx
            .send(Command::UpdateRunMetadata {
                run_id,
                metadata,
                reply: reply_tx,
            })
            .await
            .map_err(|_| anyhow!("storage worker is not running"))?;
        reply_rx
            .await
            .context("storage worker dropped update-run-metadata reply")?
    }

    /// Stage operator-entered run metadata; it is stamped onto the next run
    /// the recorder opens.
    pub async fn set_pending_run_metadata(
        &self,
        metadata: PendingRunMetadata,
    ) -> anyhow::Result<()> {
        let (reply_tx, reply_rx) = oneshot::channel();
        self.tx
            .send(Command::SetPendingRunMetadata {
                metadata,
                reply: reply_tx,
            })
            .await
            .map_err(|_| anyhow!("storage worker is not running"))?;
        reply_rx
            .await
            .context("storage worker dropped set-pending-run-metadata reply")?
    }

    pub async fn delete_run(&self, run_id: i64) -> anyhow::Result<bool> {
        let (reply_tx, reply_rx) = oneshot::channel();
        self.tx
            .send(Command::DeleteRun {
                run_id,
                reply: reply_tx,
            })
            .await
            .map_err(|_| anyhow!("storage worker is not running"))?;
        reply_rx
            .await
            .context("storage worker dropped delete-run reply")?
    }

    pub async fn insert_audit_record(
        &self,
        event: AuditEvent,
        profile_id: Option<i64>,
        snapshot: serde_json::Value,
    ) -> anyhow::Result<()> {
        let (reply_tx, reply_rx) = oneshot::channel();
        self.tx
            .send(Command::InsertAuditRecord {
                event,
                profile_id,
                snapshot,
                reply: reply_tx,
            })
            .await
            .map_err(|_| anyhow!("storage worker is not running"))?;
        reply_rx
            .await
            .context("storage worker dropped insert-audit-record reply")?
    }

    pub async fn list_audit_records(&self) -> anyhow::Result<Vec<AuditRecord>> {
        let (reply_tx, reply_rx) = oneshot::channel();
        self.tx
            .send(Command::ListAuditRecords { reply: reply_tx })
            .await
            .map_err(|_| anyhow!("storage worker is not running"))?;
        reply_rx
            .await
            .context("storage worker dropped list-audit-records reply")?
    }

    pub async fn get_peak_values_for_runs(
        &self,
        run_ids: &[i64],
    ) -> anyhow::Result<Vec<(i64, f64, f64, Option<f64>)>> {
        let (reply_tx, reply_rx) = oneshot::channel();
        self.tx
            .send(Command::GetPeakValuesForRuns {
                run_ids: run_ids.to_vec(),
                reply: reply_tx,
            })
            .await
            .map_err(|_| anyhow!("storage worker is not running"))?;
        reply_rx
            .await
            .context("storage worker dropped peak-values reply")?
    }

    pub async fn get_setting(&self, key: &str) -> anyhow::Result<Option<String>> {
        let (reply_tx, reply_rx) = oneshot::channel();
        self.tx
            .send(Command::GetSetting {
                key: key.to_owned(),
                reply: reply_tx,
            })
            .await
            .map_err(|_| anyhow!("storage worker is not running"))?;
        reply_rx
            .await
            .context("storage worker dropped get-setting reply")?
    }

    pub async fn set_setting(&self, key: &str, value: &str) -> anyhow::Result<()> {
        let (reply_tx, reply_rx) = oneshot::channel();
        self.tx
            .send(Command::SetSetting {
                key: key.to_owned(),
                value: value.to_owned(),
                reply: reply_tx,
            })
            .await
            .map_err(|_| anyhow!("storage worker is not running"))?;
        reply_rx
            .await
            .context("storage worker dropped set-setting reply")?
    }

    /// Returns `None` if no password has been set yet (first-boot state).
    pub async fn get_system_password(&self) -> anyhow::Result<Option<String>> {
        self.get_setting("system_password").await
    }

    pub async fn is_system_password_set(&self) -> anyhow::Result<bool> {
        Ok(self.get_system_password().await?.is_some())
    }

    pub async fn set_system_password(&self, new_password: &str) -> anyhow::Result<()> {
        self.set_setting("system_password", new_password).await
    }

    /// Atomically set `key` to `value` only if unset. Returns `true` if this
    /// call set it, `false` if it was already set (left untouched).
    pub async fn set_setting_if_absent(&self, key: &str, value: &str) -> anyhow::Result<bool> {
        let (reply_tx, reply_rx) = oneshot::channel();
        self.tx
            .send(Command::SetSettingIfAbsent {
                key: key.to_owned(),
                value: value.to_owned(),
                reply: reply_tx,
            })
            .await
            .map_err(|_| anyhow!("storage worker is not running"))?;
        reply_rx
            .await
            .context("storage worker dropped set-setting-if-absent reply")?
    }

    /// Set the system password only on first setup. Returns `true` if this
    /// call set the password, `false` if one was already set — avoids the
    /// check-then-set race between `is_system_password_set` and
    /// `set_system_password`.
    pub async fn set_system_password_if_absent(&self, new_password: &str) -> anyhow::Result<bool> {
        self.set_setting_if_absent("system_password", new_password)
            .await
    }

    /// Operator-selected serial device the backend reads ESP telemetry from.
    /// `None` means unset (fall back to autodetect / env).
    pub async fn get_read_serial_port(&self) -> anyhow::Result<Option<String>> {
        self.get_setting("read_serial_port").await
    }

    pub async fn set_read_serial_port(&self, path: &str) -> anyhow::Result<()> {
        self.set_setting("read_serial_port", path).await
    }

    /// Operator-selected serial device used to flash ESP firmware.
    pub async fn get_flash_serial_port(&self) -> anyhow::Result<Option<String>> {
        self.get_setting("flash_serial_port").await
    }

    pub async fn set_flash_serial_port(&self, path: &str) -> anyhow::Result<()> {
        self.set_setting("flash_serial_port", path).await
    }

    pub async fn flush(&self) -> anyhow::Result<()> {
        let (reply_tx, reply_rx) = oneshot::channel();
        self.tx
            .send(Command::Flush { reply: reply_tx })
            .await
            .map_err(|_| anyhow!("storage worker is not running"))?;
        reply_rx
            .await
            .context("storage worker dropped flush reply")?
    }
}

impl StorageTask {
    pub fn spawn(storage: Storage, mut rx: watch::Receiver<LiveFrame>) -> Self {
        let handle = tokio::spawn(async move {
            loop {
                match rx.changed().await {
                    Ok(()) => {
                        let frame = rx.borrow().clone();
                        if let Err(err) = storage.record_live_frame(frame).await {
                            warn!("storage: failed to persist live frame: {err:#}");
                        }
                    }
                    Err(_) => {
                        if let Err(err) = storage.flush().await {
                            warn!("storage: flush on shutdown failed: {err:#}");
                        }
                        info!("storage: live frame channel closed - task stopping");
                        return;
                    }
                }
            }
        });

        Self { handle }
    }
}

impl Drop for StorageTask {
    fn drop(&mut self) {
        self.handle.abort();
    }
}

impl RecordingConfig {
    fn from_config(config: &Config) -> Self {
        Self {
            source_mode: config.source_mode,
            correction_mode: config.correction_mode,
        }
    }
}

fn storage_worker(
    db_path: PathBuf,
    recording: RecordingConfig,
    bootstrap_profile: CalibrationProfile,
    mut rx: mpsc::Receiver<Command>,
    init_tx: oneshot::Sender<anyhow::Result<()>>,
) {
    let init_result = open_connection(&db_path).and_then(|conn| {
        conn.execute_batch(SCHEMA_SQL)
            .context("failed to apply SQLite schema")?;
        apply_storage_migrations(&conn)?;
        initialize_default_calibration_profile(&conn, &bootstrap_profile)?;
        initialize_system_password_from_env(&conn)?;
        Ok(conn)
    });

    let mut conn = match init_result {
        Ok(conn) => {
            let _ = init_tx.send(Ok(()));
            info!("storage: SQLite ready at {}", db_path.display());
            conn
        }
        Err(err) => {
            let _ = init_tx.send(Err(err));
            return;
        }
    };

    let mut state = RecorderState::default();

    while let Some(command) = rx.blocking_recv() {
        match command {
            Command::RecordFrame { frame, reply } => {
                let result = handle_live_frame(&mut conn, &recording, &mut state, &frame);
                let _ = reply.send(result);
            }
            Command::ListRecentRuns { limit, search, reply } => {
                let _ = reply.send(query_recent_runs(&conn, search.as_deref(), limit));
            }
            Command::SetPendingRunMetadata { metadata, reply } => {
                state.pending_metadata = metadata;
                let _ = reply.send(Ok(()));
            }
            Command::FetchRun { run_id, reply } => {
                let _ = reply.send(query_run(&conn, run_id));
            }
            Command::FetchFrames { run_id, reply } => {
                let _ = reply.send(query_frames(&conn, run_id));
            }
            Command::FetchActiveCalibration { reply } => {
                let _ = reply.send(fetch_active_calibration_profile(&conn));
            }
            Command::FetchCalibrationProfile { profile_id, reply } => {
                let _ = reply.send(fetch_calibration_profile(&conn, profile_id));
            }
            Command::CreateCalibrationProfile {
                input,
                activate_after_save,
                reply,
            } => {
                let _ = reply.send(create_calibration_profile(&mut conn, input, activate_after_save));
            }
            Command::UpdateCalibrationProfile {
                profile_id,
                input,
                activate_after_save,
                reply,
            } => {
                let _ = reply.send(update_calibration_profile(
                    &mut conn,
                    profile_id,
                    input,
                    activate_after_save,
                ));
            }
            Command::DuplicateCalibrationProfile {
                profile_id,
                duplicate_name,
                activate_after_save,
                reply,
            } => {
                let _ = reply.send(duplicate_calibration_profile(
                    &mut conn,
                    profile_id,
                    duplicate_name,
                    activate_after_save,
                ));
            }
            Command::ListCalibrationProfiles { reply } => {
                let _ = reply.send(list_calibration_profiles(&conn));
            }
            Command::ListCalibrationProfileEvents { profile_id, reply } => {
                let _ = reply.send(list_calibration_profile_events(&conn, profile_id));
            }
            Command::SetActiveCalibration { profile_id, reply } => {
                let _ = reply.send(set_active_calibration_profile(&mut conn, profile_id));
            }
            Command::DeleteRun { run_id, reply } => {
                let _ = reply.send(delete_run(&conn, run_id));
            }
            Command::UpdateRunMetadata {
                run_id,
                metadata,
                reply,
            } => {
                let _ = reply.send(db_update_run_metadata(&conn, run_id, &metadata));
            }
            Command::InsertAuditRecord {
                event,
                profile_id,
                snapshot,
                reply,
            } => {
                let _ = reply.send(db_insert_audit_record(&conn, event, profile_id, snapshot));
            }
            Command::ListAuditRecords { reply } => {
                let _ = reply.send(db_list_audit_records(&conn));
            }
            Command::GetPeakValuesForRuns { run_ids, reply } => {
                let _ = reply.send(db_get_peak_values_for_runs(&conn, &run_ids));
            }
            Command::GetSetting { key, reply } => {
                let _ = reply.send(db_get_setting(&conn, &key));
            }
            Command::SetSetting { key, value, reply } => {
                let _ = reply.send(db_set_setting(&conn, &key, &value));
            }
            Command::SetSettingIfAbsent { key, value, reply } => {
                let _ = reply.send(db_set_setting_if_absent(&conn, &key, &value));
            }
            Command::Flush { reply } => {
                let _ = reply.send(Ok(()));
            }
        }
    }

    if let Some(run_id) = state.active_run_id {
        let ended_at_ms = current_time_ms();
        if let Err(err) = close_run(&conn, run_id, ended_at_ms) {
            warn!("storage: failed to close active run {run_id} during shutdown: {err:#}");
        }
    }
}

fn open_connection(db_path: &Path) -> anyhow::Result<Connection> {
    if let Some(parent) = db_path.parent() {
        if !parent.as_os_str().is_empty() {
            fs::create_dir_all(parent)
                .with_context(|| format!("failed to create database directory {}", parent.display()))?;
        }
    }

    let conn = Connection::open(db_path)
        .with_context(|| format!("failed to open SQLite database {}", db_path.display()))?;
    conn.busy_timeout(Duration::from_secs(1))
        .context("failed to configure SQLite busy timeout")?;
    conn.execute_batch(
        r#"
        PRAGMA foreign_keys = ON;
        PRAGMA journal_mode = WAL;
        PRAGMA synchronous = NORMAL;
        "#,
    )
    .context("failed to apply SQLite pragmas")?;
    Ok(conn)
}

fn apply_storage_migrations(conn: &Connection) -> anyhow::Result<()> {
    ensure_column_exists(conn, "calibration_profiles", "engine_stroke", "INTEGER NULL")?;
    ensure_column_exists(conn, "calibration_profiles", "engine_cylinders", "INTEGER NULL")?;
    ensure_column_exists(conn, "runs", "engine_pulses_per_rev_hint", "REAL NULL")?;
    ensure_column_exists(conn, "runs", "engine_rpm_scale", "REAL NULL")?;
    ensure_column_exists(conn, "runs", "engine_stroke", "INTEGER NULL")?;
    ensure_column_exists(conn, "runs", "engine_cylinders", "INTEGER NULL")?;
    ensure_column_exists(conn, "runs", "peak_power_hp", "REAL NOT NULL DEFAULT 0.0")?;
    ensure_column_exists(conn, "runs", "peak_power_rpm", "REAL NOT NULL DEFAULT 0.0")?;
    ensure_column_exists(conn, "runs", "peak_torque_nm", "REAL NOT NULL DEFAULT 0.0")?;
    ensure_column_exists(conn, "runs", "peak_torque_rpm", "REAL NOT NULL DEFAULT 0.0")?;
    ensure_column_exists(conn, "runs", "peaks_computed", "INTEGER NOT NULL DEFAULT 0")?;
    ensure_column_exists(conn, "runs", "calibration_profile_id", "INTEGER NULL")?;
    ensure_column_exists(conn, "runs", "calibration_profile_name", "TEXT NULL")?;
    ensure_column_exists(conn, "runs", "vehicle_name", "TEXT NULL")?;
    ensure_column_exists(conn, "runs", "license_plate", "TEXT NULL")?;
    let run_no_added = !table_has_column(conn, "runs", "run_no")?;
    ensure_column_exists(conn, "runs", "run_no", "INTEGER NULL")?;
    ensure_column_exists(conn, "runs", "customer_name", "TEXT NULL")?;
    ensure_column_exists(conn, "runs", "customer_phone", "TEXT NULL")?;
    ensure_column_exists(conn, "runs", "notes", "TEXT NULL")?;
    conn.execute(
        "CREATE INDEX IF NOT EXISTS idx_runs_license_plate ON runs(license_plate)",
        [],
    )
    .context("failed to create idx_runs_license_plate")?;
    if run_no_added {
        backfill_run_numbers(conn)?;
    }
    Ok(())
}

/// Assign per-plate sequence numbers to pre-existing runs, ordered by start time.
fn backfill_run_numbers(conn: &Connection) -> anyhow::Result<()> {
    conn.execute_batch(
        r#"
        UPDATE runs SET run_no = (
            SELECT COUNT(*) FROM runs r2
            WHERE r2.license_plate = runs.license_plate
              AND (r2.started_at_ms < runs.started_at_ms
                   OR (r2.started_at_ms = runs.started_at_ms AND r2.run_id <= runs.run_id))
        )
        WHERE license_plate IS NOT NULL;
        "#,
    )
    .context("failed to backfill run_no")?;
    Ok(())
}

fn ensure_column_exists(
    conn: &Connection,
    table_name: &str,
    column_name: &str,
    column_definition: &str,
) -> anyhow::Result<()> {
    if table_has_column(conn, table_name, column_name)? {
        return Ok(());
    }

    let statement =
        format!("ALTER TABLE {table_name} ADD COLUMN {column_name} {column_definition}");
    conn.execute(&statement, [])
        .with_context(|| format!("failed to add {table_name}.{column_name}"))?;
    Ok(())
}

fn table_has_column(conn: &Connection, table_name: &str, column_name: &str) -> anyhow::Result<bool> {
    let pragma = format!("PRAGMA table_info({table_name})");
    let mut stmt = conn
        .prepare(&pragma)
        .with_context(|| format!("failed to inspect table info for {table_name}"))?;
    let mut rows = stmt
        .query([])
        .with_context(|| format!("failed to query table info for {table_name}"))?;

    while let Some(row) = rows.next().context("failed to iterate table info rows")? {
        let existing_name: String = row.get(1).context("failed to read table column name")?;
        if existing_name == column_name {
            return Ok(true);
        }
    }

    Ok(false)
}

fn initialize_default_calibration_profile(
    conn: &Connection,
    bootstrap_profile: &CalibrationProfile,
) -> anyhow::Result<()> {
    let profile_count: i64 = conn
        .query_row("SELECT COUNT(*) FROM calibration_profiles", [], |row| row.get(0))
        .context("failed to count calibration profiles")?;

    if profile_count > 0 {
        return Ok(());
    }

    let profile_id = insert_calibration_profile(conn, bootstrap_profile)
        .context("failed to create bootstrap calibration profile")?;
    let stored_profile = fetch_calibration_profile(conn, profile_id)?
        .ok_or_else(|| anyhow!("bootstrap calibration profile missing after insert"))?;
    insert_calibration_profile_event(
        conn,
        stored_profile.profile_id,
        CalibrationProfileEventType::Created,
        stored_profile.created_at_ms,
        format!("Created profile {}", stored_profile.name),
        None,
        Some(profile_snapshot_json(&stored_profile)?),
    )
    .context("failed to create bootstrap calibration audit event")?;
    Ok(())
}

/// Seeds the system password from `DYNO_SYSTEM_PASSWORD` for unattended
/// installs. If unset, no password is stored — the system stays in
/// first-boot state until an operator sets one via the setup endpoint.
fn initialize_system_password_from_env(conn: &Connection) -> anyhow::Result<()> {
    if db_get_setting(conn, "system_password")?.is_none() {
        if let Some(initial) = std::env::var("DYNO_SYSTEM_PASSWORD")
            .ok()
            .filter(|value| !value.trim().is_empty())
        {
            db_set_setting(conn, "system_password", &initial)?;
        }
    }
    Ok(())
}

fn insert_calibration_profile(
    conn: &Connection,
    profile: &CalibrationProfile,
) -> anyhow::Result<i64> {
    conn.execute(
        r#"
        INSERT INTO calibration_profiles (
            name,
            created_at_ms,
            updated_at_ms,
            is_active,
            roller_diameter_m,
            encoder_pulses_per_rev,
            roller_inertia_kg_m2,
            sample_window_ms,
            engine_pulses_per_rev_hint,
            engine_rpm_scale,
            engine_stroke,
            engine_cylinders,
            notes
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)
        "#,
        params![
            &profile.name,
            profile.created_at_ms,
            profile.updated_at_ms,
            i64::from(profile.is_active as u8),
            profile.roller_diameter_m,
            profile.encoder_pulses_per_rev,
            profile.roller_inertia_kg_m2,
            i64::try_from(profile.sample_window_ms).context("sample_window_ms exceeds i64")?,
            profile.engine_pulses_per_rev_hint,
            profile.engine_rpm_scale,
            profile.engine_stroke,
            profile.engine_cylinders,
            profile.notes.as_deref(),
        ],
    )
    .context("failed to insert calibration profile")?;

    Ok(conn.last_insert_rowid())
}

fn create_calibration_profile(
    conn: &mut Connection,
    input: CalibrationProfileInput,
    activate_after_save: bool,
) -> anyhow::Result<CalibrationProfileChange> {
    let tx = conn
        .unchecked_transaction()
        .context("failed to start calibration create transaction")?;
    let normalized = input.normalized();
    let now_ms = current_time_ms();
    let previous_active = if activate_after_save {
        fetch_active_calibration_profile(&tx)?
    } else {
        None
    };

    if activate_after_save {
        tx.execute("UPDATE calibration_profiles SET is_active = 0 WHERE is_active = 1", [])
            .context("failed to clear previous active calibration profile during create")?;
    }

    let profile_id = insert_calibration_profile(
        &tx,
        &normalized
            .clone()
            .into_profile(0, now_ms, now_ms, activate_after_save),
    )
    .context("failed to create calibration profile")?;
    let profile = fetch_calibration_profile(&tx, profile_id)?
        .ok_or_else(|| anyhow!("created calibration profile missing after insert"))?;

    insert_calibration_profile_event(
        &tx,
        profile.profile_id,
        CalibrationProfileEventType::Created,
        now_ms,
        format!("Created profile {}", profile.name),
        None,
        Some(profile_snapshot_json(&profile)?),
    )
    .context("failed to record calibration create event")?;

    if activate_after_save {
        insert_calibration_profile_event(
            &tx,
            profile.profile_id,
            CalibrationProfileEventType::Activated,
            now_ms,
            format!("Activated profile {}", profile.name),
            previous_active
                .as_ref()
                .map(profile_snapshot_json)
                .transpose()?,
            Some(profile_snapshot_json(&profile)?),
        )
        .context("failed to record calibration activation event after create")?;
    }

    tx.commit()
        .context("failed to commit calibration create transaction")?;

    Ok(CalibrationProfileChange {
        profile,
        activated: activate_after_save,
    })
}

fn update_calibration_profile(
    conn: &mut Connection,
    profile_id: i64,
    input: CalibrationProfileInput,
    activate_after_save: bool,
) -> anyhow::Result<Option<CalibrationProfileChange>> {
    let tx = conn
        .unchecked_transaction()
        .context("failed to start calibration update transaction")?;
    let Some(existing_profile) = fetch_calibration_profile(&tx, profile_id)? else {
        tx.rollback()
            .context("failed to roll back missing calibration update")?;
        return Ok(None);
    };

    let normalized = input.normalized();
    let now_ms = current_time_ms();
    let should_activate = activate_after_save && !existing_profile.is_active;
    let previous_active = if should_activate {
        fetch_active_calibration_profile(&tx)?
    } else {
        None
    };

    if should_activate {
        tx.execute("UPDATE calibration_profiles SET is_active = 0 WHERE is_active = 1", [])
            .context("failed to clear previous active calibration profile during update")?;
    }

    let new_is_active = existing_profile.is_active || activate_after_save;
    tx.execute(
        r#"
        UPDATE calibration_profiles
        SET
            name = ?1,
            updated_at_ms = ?2,
            is_active = ?3,
            roller_diameter_m = ?4,
            encoder_pulses_per_rev = ?5,
            roller_inertia_kg_m2 = ?6,
            sample_window_ms = ?7,
            engine_pulses_per_rev_hint = ?8,
            engine_rpm_scale = ?9,
            engine_stroke = ?10,
            engine_cylinders = ?11,
            notes = ?12
        WHERE profile_id = ?13
        "#,
        params![
            &normalized.name,
            now_ms,
            i64::from(new_is_active as u8),
            normalized.roller_diameter_m,
            normalized.encoder_pulses_per_rev,
            normalized.roller_inertia_kg_m2,
            i64::try_from(normalized.sample_window_ms).context("sample_window_ms exceeds i64")?,
            normalized.engine_pulses_per_rev_hint,
            normalized.engine_rpm_scale,
            normalized.engine_stroke,
            normalized.engine_cylinders,
            normalized.notes.as_deref(),
            profile_id,
        ],
    )
    .with_context(|| format!("failed to update calibration profile {profile_id}"))?;

    let profile = fetch_calibration_profile(&tx, profile_id)?
        .ok_or_else(|| anyhow!("updated calibration profile missing after update"))?;

    insert_calibration_profile_event(
        &tx,
        profile.profile_id,
        CalibrationProfileEventType::Updated,
        now_ms,
        format!("Updated profile {}", profile.name),
        Some(profile_snapshot_json(&existing_profile)?),
        Some(profile_snapshot_json(&profile)?),
    )
    .context("failed to record calibration update event")?;

    if should_activate {
        insert_calibration_profile_event(
            &tx,
            profile.profile_id,
            CalibrationProfileEventType::Activated,
            now_ms,
            format!("Activated profile {}", profile.name),
            previous_active
                .as_ref()
                .map(profile_snapshot_json)
                .transpose()?,
            Some(profile_snapshot_json(&profile)?),
        )
        .context("failed to record calibration activation event after update")?;
    }

    tx.commit()
        .context("failed to commit calibration update transaction")?;

    Ok(Some(CalibrationProfileChange {
        profile,
        activated: should_activate,
    }))
}

fn duplicate_calibration_profile(
    conn: &mut Connection,
    profile_id: i64,
    duplicate_name: Option<String>,
    activate_after_save: bool,
) -> anyhow::Result<Option<CalibrationProfileChange>> {
    let tx = conn
        .unchecked_transaction()
        .context("failed to start calibration duplicate transaction")?;
    let Some(source_profile) = fetch_calibration_profile(&tx, profile_id)? else {
        tx.rollback()
            .context("failed to roll back missing calibration duplicate")?;
        return Ok(None);
    };

    let now_ms = current_time_ms();
    let previous_active = if activate_after_save {
        fetch_active_calibration_profile(&tx)?
    } else {
        None
    };

    if activate_after_save {
        tx.execute("UPDATE calibration_profiles SET is_active = 0 WHERE is_active = 1", [])
            .context("failed to clear previous active calibration profile during duplicate")?;
    }

    let requested_name = duplicate_name
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(ToOwned::to_owned);
    let name = match requested_name {
        Some(name) => name,
        None => next_duplicate_profile_name(&tx, &source_profile.name)?,
    };
    let duplicate_input = CalibrationProfileInput {
        name,
        roller_diameter_m: source_profile.roller_diameter_m,
        encoder_pulses_per_rev: source_profile.encoder_pulses_per_rev,
        roller_inertia_kg_m2: source_profile.roller_inertia_kg_m2,
        sample_window_ms: source_profile.sample_window_ms,
        engine_pulses_per_rev_hint: source_profile.engine_pulses_per_rev_hint,
        engine_rpm_scale: source_profile.engine_rpm_scale,
        engine_stroke: source_profile.engine_stroke,
        engine_cylinders: source_profile.engine_cylinders,
        notes: source_profile.notes.clone(),
    };

    let duplicated_profile_id = insert_calibration_profile(
        &tx,
        &duplicate_input
            .into_profile(0, now_ms, now_ms, activate_after_save),
    )
    .context("failed to create duplicated calibration profile")?;
    let duplicated_profile = fetch_calibration_profile(&tx, duplicated_profile_id)?
        .ok_or_else(|| anyhow!("duplicated calibration profile missing after insert"))?;

    insert_calibration_profile_event(
        &tx,
        duplicated_profile.profile_id,
        CalibrationProfileEventType::Duplicated,
        now_ms,
        format!(
            "Duplicated profile {} into {}",
            source_profile.name, duplicated_profile.name
        ),
        Some(profile_snapshot_json(&source_profile)?),
        Some(profile_snapshot_json(&duplicated_profile)?),
    )
    .context("failed to record calibration duplicate event")?;

    if activate_after_save {
        insert_calibration_profile_event(
            &tx,
            duplicated_profile.profile_id,
            CalibrationProfileEventType::Activated,
            now_ms,
            format!("Activated profile {}", duplicated_profile.name),
            previous_active
                .as_ref()
                .map(profile_snapshot_json)
                .transpose()?,
            Some(profile_snapshot_json(&duplicated_profile)?),
        )
        .context("failed to record calibration activation event after duplicate")?;
    }

    tx.commit()
        .context("failed to commit calibration duplicate transaction")?;

    Ok(Some(CalibrationProfileChange {
        profile: duplicated_profile,
        activated: activate_after_save,
    }))
}

fn insert_calibration_profile_event(
    conn: &Connection,
    profile_id: i64,
    event_type: CalibrationProfileEventType,
    created_at_ms: i64,
    summary: String,
    previous_values_json: Option<serde_json::Value>,
    new_values_json: Option<serde_json::Value>,
) -> anyhow::Result<i64> {
    let previous_values_json = previous_values_json
        .as_ref()
        .map(serde_json::to_string)
        .transpose()
        .context("failed to serialize previous calibration event values")?;
    let new_values_json = new_values_json
        .as_ref()
        .map(serde_json::to_string)
        .transpose()
        .context("failed to serialize new calibration event values")?;

    conn.execute(
        r#"
        INSERT INTO calibration_profile_events (
            profile_id,
            event_type,
            created_at_ms,
            summary,
            previous_values_json,
            new_values_json
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6)
        "#,
        params![
            profile_id,
            event_type.to_string(),
            created_at_ms,
            summary,
            previous_values_json.as_deref(),
            new_values_json.as_deref(),
        ],
    )
    .with_context(|| format!("failed to insert calibration event for profile {profile_id}"))?;

    Ok(conn.last_insert_rowid())
}

fn profile_snapshot_json(profile: &CalibrationProfile) -> anyhow::Result<serde_json::Value> {
    serde_json::to_value(profile).context("failed to serialize calibration profile snapshot")
}

fn next_duplicate_profile_name(conn: &Connection, base_name: &str) -> anyhow::Result<String> {
    let trimmed = base_name.trim();
    let mut suffix = 1_i64;

    loop {
        let candidate = format!("{trimmed}-{suffix}");
        let existing_count: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM calibration_profiles WHERE name = ?1",
                [&candidate],
                |row| row.get(0),
            )
            .with_context(|| format!("failed to check duplicate calibration name {candidate}"))?;
        if existing_count == 0 {
            return Ok(candidate);
        }
        suffix += 1;
    }
}

fn handle_live_frame(
    conn: &mut Connection,
    recording: &RecordingConfig,
    state: &mut RecorderState,
    frame: &LiveFrame,
) -> anyhow::Result<()> {
    let blocked_stale_recording = state.active_run_id.is_none()
        && state.open_blocked_until_non_recording
        && frame.run_state == RunState::Recording;

    // Feed the pre-run ring buffer with every frame regardless of run state.
    // The oldest entry is dropped when the buffer exceeds its fixed capacity.
    if !blocked_stale_recording {
        state.pre_run_buffer.push_back(frame.clone());
        if state.pre_run_buffer.len() > PRE_RUN_BUFFER_CAP {
            state.pre_run_buffer.pop_front();
        }
    }

    if state.active_run_id.is_none() && frame.run_state != RunState::Recording {
        state.open_blocked_until_non_recording = false;
    }

    // Track whether the current frame was already written as part of the
    // pre-run drain so we do not double-insert it via the normal append path.
    let mut drained_at_start = false;

    if state.active_run_id.is_none()
        && frame.run_state == RunState::Recording
        && !state.open_blocked_until_non_recording
    {
        let run_id = create_run(conn, recording, &state.pending_metadata)?;
        state.active_run_id = Some(run_id);
        debug!("storage: opened run {run_id} at {}", frame.ts_ms);

        // Drain the entire ring buffer (which includes the current Recording
        // frame as its last entry) and persist all frames in original timestamp
        // order as the opening context of the new run.
        let pre_run: Vec<LiveFrame> = state.pre_run_buffer.drain(..).collect();
        for pre_frame in &pre_run {
            append_frame(conn, run_id, pre_frame)?;
        }
        state.last_frame_ts_ms = pre_run.last().map(|f| f.ts_ms);
        drained_at_start = true;
    }

    if let Some(run_id) = state.active_run_id {
        match frame.run_state {
            RunState::Recording | RunState::Stopping => {
                if !drained_at_start {
                    append_frame(conn, run_id, frame)?;
                    state.last_frame_ts_ms = Some(frame.ts_ms);
                }
            }
            RunState::Armed => {}
            RunState::Idle | RunState::Fault => {
                let block_stale_recording = is_synthetic_idle_frame(frame);
                close_run(conn, run_id, frame.ts_ms)?;
                debug!("storage: closed run {run_id} at {}", frame.ts_ms);
                state.active_run_id = None;
                state.last_frame_ts_ms = None;
                state.open_blocked_until_non_recording = block_stale_recording;
                state.pre_run_buffer.clear();
            }
        }
    }

    Ok(())
}

fn is_synthetic_idle_frame(frame: &LiveFrame) -> bool {
    frame.run_state == RunState::Idle
        && frame.engine_rpm.is_none()
        && frame.roller_rpm.is_none()
        && frame.speed_kmh.is_none()
}

fn create_run(
    conn: &Connection,
    recording: &RecordingConfig,
    metadata: &PendingRunMetadata,
) -> anyhow::Result<i64> {
    let calibration = fetch_active_calibration_profile(conn)?
        .ok_or_else(|| anyhow!("active calibration profile is missing"))?;
    let started_at_ms = current_time_ms();
    let license_plate = normalize_optional_text(metadata.license_plate.as_deref())
        .map(|p| p.to_uppercase());
    conn.execute("BEGIN IMMEDIATE", [])
        .context("failed to begin run-insert transaction")?;
    let insert_result = (|| -> anyhow::Result<i64> {
        // Per-plate sequence number, allocated inside the same transaction so
        // concurrent starts cannot produce duplicates.
        let run_no: Option<i64> = match &license_plate {
            Some(plate) => Some(
                conn.query_row(
                    "SELECT COALESCE(MAX(run_no), 0) + 1 FROM runs WHERE license_plate = ?1",
                    params![plate],
                    |row| row.get(0),
                )
                .context("failed to allocate run_no")?,
            ),
            None => None,
        };
        conn.execute(
            r#"
            INSERT INTO runs (
                started_at_ms,
                ended_at_ms,
                source_mode,
                correction_mode,
                calibration_profile_id,
                calibration_profile_name,
                roller_diameter_m,
                encoder_pulses_per_rev,
                roller_inertia_kg_m2,
                sample_window_ms,
                engine_pulses_per_rev_hint,
                engine_rpm_scale,
                engine_stroke,
                engine_cylinders,
                vehicle_name,
                license_plate,
                run_no,
                customer_name,
                customer_phone,
                notes
            ) VALUES (?1, NULL, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19)
            "#,
            params![
                started_at_ms,
                recording.source_mode.to_string(),
                recording.correction_mode.to_string(),
                calibration.profile_id,
                calibration.name,
                calibration.roller_diameter_m,
                calibration.encoder_pulses_per_rev,
                calibration.roller_inertia_kg_m2,
                i64::try_from(calibration.sample_window_ms).context("sample_window_ms exceeds i64")?,
                calibration.engine_pulses_per_rev_hint,
                calibration.engine_rpm_scale,
                calibration.engine_stroke,
                calibration.engine_cylinders,
                normalize_optional_text(metadata.vehicle_name.as_deref()),
                license_plate,
                run_no,
                normalize_optional_text(metadata.customer_name.as_deref()),
                normalize_optional_text(metadata.customer_phone.as_deref()),
                normalize_optional_text(metadata.notes.as_deref()),
            ],
        )
        .context("failed to insert run row")?;
        Ok(conn.last_insert_rowid())
    })();
    match insert_result {
        Ok(run_id) => {
            conn.execute("COMMIT", [])
                .context("failed to commit run-insert transaction")?;
            Ok(run_id)
        }
        Err(err) => {
            let _ = conn.execute("ROLLBACK", []);
            Err(err)
        }
    }
}

/// Trim text and collapse empty/placeholder values to NULL.
fn normalize_optional_text(value: Option<&str>) -> Option<String> {
    let trimmed = value?.trim();
    if trimmed.is_empty() || trimmed == "—" {
        None
    } else {
        Some(trimmed.to_owned())
    }
}

fn append_frame(conn: &Connection, run_id: i64, frame: &LiveFrame) -> anyhow::Result<()> {
    conn.execute(
        r#"
        INSERT INTO frames (
            run_id,
            ts_ms,
            engine_rpm,
            roller_rpm,
            speed_kmh,
            power_hp,
            torque_nm,
            afr,
            lambda,
            ambient_temp_c,
            humidity_pct,
            pressure_hpa,
            correction_factor,
            run_state
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14)
        "#,
        params![
            run_id,
            frame.ts_ms,
            frame.engine_rpm,
            frame.roller_rpm,
            frame.speed_kmh,
            frame.power_hp,
            frame.torque_nm,
            frame.afr,
            frame.lambda,
            frame.ambient_temp_c,
            frame.humidity_pct,
            frame.pressure_hpa,
            frame.correction_factor,
            frame.run_state.to_string(),
        ],
    )
    .with_context(|| format!("failed to insert frame for run {run_id}"))?;
    Ok(())
}

fn close_run(conn: &Connection, run_id: i64, ended_at_ms: i64) -> anyhow::Result<()> {
    let rows_affected = conn
        .execute(
            "UPDATE runs SET ended_at_ms = ?1 WHERE run_id = ?2 AND ended_at_ms IS NULL",
            params![ended_at_ms, run_id],
        )
        .with_context(|| format!("failed to close run {run_id}"))?;
    // Only the call that actually transitions the run to closed computes
    // peaks; a redundant close (rows_affected == 0) leaves the existing
    // value alone, and reads fall back to lazy backfill if it's missing.
    if rows_affected > 0 {
        compute_and_store_peaks(conn, run_id)
            .with_context(|| format!("failed to compute peaks for run {run_id}"))?;
    }
    Ok(())
}

/// Peak power/torque for a run, computed with the same "clean ascending
/// sweep" rule the live dashboard uses (see `compute_run_peaks`). `0.0`
/// means no frame qualified — mirrors the existing StoredRun/RunSummary
/// convention where callers treat `peak_power_hp > 0.0` as "has a peak".
#[derive(Debug, Clone, Copy, PartialEq, Default)]
struct RunPeaks {
    power_hp: f32,
    power_rpm: f32,
    torque_nm: f32,
    torque_rpm: f32,
}

/// Port of the dashboard's live peak-tracking rule
/// (`TelemetryPresenter.appendChartPoint`) applied to a run's full,
/// already-finalized frame history. A frame contributes only if: it was
/// recorded while the run state was `RECORDING` (matches the dashboard,
/// which resets peaks on entering RECORDING and never accumulates outside
/// it); engine RPM, power, and torque are all present; power and torque are
/// non-negative; and engine RPM is monotonically non-decreasing versus the
/// last *accepted* frame (drops the decel/coast-down tail so only the
/// ascending pull counts).
fn compute_run_peaks(frames: &[StoredFrame]) -> RunPeaks {
    let mut peaks = RunPeaks::default();
    let mut last_accepted_rpm: Option<f32> = None;

    for frame in frames {
        if frame.run_state != RunState::Recording {
            continue;
        }
        let (Some(rpm), Some(power), Some(torque)) =
            (frame.engine_rpm, frame.power_hp, frame.torque_nm)
        else {
            continue;
        };
        if power < 0.0 || torque < 0.0 {
            continue;
        }
        if let Some(prev) = last_accepted_rpm {
            if rpm < prev {
                continue;
            }
        }
        last_accepted_rpm = Some(rpm);

        if power > peaks.power_hp {
            peaks.power_hp = power;
            peaks.power_rpm = rpm;
        }
        if torque > peaks.torque_nm {
            peaks.torque_nm = torque;
            peaks.torque_rpm = rpm;
        }
    }

    peaks
}

/// Compute peaks over a run's stored frames and persist them on the `runs`
/// row, marking it as computed so later reads don't recompute.
fn compute_and_store_peaks(conn: &Connection, run_id: i64) -> anyhow::Result<RunPeaks> {
    let frames = query_frames(conn, run_id)?;
    let peaks = compute_run_peaks(&frames);
    conn.execute(
        r#"
        UPDATE runs SET
            peak_power_hp = ?1,
            peak_power_rpm = ?2,
            peak_torque_nm = ?3,
            peak_torque_rpm = ?4,
            peaks_computed = 1
        WHERE run_id = ?5
        "#,
        params![
            peaks.power_hp,
            peaks.power_rpm,
            peaks.torque_nm,
            peaks.torque_rpm,
            run_id,
        ],
    )
    .with_context(|| format!("failed to persist peaks for run {run_id}"))?;
    Ok(peaks)
}

/// Ensure a finalized run has computed peaks, backfilling lazily on first
/// access. Runs that are still active (`ended_at_ms IS NULL`) are left
/// alone — their peaks are computed once when the run closes.
fn backfill_run_peaks(conn: &Connection, runs: &mut [StoredRun], peaks_computed: &[bool]) -> anyhow::Result<()> {
    for (run, &computed) in runs.iter_mut().zip(peaks_computed.iter()) {
        if computed || run.ended_at_ms.is_none() {
            continue;
        }
        let peaks = compute_and_store_peaks(conn, run.run_id)?;
        run.peak_power_hp = peaks.power_hp;
        run.peak_power_rpm = peaks.power_rpm;
        run.peak_torque_nm = peaks.torque_nm;
        run.peak_torque_rpm = peaks.torque_rpm;
    }
    Ok(())
}

fn query_recent_runs(
    conn: &Connection,
    search: Option<&str>,
    limit: usize,
) -> anyhow::Result<Vec<StoredRun>> {
    let mut stmt = conn
        .prepare(
            r#"
            SELECT
                r.run_id,
                r.started_at_ms,
                r.ended_at_ms,
                r.source_mode,
                r.correction_mode,
                r.calibration_profile_id,
                r.calibration_profile_name,
                r.roller_diameter_m,
                r.encoder_pulses_per_rev,
                r.roller_inertia_kg_m2,
                r.sample_window_ms,
                r.engine_pulses_per_rev_hint,
                r.engine_rpm_scale,
                r.engine_stroke,
                r.engine_cylinders,
                r.peak_power_hp,
                r.peak_power_rpm,
                r.peak_torque_nm,
                r.peak_torque_rpm,
                r.vehicle_name,
                r.license_plate,
                r.run_no,
                r.customer_name,
                r.customer_phone,
                r.notes,
                r.peaks_computed
            FROM runs r
            WHERE ?1 IS NULL
               OR instr(upper(COALESCE(r.license_plate, '')), upper(?1)) > 0
               OR instr(upper(COALESCE(r.customer_name, '')), upper(?1)) > 0
               OR instr(upper(COALESCE(r.customer_phone, '')), upper(?1)) > 0
            ORDER BY r.started_at_ms DESC, r.run_id DESC
            LIMIT ?2
            "#,
        )
        .context("failed to prepare recent-runs query")?;

    let search = search.map(str::trim).filter(|s| !s.is_empty());
    let rows = stmt
        .query_map(params![search, limit as i64], map_stored_run_row_with_peaks_flag)
        .context("failed to execute recent-runs query")?;

    let mut runs = Vec::new();
    let mut peaks_computed = Vec::new();
    for row in rows {
        let (run, computed) = row.context("failed to map recent-runs row")?;
        runs.push(run);
        peaks_computed.push(computed);
    }
    backfill_run_peaks(conn, &mut runs, &peaks_computed)?;
    Ok(runs)
}

fn query_run(conn: &Connection, run_id: i64) -> anyhow::Result<Option<StoredRun>> {
    let mut stmt = conn
        .prepare(
            r#"
            SELECT
                r.run_id,
                r.started_at_ms,
                r.ended_at_ms,
                r.source_mode,
                r.correction_mode,
                r.calibration_profile_id,
                r.calibration_profile_name,
                r.roller_diameter_m,
                r.encoder_pulses_per_rev,
                r.roller_inertia_kg_m2,
                r.sample_window_ms,
                r.engine_pulses_per_rev_hint,
                r.engine_rpm_scale,
                r.engine_stroke,
                r.engine_cylinders,
                r.peak_power_hp,
                r.peak_power_rpm,
                r.peak_torque_nm,
                r.peak_torque_rpm,
                r.vehicle_name,
                r.license_plate,
                r.run_no,
                r.customer_name,
                r.customer_phone,
                r.notes,
                r.peaks_computed
            FROM runs r
            WHERE r.run_id = ?1
            "#,
        )
        .with_context(|| format!("failed to prepare run query for run {run_id}"))?;

    let mapped = stmt
        .query_row([run_id], map_stored_run_row_with_peaks_flag)
        .optional()
        .with_context(|| format!("failed to execute run query for run {run_id}"))?;

    match mapped {
        Some((mut run, computed)) => {
            let peaks_computed = [computed];
            backfill_run_peaks(conn, std::slice::from_mut(&mut run), &peaks_computed)?;
            Ok(Some(run))
        }
        None => Ok(None),
    }
}

fn map_stored_run_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<StoredRun> {
    let source_mode: String = row.get(3)?;
    let correction_mode: String = row.get(4)?;

    Ok(StoredRun {
        run_id: row.get(0)?,
        started_at_ms: row.get(1)?,
        ended_at_ms: row.get(2)?,
        source_mode: parse_source_mode(&source_mode)?,
        correction_mode: parse_correction_mode(&correction_mode)?,
        calibration_profile_id: row.get(5)?,
        calibration_profile_name: row.get(6)?,
        roller_diameter_m: row.get(7)?,
        encoder_pulses_per_rev: row.get(8)?,
        roller_inertia_kg_m2: row.get(9)?,
        sample_window_ms: row.get(10)?,
        engine_pulses_per_rev_hint: row.get(11)?,
        engine_rpm_scale: row.get(12)?,
        engine_stroke: row.get(13)?,
        engine_cylinders: row.get(14)?,
        peak_power_hp: row.get(15)?,
        peak_power_rpm: row.get(16)?,
        peak_torque_nm: row.get(17)?,
        peak_torque_rpm: row.get(18)?,
        vehicle_name: row.get(19)?,
        license_plate: row.get(20)?,
        run_no: row.get(21)?,
        customer_name: row.get(22)?,
        customer_phone: row.get(23)?,
        notes: row.get(24)?,
    })
}

/// Same column layout as `map_stored_run_row` plus a trailing
/// `peaks_computed` flag column, used by callers that need to trigger lazy
/// peak backfill.
fn map_stored_run_row_with_peaks_flag(row: &rusqlite::Row<'_>) -> rusqlite::Result<(StoredRun, bool)> {
    let run = map_stored_run_row(row)?;
    let peaks_computed: i64 = row.get(25)?;
    Ok((run, peaks_computed != 0))
}

fn query_frames(conn: &Connection, run_id: i64) -> anyhow::Result<Vec<StoredFrame>> {
    let mut stmt = conn
        .prepare(
            r#"
            SELECT
                run_id,
                ts_ms,
                engine_rpm,
                roller_rpm,
                speed_kmh,
                power_hp,
                torque_nm,
                afr,
                lambda,
                ambient_temp_c,
                humidity_pct,
                pressure_hpa,
                correction_factor,
                run_state
            FROM frames
            WHERE run_id = ?1
            ORDER BY ts_ms ASC
            "#,
        )
        .with_context(|| format!("failed to prepare frame query for run {run_id}"))?;

    let rows = stmt
        .query_map([run_id], |row| {
            let run_state: String = row.get(13)?;
            let run_state = match run_state.as_str() {
                "idle" => RunState::Idle,
                "armed" => RunState::Armed,
                "recording" => RunState::Recording,
                "stopping" => RunState::Stopping,
                "fault" => RunState::Fault,
                _ => return Err(rusqlite::Error::InvalidQuery),
            };
            Ok(StoredFrame {
                run_id: row.get(0)?,
                ts_ms: row.get(1)?,
                engine_rpm: row.get(2)?,
                roller_rpm: row.get(3)?,
                speed_kmh: row.get(4)?,
                power_hp: row.get(5)?,
                torque_nm: row.get(6)?,
                afr: row.get(7)?,
                lambda: row.get(8)?,
                ambient_temp_c: row.get(9)?,
                humidity_pct: row.get(10)?,
                pressure_hpa: row.get(11)?,
                correction_factor: row.get(12)?,
                run_state,
            })
        })
        .with_context(|| format!("failed to execute frame query for run {run_id}"))?;

    let mut frames = Vec::new();
    for row in rows {
        frames.push(row.context("failed to map stored frame row")?);
    }
    Ok(frames)
}

fn fetch_active_calibration_profile(conn: &Connection) -> anyhow::Result<Option<CalibrationProfile>> {
    let mut stmt = conn
        .prepare(
            r#"
            SELECT
                profile_id,
                name,
                created_at_ms,
                updated_at_ms,
                is_active,
                roller_diameter_m,
                encoder_pulses_per_rev,
                roller_inertia_kg_m2,
                sample_window_ms,
                engine_pulses_per_rev_hint,
                engine_rpm_scale,
                engine_stroke,
                engine_cylinders,
                notes
            FROM calibration_profiles
            WHERE is_active = 1
            ORDER BY updated_at_ms DESC, profile_id DESC
            LIMIT 1
            "#,
        )
        .context("failed to prepare active calibration query")?;

    stmt.query_row([], map_calibration_profile_row)
        .optional()
        .context("failed to execute active calibration query")
}

fn fetch_calibration_profile(
    conn: &Connection,
    profile_id: i64,
) -> anyhow::Result<Option<CalibrationProfile>> {
    let mut stmt = conn
        .prepare(
            r#"
            SELECT
                profile_id,
                name,
                created_at_ms,
                updated_at_ms,
                is_active,
                roller_diameter_m,
                encoder_pulses_per_rev,
                roller_inertia_kg_m2,
                sample_window_ms,
                engine_pulses_per_rev_hint,
                engine_rpm_scale,
                engine_stroke,
                engine_cylinders,
                notes
            FROM calibration_profiles
            WHERE profile_id = ?1
            LIMIT 1
            "#,
        )
        .with_context(|| format!("failed to prepare calibration query for profile {profile_id}"))?;

    stmt.query_row([profile_id], map_calibration_profile_row)
        .optional()
        .with_context(|| format!("failed to execute calibration query for profile {profile_id}"))
}

fn list_calibration_profiles(conn: &Connection) -> anyhow::Result<Vec<CalibrationProfile>> {
    let mut stmt = conn
        .prepare(
            r#"
            SELECT
                profile_id,
                name,
                created_at_ms,
                updated_at_ms,
                is_active,
                roller_diameter_m,
                encoder_pulses_per_rev,
                roller_inertia_kg_m2,
                sample_window_ms,
                engine_pulses_per_rev_hint,
                engine_rpm_scale,
                engine_stroke,
                engine_cylinders,
                notes
            FROM calibration_profiles
            ORDER BY is_active DESC, updated_at_ms DESC, profile_id DESC
            "#,
        )
        .context("failed to prepare calibration profile list query")?;

    let rows = stmt
        .query_map([], map_calibration_profile_row)
        .context("failed to execute calibration profile list query")?;

    let mut profiles = Vec::new();
    for row in rows {
        profiles.push(row.context("failed to map calibration profile row")?);
    }
    Ok(profiles)
}

fn list_calibration_profile_events(
    conn: &Connection,
    profile_id: i64,
) -> anyhow::Result<Vec<CalibrationProfileEvent>> {
    let mut stmt = conn
        .prepare(
            r#"
            SELECT
                event_id,
                profile_id,
                event_type,
                created_at_ms,
                summary,
                previous_values_json,
                new_values_json
            FROM calibration_profile_events
            WHERE profile_id = ?1
            ORDER BY created_at_ms DESC, event_id DESC
            "#,
        )
        .with_context(|| format!("failed to prepare calibration event list query for profile {profile_id}"))?;

    let rows = stmt
        .query_map([profile_id], map_calibration_profile_event_row)
        .with_context(|| format!("failed to execute calibration event list query for profile {profile_id}"))?;

    let mut events = Vec::new();
    for row in rows {
        events.push(row.context("failed to map calibration profile event row")?);
    }
    Ok(events)
}

fn set_active_calibration_profile(conn: &mut Connection, profile_id: i64) -> anyhow::Result<bool> {
    let tx = conn
        .unchecked_transaction()
        .context("failed to start calibration activation transaction")?;
    let previous_active = fetch_active_calibration_profile(&tx)?;
    if previous_active
        .as_ref()
        .map(|profile| profile.profile_id == profile_id)
        .unwrap_or(false)
    {
        tx.rollback()
            .context("failed to roll back redundant calibration activation")?;
        return Ok(true);
    }
    let now_ms = current_time_ms();

    tx.execute(
        "UPDATE calibration_profiles SET is_active = 0 WHERE is_active = 1",
        [],
    )
    .context("failed to clear previous active calibration profile")?;

    let activated = tx
        .execute(
            "UPDATE calibration_profiles SET is_active = 1, updated_at_ms = ?1 WHERE profile_id = ?2",
            params![now_ms, profile_id],
        )
        .with_context(|| format!("failed to activate calibration profile {profile_id}"))?;

    if activated == 0 {
        tx.rollback()
            .context("failed to roll back missing calibration activation")?;
        return Ok(false);
    }

    let active_profile = fetch_calibration_profile(&tx, profile_id)?
        .ok_or_else(|| anyhow!("activated calibration profile missing after update"))?;
    insert_calibration_profile_event(
        &tx,
        active_profile.profile_id,
        CalibrationProfileEventType::Activated,
        now_ms,
        format!("Activated profile {}", active_profile.name),
        previous_active
            .as_ref()
            .map(profile_snapshot_json)
            .transpose()?,
        Some(profile_snapshot_json(&active_profile)?),
    )
    .context("failed to record calibration activation event")?;

    tx.commit()
        .context("failed to commit calibration activation transaction")?;

    Ok(true)
}

fn map_calibration_profile_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<CalibrationProfile> {
    let sample_window_ms: i64 = row.get(8)?;
    let is_active: i64 = row.get(4)?;

    Ok(CalibrationProfile {
        profile_id: row.get(0)?,
        name: row.get(1)?,
        created_at_ms: row.get(2)?,
        updated_at_ms: row.get(3)?,
        is_active: is_active != 0,
        roller_diameter_m: row.get(5)?,
        encoder_pulses_per_rev: row.get(6)?,
        roller_inertia_kg_m2: row.get(7)?,
        sample_window_ms: u64::try_from(sample_window_ms)
            .map_err(|_| rusqlite::Error::IntegralValueOutOfRange(8, sample_window_ms))?,
        engine_pulses_per_rev_hint: row.get(9)?,
        engine_rpm_scale: row.get(10)?,
        engine_stroke: row.get(11)?,
        engine_cylinders: row.get(12)?,
        notes: row.get(13)?,
    })
}

fn map_calibration_profile_event_row(
    row: &rusqlite::Row<'_>,
) -> rusqlite::Result<CalibrationProfileEvent> {
    let event_type: String = row.get(2)?;
    let event_type = match event_type.as_str() {
        "created" => CalibrationProfileEventType::Created,
        "updated" => CalibrationProfileEventType::Updated,
        "duplicated" => CalibrationProfileEventType::Duplicated,
        "activated" => CalibrationProfileEventType::Activated,
        _ => return Err(rusqlite::Error::InvalidQuery),
    };
    let previous_values_json = parse_event_json(row.get::<_, Option<String>>(5)?)?;
    let new_values_json = parse_event_json(row.get::<_, Option<String>>(6)?)?;

    Ok(CalibrationProfileEvent {
        event_id: row.get(0)?,
        profile_id: row.get(1)?,
        event_type,
        created_at_ms: row.get(3)?,
        summary: row.get(4)?,
        previous_values_json,
        new_values_json,
    })
}

fn parse_event_json(value: Option<String>) -> rusqlite::Result<Option<serde_json::Value>> {
    value
        .map(|json| serde_json::from_str(&json).map_err(|_| rusqlite::Error::InvalidQuery))
        .transpose()
}

fn db_update_run_metadata(
    conn: &Connection,
    run_id: i64,
    metadata: &PendingRunMetadata,
) -> anyhow::Result<Option<StoredRun>> {
    let Some(existing) = query_run(conn, run_id)? else {
        return Ok(None);
    };
    let license_plate = normalize_optional_text(metadata.license_plate.as_deref())
        .map(|p| p.to_uppercase());
    // Reassign the per-plate sequence number when the plate changes so
    // {plate}-{run_no} stays unique under the new plate.
    let run_no: Option<i64> = if license_plate == existing.license_plate {
        existing.run_no
    } else {
        match &license_plate {
            Some(plate) => Some(
                conn.query_row(
                    "SELECT COALESCE(MAX(run_no), 0) + 1 FROM runs WHERE license_plate = ?1 AND run_id != ?2",
                    params![plate, run_id],
                    |row| row.get(0),
                )
                .context("failed to allocate run_no for updated plate")?,
            ),
            None => None,
        }
    };
    conn.execute(
        r#"
        UPDATE runs SET
            vehicle_name = ?1,
            license_plate = ?2,
            run_no = ?3,
            customer_name = ?4,
            customer_phone = ?5,
            notes = ?6
        WHERE run_id = ?7
        "#,
        params![
            normalize_optional_text(metadata.vehicle_name.as_deref()),
            license_plate,
            run_no,
            normalize_optional_text(metadata.customer_name.as_deref()),
            normalize_optional_text(metadata.customer_phone.as_deref()),
            normalize_optional_text(metadata.notes.as_deref()),
            run_id,
        ],
    )
    .with_context(|| format!("failed to update run metadata for run {run_id}"))?;
    query_run(conn, run_id)
}

fn delete_run(conn: &Connection, run_id: i64) -> anyhow::Result<bool> {
    let deleted = conn
        .execute("DELETE FROM runs WHERE run_id = ?1", [run_id])
        .with_context(|| format!("failed to delete run {run_id}"))?;
    Ok(deleted > 0)
}

fn db_insert_audit_record(
    conn: &Connection,
    event: AuditEvent,
    profile_id: Option<i64>,
    snapshot: serde_json::Value,
) -> anyhow::Result<()> {
    let snapshot_str =
        serde_json::to_string(&snapshot).context("failed to serialize audit snapshot")?;
    let now_ms = current_time_ms();
    conn.execute(
        r#"
        INSERT INTO audit_log (occurred_at_ms, event, calibration_profile_id, params_snapshot)
        VALUES (?1, ?2, ?3, ?4)
        "#,
        params![now_ms, event.to_string(), profile_id, snapshot_str],
    )
    .context("failed to insert audit record")?;
    Ok(())
}

fn db_list_audit_records(conn: &Connection) -> anyhow::Result<Vec<AuditRecord>> {
    let mut stmt = conn
        .prepare(
            r#"
            SELECT id, occurred_at_ms, event, calibration_profile_id, params_snapshot
            FROM audit_log
            ORDER BY occurred_at_ms DESC
            LIMIT 500
            "#,
        )
        .context("failed to prepare audit log list query")?;

    let rows = stmt
        .query_map([], |row| {
            let occurred_at_ms: i64 = row.get(1)?;
            let event_str: String = row.get(2)?;
            let snapshot_str: String = row.get(4)?;
            Ok((row.get::<_, i64>(0)?, occurred_at_ms, event_str, row.get::<_, Option<i64>>(3)?, snapshot_str))
        })
        .context("failed to execute audit log list query")?;

    let mut records = Vec::new();
    for row in rows {
        let (id, occurred_at_ms, event_str, profile_id, snapshot_str) =
            row.context("failed to map audit log row")?;
        let event = event_str
            .parse::<AuditEvent>()
            .map_err(|_| anyhow!("unknown audit event type: {event_str}"))?;
        let occurred_at = datetime_from_ms(occurred_at_ms)?;
        let params_snapshot: serde_json::Value = serde_json::from_str(&snapshot_str)
            .context("failed to deserialize audit snapshot")?;
        records.push(AuditRecord {
            id,
            occurred_at,
            event,
            calibration_profile_id: profile_id,
            params_snapshot,
        });
    }
    Ok(records)
}

fn db_get_peak_values_for_runs(
    conn: &Connection,
    run_ids: &[i64],
) -> anyhow::Result<Vec<(i64, f64, f64, Option<f64>)>> {
    if run_ids.is_empty() {
        return Ok(Vec::new());
    }

    // Ensure every requested run has its peaks computed with the unified
    // (dashboard) rule before reading them, same lazy-backfill contract as
    // query_run/query_recent_runs.
    for &run_id in run_ids {
        let already_computed: Option<i64> = conn
            .query_row(
                "SELECT peaks_computed FROM runs WHERE run_id = ?1 AND ended_at_ms IS NOT NULL",
                [run_id],
                |row| row.get(0),
            )
            .optional()
            .with_context(|| format!("failed to check peaks_computed for run {run_id}"))?;
        if already_computed == Some(0) {
            compute_and_store_peaks(conn, run_id)
                .with_context(|| format!("failed to backfill peaks for run {run_id}"))?;
        }
    }

    let placeholders = (1..=run_ids.len())
        .map(|i| format!("?{i}"))
        .collect::<Vec<_>>()
        .join(", ");

    // Peak power/torque come from the runs table (unified dashboard rule);
    // peak speed is not governed by that rule, so it's still a raw MAX over
    // frames.
    let sql = format!(
        "SELECT r.run_id, r.peak_power_hp, r.peak_torque_nm,
                (SELECT MAX(f.speed_kmh) FROM frames f WHERE f.run_id = r.run_id)
         FROM runs r
         WHERE r.run_id IN ({placeholders}) AND r.peak_power_hp > 0.0 AND r.peak_torque_nm > 0.0"
    );

    let mut stmt = conn.prepare(&sql).context("failed to prepare peak-values query")?;
    let rows = stmt
        .query_map(rusqlite::params_from_iter(run_ids.iter()), |row| {
            Ok((
                row.get::<_, i64>(0)?,
                row.get::<_, f64>(1)?,
                row.get::<_, f64>(2)?,
                row.get::<_, Option<f64>>(3)?,
            ))
        })
        .context("failed to execute peak-values query")?;

    let mut result = Vec::new();
    for row in rows {
        result.push(row.context("failed to map peak-values row")?);
    }
    Ok(result)
}

fn db_get_setting(conn: &Connection, key: &str) -> anyhow::Result<Option<String>> {
    conn.query_row(
        "SELECT value FROM settings WHERE key = ?1",
        [key],
        |row| row.get(0),
    )
    .optional()
    .with_context(|| format!("failed to get setting '{key}'"))
}

fn db_set_setting(conn: &Connection, key: &str, value: &str) -> anyhow::Result<()> {
    conn.execute(
        "INSERT INTO settings (key, value) VALUES (?1, ?2) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        [key, value],
    )
    .with_context(|| format!("failed to set setting '{key}'"))?;
    Ok(())
}

/// Atomically set `key` only if it has no row yet. Returns `true` if this
/// call inserted the value, `false` if a row already existed (left
/// untouched). Avoids the check-then-set race of a separate
/// `db_get_setting` + `db_set_setting` pair — the whole thing is one
/// statement, and the storage worker processes commands strictly
/// sequentially, so no other command can interleave between the check and
/// the insert.
fn db_set_setting_if_absent(conn: &Connection, key: &str, value: &str) -> anyhow::Result<bool> {
    let rows = conn
        .execute(
            "INSERT OR IGNORE INTO settings (key, value) VALUES (?1, ?2)",
            [key, value],
        )
        .with_context(|| format!("failed to set setting '{key}' if absent"))?;
    Ok(rows > 0)
}

fn stored_run_to_summary(run: StoredRun) -> RunSummary {
    RunSummary {
        run_id: run.run_id,
        run_name: run.display_id(),
        date: datetime_from_ms(run.started_at_ms).unwrap_or_else(|_| Utc::now()),
        peak_power_hp: run.peak_power_hp,
        peak_power_rpm: run.peak_power_rpm,
        peak_torque_nm: run.peak_torque_nm,
        peak_torque_rpm: run.peak_torque_rpm,
    }
}

fn parse_source_mode(value: &str) -> rusqlite::Result<SourceMode> {
    value
        .parse::<SourceMode>()
        .map_err(|_| rusqlite::Error::InvalidQuery)
}

fn parse_correction_mode(value: &str) -> rusqlite::Result<CorrectionMode> {
    value
        .parse::<CorrectionMode>()
        .map_err(|_| rusqlite::Error::InvalidQuery)
}

fn datetime_from_ms(ts_ms: i64) -> anyhow::Result<DateTime<Utc>> {
    Utc.timestamp_millis_opt(ts_ms)
        .single()
        .ok_or_else(|| anyhow!("invalid millisecond timestamp {ts_ms}"))
}

fn current_time_ms() -> i64 {
    match SystemTime::now().duration_since(UNIX_EPOCH) {
        Ok(duration) => duration.as_millis() as i64,
        Err(_) => 0,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use dyno_types::Esp32TelemetryStatus;
    use std::sync::atomic::{AtomicU64, Ordering};

    static NEXT_TEST_ID: AtomicU64 = AtomicU64::new(1);

    fn test_db_path(label: &str) -> PathBuf {
        let unique = NEXT_TEST_ID.fetch_add(1, Ordering::Relaxed);
        let pid = std::process::id();
        std::env::temp_dir().join(format!("dyno-core-{label}-{pid}-{unique}.sqlite"))
    }

    fn test_config(db_path: &Path) -> Config {
        Config {
            serial_port: "/dev/null".to_owned(),
            serial_baud: 921_600,
            can_iface: "can0".to_owned(),
            profile: "production".to_owned(),
            modbus_afr_enabled: false,
            ws_bind: "127.0.0.1:0".to_owned(),
            api_bind: "127.0.0.1:0".to_owned(),
            data_dir: ".".to_owned(),
            db_path: db_path.display().to_string(),
            esp32_config_path: "esp32-device-config.json".to_owned(),
            esp32_applied_config_path: "esp32-last-applied.json".to_owned(),
            esp32_command_timeout_ms: 1_500,
            esp32_command_retries: 3,
            bme280_enabled: false,
            source_mode: SourceMode::Replay,
            correction_mode: CorrectionMode::SAEJ1349,
            roller_diameter_m: 0.318,
            encoder_pulses_per_rev: 60.0,
            roller_inertia_kg_m2: 3.5,
            sample_window_ms: 100,
            ui_broadcast_rate_hz: 20,
            arm_rpm: 1500.0,
            record_rpm: 2000.0,
            stop_rpm: 1000.0,
        }
    }

    fn frame(ts_ms: i64, run_state: RunState, engine_rpm: f32, power_hp: Option<f32>, torque_nm: Option<f32>) -> LiveFrame {
        LiveFrame {
            ts_ms,
            engine_rpm: Some(engine_rpm),
            roller_rpm: Some(engine_rpm / 4.0),
            speed_kmh: Some(engine_rpm / 60.0),
            power_hp,
            torque_nm,
            correction_factor: 1.02,
            afr: Some(13.2),
            lambda: Some(0.9),
            can_present: true,
            can_frames_seen: 1,
            afr_valid: true,
            can_valid: true,
            can_status_text: "AEM UEGO active".to_owned(),
            ambient_temp_c: Some(24.5),
            humidity_pct: Some(55.0),
            pressure_hpa: Some(1013.25),
            esp32_status: Esp32TelemetryStatus::default(),
            run_state,
            faults: Vec::new(),
            alerts: Default::default(),
        }
    }

    fn stored_frame(
        ts_ms: i64,
        run_state: RunState,
        engine_rpm: Option<f32>,
        power_hp: Option<f32>,
        torque_nm: Option<f32>,
    ) -> StoredFrame {
        StoredFrame {
            run_id: 1,
            ts_ms,
            engine_rpm,
            roller_rpm: None,
            speed_kmh: None,
            power_hp,
            torque_nm,
            afr: None,
            lambda: None,
            ambient_temp_c: None,
            humidity_pct: None,
            pressure_hpa: None,
            correction_factor: 1.0,
            run_state,
        }
    }

    #[test]
    fn compute_run_peaks_takes_max_over_clean_ascending_sweep() {
        let frames = vec![
            stored_frame(0, RunState::Idle, Some(900.0), None, None),
            stored_frame(100, RunState::Recording, Some(2000.0), Some(40.0), Some(100.0)),
            stored_frame(200, RunState::Recording, Some(3000.0), Some(60.0), Some(110.0)),
            stored_frame(300, RunState::Recording, Some(4000.0), Some(80.0), Some(105.0)),
        ];
        let peaks = compute_run_peaks(&frames);
        assert_eq!(peaks.power_hp, 80.0);
        assert_eq!(peaks.power_rpm, 4000.0);
        assert_eq!(peaks.torque_nm, 110.0);
        assert_eq!(peaks.torque_rpm, 3000.0);
    }

    #[test]
    fn compute_run_peaks_rejects_decel_spike_after_rpm_drop() {
        let frames = vec![
            stored_frame(100, RunState::Recording, Some(2000.0), Some(40.0), Some(100.0)),
            stored_frame(200, RunState::Recording, Some(4000.0), Some(80.0), Some(110.0)),
            // Decel tail: rpm drops, even though power/torque here would be
            // a new peak they must NOT count.
            stored_frame(300, RunState::Recording, Some(3500.0), Some(95.0), Some(130.0)),
        ];
        let peaks = compute_run_peaks(&frames);
        assert_eq!(peaks.power_hp, 80.0);
        assert_eq!(peaks.torque_nm, 110.0);
    }

    #[test]
    fn compute_run_peaks_skips_frame_missing_torque() {
        let frames = vec![
            // Highest power, but torque missing -> must not set either peak.
            stored_frame(100, RunState::Recording, Some(2000.0), Some(999.0), None),
            stored_frame(200, RunState::Recording, Some(3000.0), Some(60.0), Some(110.0)),
        ];
        let peaks = compute_run_peaks(&frames);
        assert_eq!(peaks.power_hp, 60.0);
        assert_eq!(peaks.torque_nm, 110.0);
    }

    #[test]
    fn compute_run_peaks_drops_negative_values() {
        let frames = vec![
            stored_frame(100, RunState::Recording, Some(2000.0), Some(-5.0), Some(-1.0)),
            stored_frame(200, RunState::Recording, Some(3000.0), Some(60.0), Some(110.0)),
        ];
        let peaks = compute_run_peaks(&frames);
        assert_eq!(peaks.power_hp, 60.0);
        assert_eq!(peaks.torque_nm, 110.0);
    }

    #[test]
    fn compute_run_peaks_ignores_pre_run_idle_frames() {
        let frames = vec![
            // Pre-run buffer frames prepended with idle/armed state, higher
            // "power" than anything in the actual recording, must be ignored.
            stored_frame(0, RunState::Idle, Some(900.0), Some(500.0), Some(500.0)),
            stored_frame(50, RunState::Armed, Some(1500.0), Some(500.0), Some(500.0)),
            stored_frame(100, RunState::Recording, Some(2000.0), Some(40.0), Some(100.0)),
        ];
        let peaks = compute_run_peaks(&frames);
        assert_eq!(peaks.power_hp, 40.0);
        assert_eq!(peaks.torque_nm, 100.0);
    }

    #[test]
    fn compute_run_peaks_empty_run_yields_zero() {
        let frames: Vec<StoredFrame> = Vec::new();
        let peaks = compute_run_peaks(&frames);
        assert_eq!(peaks, RunPeaks::default());
    }

    #[test]
    fn compute_run_peaks_all_frames_missing_rpm_yields_zero() {
        let frames = vec![stored_frame(100, RunState::Recording, None, Some(40.0), Some(100.0))];
        let peaks = compute_run_peaks(&frames);
        assert_eq!(peaks, RunPeaks::default());
    }

    async fn record_run(storage: &Storage, base_ts: i64) {
        storage
            .record_live_frame(frame(base_ts, RunState::Recording, 3000.0, Some(50.0), Some(120.0)))
            .await
            .expect("recording frame");
        storage
            .record_live_frame(frame(base_ts + 100, RunState::Idle, 900.0, None, None))
            .await
            .expect("closing frame");
        storage.flush().await.expect("flush");
    }

    fn plate_metadata(plate: &str) -> PendingRunMetadata {
        PendingRunMetadata {
            license_plate: Some(plate.to_owned()),
            vehicle_name: None,
            customer_name: Some("Somchai".to_owned()),
            customer_phone: Some("081-234-5678".to_owned()),
            notes: None,
        }
    }

    #[tokio::test]
    async fn run_no_sequences_per_plate_and_display_id_formats() {
        let db_path = test_db_path("run-no");
        let storage = Storage::open(&test_config(&db_path)).await.expect("open storage");

        storage.set_pending_run_metadata(plate_metadata(" abc 123 ")).await.expect("meta");
        record_run(&storage, 1_000).await;
        record_run(&storage, 10_000).await;
        storage.set_pending_run_metadata(plate_metadata("XYZ 9")).await.expect("meta");
        record_run(&storage, 20_000).await;

        let runs = storage.list_recent_runs(10).await.expect("list");
        assert_eq!(runs.len(), 3);
        // Newest first.
        assert_eq!(runs[0].display_id(), "XYZ 9-1");
        assert_eq!(runs[1].display_id(), "ABC 123-2");
        assert_eq!(runs[2].display_id(), "ABC 123-1");
        assert_eq!(runs[0].customer_name.as_deref(), Some("Somchai"));

        drop(storage);
        let _ = fs::remove_file(db_path);
    }

    #[tokio::test]
    async fn search_matches_plate_customer_and_phone() {
        let db_path = test_db_path("search");
        let storage = Storage::open(&test_config(&db_path)).await.expect("open storage");

        storage.set_pending_run_metadata(plate_metadata("ABC 123")).await.expect("meta");
        record_run(&storage, 1_000).await;
        storage
            .set_pending_run_metadata(PendingRunMetadata {
                license_plate: Some("XYZ 9".to_owned()),
                vehicle_name: None,
                customer_name: Some("Malee".to_owned()),
                customer_phone: Some("099-000-1111".to_owned()),
                notes: None,
            })
            .await
            .expect("meta");
        record_run(&storage, 10_000).await;

        let by_plate = storage.search_recent_runs(Some("abc".to_owned()), 10).await.expect("q");
        assert_eq!(by_plate.len(), 1);
        assert_eq!(by_plate[0].license_plate.as_deref(), Some("ABC 123"));

        let by_customer = storage.search_recent_runs(Some("malee".to_owned()), 10).await.expect("q");
        assert_eq!(by_customer.len(), 1);
        assert_eq!(by_customer[0].license_plate.as_deref(), Some("XYZ 9"));

        let by_phone = storage.search_recent_runs(Some("099-000".to_owned()), 10).await.expect("q");
        assert_eq!(by_phone.len(), 1);

        let no_match = storage.search_recent_runs(Some("nothing".to_owned()), 10).await.expect("q");
        assert!(no_match.is_empty());

        // Blank search behaves like no filter.
        let blank = storage.search_recent_runs(Some("  ".to_owned()), 10).await.expect("q");
        assert_eq!(blank.len(), 2);

        drop(storage);
        let _ = fs::remove_file(db_path);
    }

    #[tokio::test]
    async fn run_no_backfill_numbers_existing_runs_per_plate() {
        let db_path = test_db_path("backfill");
        // Simulate a pre-migration database: runs with plates but no run_no column.
        {
            let conn = open_connection(&db_path).expect("open raw");
            conn.execute_batch(
                r#"
                CREATE TABLE runs (
                    run_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    started_at_ms INTEGER NOT NULL,
                    ended_at_ms INTEGER NULL,
                    source_mode TEXT NOT NULL,
                    correction_mode TEXT NOT NULL,
                    roller_diameter_m REAL NOT NULL,
                    encoder_pulses_per_rev REAL NOT NULL,
                    roller_inertia_kg_m2 REAL NOT NULL,
                    sample_window_ms INTEGER NOT NULL,
                    vehicle_name TEXT NULL,
                    license_plate TEXT NULL
                );
                INSERT INTO runs (started_at_ms, ended_at_ms, source_mode, correction_mode,
                                  roller_diameter_m, encoder_pulses_per_rev, roller_inertia_kg_m2,
                                  sample_window_ms, license_plate)
                VALUES
                    (1000, 2000, 'replay', 'SAEJ1349', 0.3, 60, 3.5, 100, 'AAA 1'),
                    (3000, 4000, 'replay', 'SAEJ1349', 0.3, 60, 3.5, 100, 'BBB 2'),
                    (5000, 6000, 'replay', 'SAEJ1349', 0.3, 60, 3.5, 100, 'AAA 1'),
                    (7000, 8000, 'replay', 'SAEJ1349', 0.3, 60, 3.5, 100, NULL);
                "#,
            )
            .expect("seed legacy runs");
        }

        let storage = Storage::open(&test_config(&db_path)).await.expect("open storage");
        let runs = storage.list_recent_runs(10).await.expect("list");
        let by_id: Vec<(Option<&str>, Option<i64>)> = runs
            .iter()
            .rev()
            .map(|r| (r.license_plate.as_deref(), r.run_no))
            .collect();
        assert_eq!(
            by_id,
            vec![
                (Some("AAA 1"), Some(1)),
                (Some("BBB 2"), Some(1)),
                (Some("AAA 1"), Some(2)),
                (None, None),
            ]
        );

        drop(storage);
        let _ = fs::remove_file(db_path);
    }

    #[tokio::test]
    async fn update_run_metadata_reassigns_run_no_on_plate_change() {
        let db_path = test_db_path("meta-update");
        let storage = Storage::open(&test_config(&db_path)).await.expect("open storage");

        storage.set_pending_run_metadata(plate_metadata("OLD 1")).await.expect("meta");
        record_run(&storage, 1_000).await;
        let run_id = storage.list_recent_runs(1).await.expect("list")[0].run_id;

        let updated = storage
            .update_run_metadata(
                run_id,
                PendingRunMetadata {
                    license_plate: Some("new 7".to_owned()),
                    vehicle_name: Some("Civic".to_owned()),
                    customer_name: Some("Anan".to_owned()),
                    customer_phone: None,
                    notes: Some("retune".to_owned()),
                },
            )
            .await
            .expect("update")
            .expect("run exists");
        assert_eq!(updated.display_id(), "NEW 7-1");
        assert_eq!(updated.vehicle_name.as_deref(), Some("Civic"));
        assert_eq!(updated.customer_name.as_deref(), Some("Anan"));
        assert_eq!(updated.notes.as_deref(), Some("retune"));

        drop(storage);
        let _ = fs::remove_file(db_path);
    }

    #[tokio::test]
    async fn schema_is_created_on_open() {
        let db_path = test_db_path("schema");
        let storage = Storage::open(&test_config(&db_path)).await.expect("open storage");
        storage.flush().await.expect("flush");

        let conn = Connection::open(&db_path).expect("open db for inspection");
        let mut stmt = conn
            .prepare(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('calibration_profile_events', 'calibration_profiles', 'runs', 'frames') ORDER BY name",
            )
            .expect("prepare sqlite_master query");

        let names = stmt
            .query_map([], |row| row.get::<_, String>(0))
            .expect("query sqlite_master")
            .collect::<Result<Vec<_>, _>>()
            .expect("read table names");

        assert_eq!(
            names,
            vec![
                "calibration_profile_events".to_owned(),
                "calibration_profiles".to_owned(),
                "frames".to_owned(),
                "runs".to_owned()
            ]
        );

        drop(storage);
        let _ = fs::remove_file(db_path);
    }

    #[tokio::test]
    async fn default_profile_is_created_when_storage_is_empty() {
        let db_path = test_db_path("default-calibration");
        let storage = Storage::open(&test_config(&db_path)).await.expect("open storage");

        let active = storage
            .fetch_active_calibration()
            .await
            .expect("fetch active calibration")
            .expect("active calibration exists");
        let profiles = storage
            .list_calibration_profiles()
            .await
            .expect("list calibration profiles");

        assert_eq!(profiles.len(), 1);
        assert_eq!(active.name, "Default bootstrap profile");
        assert!(active.is_active);
        assert_eq!(active.roller_diameter_m, 0.318);
        assert_eq!(active.encoder_pulses_per_rev, 60.0);
        assert_eq!(active.roller_inertia_kg_m2, 3.5);
        assert_eq!(active.sample_window_ms, 100);

        drop(storage);
        let _ = fs::remove_file(db_path);
    }

    #[tokio::test]
    async fn active_profile_loading_and_switching_work() {
        let db_path = test_db_path("active-calibration");
        let storage = Storage::open(&test_config(&db_path)).await.expect("open storage");

        {
            let conn = Connection::open(&db_path).expect("open db for calibration insert");
            conn.execute(
                r#"
                INSERT INTO calibration_profiles (
                    name,
                    created_at_ms,
                    updated_at_ms,
                    is_active,
                    roller_diameter_m,
                    encoder_pulses_per_rev,
                    roller_inertia_kg_m2,
                    sample_window_ms,
                    engine_pulses_per_rev_hint,
                    engine_rpm_scale,
                    notes
                ) VALUES (?1, ?2, ?3, 0, ?4, ?5, ?6, ?7, NULL, NULL, ?8)
                "#,
                params![
                    "Track day profile",
                    2_000_i64,
                    2_000_i64,
                    0.33_f32,
                    48.0_f32,
                    4.0_f32,
                    80_i64,
                    "second profile",
                ],
            )
            .expect("insert second calibration");
        }

        let profiles = storage
            .list_calibration_profiles()
            .await
            .expect("list calibration profiles");
        assert_eq!(profiles.len(), 2);
        let second_profile_id = profiles
            .iter()
            .find(|profile| profile.name == "Track day profile")
            .expect("second profile")
            .profile_id;

        assert!(storage
            .set_active_calibration(second_profile_id)
            .await
            .expect("set active calibration"));

        let active = storage
            .fetch_active_calibration()
            .await
            .expect("fetch active calibration")
            .expect("active calibration exists");
        assert_eq!(active.profile_id, second_profile_id);
        assert_eq!(active.name, "Track day profile");
        assert!(active.is_active);

        drop(storage);
        let _ = fs::remove_file(db_path);
    }

    #[tokio::test]
    async fn create_profile_records_audit_event() {
        let db_path = test_db_path("create-profile");
        let storage = Storage::open(&test_config(&db_path)).await.expect("open storage");

        let change = storage
            .create_calibration_profile(
                CalibrationProfileInput {
                    name: "Street roller".to_owned(),
                    roller_diameter_m: 0.325,
                    encoder_pulses_per_rev: 72.0,
                    roller_inertia_kg_m2: 3.8,
                    sample_window_ms: 90,
                    engine_pulses_per_rev_hint: Some(1.0),
                    engine_rpm_scale: Some(1.0),
                    engine_stroke: None,
                    engine_cylinders: None,
                    notes: Some("created".to_owned()),
                },
                false,
            )
            .await
            .expect("create calibration profile");

        assert_eq!(change.profile.name, "Street roller");
        assert!(!change.activated);
        assert!(!change.profile.is_active);

        let events = storage
            .list_calibration_profile_events(change.profile.profile_id)
            .await
            .expect("list profile events");
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].event_type, CalibrationProfileEventType::Created);
        assert!(events[0].summary.contains("Street roller"));

        drop(storage);
        let _ = fs::remove_file(db_path);
    }

    #[tokio::test]
    async fn update_profile_records_before_and_after_audit_snapshots() {
        let db_path = test_db_path("update-profile");
        let storage = Storage::open(&test_config(&db_path)).await.expect("open storage");
        let original = storage
            .fetch_active_calibration()
            .await
            .expect("fetch active calibration")
            .expect("active calibration");

        let updated = storage
            .update_calibration_profile(
                original.profile_id,
                CalibrationProfileInput {
                    name: "Updated active profile".to_owned(),
                    roller_diameter_m: 0.330,
                    encoder_pulses_per_rev: 64.0,
                    roller_inertia_kg_m2: 4.1,
                    sample_window_ms: 120,
                    engine_pulses_per_rev_hint: Some(1.0),
                    engine_rpm_scale: Some(1.0),
                    engine_stroke: None,
                    engine_cylinders: None,
                    notes: Some("updated".to_owned()),
                },
                false,
            )
            .await
            .expect("update calibration profile")
            .expect("profile exists");

        assert_eq!(updated.profile.name, "Updated active profile");
        assert!(updated.profile.is_active);
        assert!(!updated.activated);

        let events = storage
            .list_calibration_profile_events(original.profile_id)
            .await
            .expect("list profile events");
        assert_eq!(events[0].event_type, CalibrationProfileEventType::Updated);
        assert_eq!(
            events[0].previous_values_json.as_ref().and_then(|value| value["name"].as_str()),
            Some("Default bootstrap profile")
        );
        assert_eq!(
            events[0].new_values_json.as_ref().and_then(|value| value["name"].as_str()),
            Some("Updated active profile")
        );

        drop(storage);
        let _ = fs::remove_file(db_path);
    }

    #[tokio::test]
    async fn duplicate_profile_generates_incremented_name_and_audit_event() {
        let db_path = test_db_path("duplicate-profile");
        let storage = Storage::open(&test_config(&db_path)).await.expect("open storage");
        let source = storage
            .fetch_active_calibration()
            .await
            .expect("fetch active calibration")
            .expect("active calibration");

        let first_copy = storage
            .duplicate_calibration_profile(source.profile_id, None, false)
            .await
            .expect("duplicate profile")
            .expect("source profile exists");
        let second_copy = storage
            .duplicate_calibration_profile(source.profile_id, None, false)
            .await
            .expect("duplicate profile again")
            .expect("source profile exists");

        assert_eq!(first_copy.profile.name, "Default bootstrap profile-1");
        assert_eq!(second_copy.profile.name, "Default bootstrap profile-2");

        let events = storage
            .list_calibration_profile_events(first_copy.profile.profile_id)
            .await
            .expect("list duplicate profile events");
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].event_type, CalibrationProfileEventType::Duplicated);
        assert_eq!(
            events[0].previous_values_json.as_ref().and_then(|value| value["profile_id"].as_i64()),
            Some(source.profile_id)
        );

        drop(storage);
        let _ = fs::remove_file(db_path);
    }

    #[tokio::test]
    async fn recording_lifecycle_persists_run_and_frames() {
        let db_path = test_db_path("lifecycle");
        let storage = Storage::open(&test_config(&db_path)).await.expect("open storage");

        storage
            .record_live_frame(frame(900, RunState::Idle, 1100.0, None, None))
            .await
            .expect("idle frame");
        storage
            .record_live_frame(frame(1000, RunState::Recording, 2800.0, Some(42.0), Some(110.0)))
            .await
            .expect("first recording frame");
        storage
            .record_live_frame(frame(1100, RunState::Recording, 4200.0, Some(88.0), Some(132.0)))
            .await
            .expect("second recording frame");
        storage
            .record_live_frame(frame(1200, RunState::Armed, 1500.0, None, None))
            .await
            .expect("paused armed frame");
        storage
            .record_live_frame(frame(1300, RunState::Recording, 3900.0, Some(70.0), Some(120.0)))
            .await
            .expect("recording resumes");
        storage
            .record_live_frame(frame(1400, RunState::Idle, 1000.0, None, None))
            .await
            .expect("closing idle frame");
        storage.flush().await.expect("flush");

        let runs = storage.list_recent_runs(10).await.expect("list runs");
        assert_eq!(runs.len(), 1);
        let run = &runs[0];
        assert_eq!(run.peak_power_hp, 88.0);
        assert_eq!(run.peak_power_rpm, 4200.0);
        assert_eq!(run.peak_torque_nm, 132.0);
        assert_eq!(run.peak_torque_rpm, 4200.0);
        assert_eq!(run.calibration_profile_name.as_deref(), Some("Default bootstrap profile"));

        let frames = storage.fetch_frames(run.run_id).await.expect("fetch frames");
        // The Idle frame at ts_ms=900 is included as pre-run context; the
        // mid-run Armed pause is not persisted and does not close the run.
        assert_eq!(frames.len(), 4);
        assert_eq!(frames[0].ts_ms, 900);
        assert_eq!(frames[0].run_state, RunState::Idle);
        assert_eq!(frames[1].run_state, RunState::Recording);
        assert_eq!(frames[2].run_state, RunState::Recording);
        assert_eq!(frames[3].ts_ms, 1300);
        assert_eq!(frames[3].run_state, RunState::Recording);

        let conn = Connection::open(&db_path).expect("open db for run inspection");
        let ended_at_ms: Option<i64> = conn
            .query_row(
                "SELECT ended_at_ms FROM runs WHERE run_id = ?1",
                [run.run_id],
                |row| row.get(0),
            )
            .expect("query ended_at_ms");
        assert_eq!(ended_at_ms, Some(1400));

        drop(storage);
        let _ = fs::remove_file(db_path);
    }

    #[tokio::test]
    async fn stop_idle_close_drops_stale_recording_without_new_run() {
        let db_path = test_db_path("stop-stale-recording");
        let storage = Storage::open(&test_config(&db_path)).await.expect("open storage");

        storage
            .record_live_frame(frame(1000, RunState::Recording, 3000.0, Some(60.0), Some(120.0)))
            .await
            .expect("recording frame");
        storage
            .record_live_frame(LiveFrame::idle(2000))
            .await
            .expect("operator stop idle frame");
        storage
            .record_live_frame(frame(1500, RunState::Recording, 3200.0, Some(62.0), Some(122.0)))
            .await
            .expect("stale recording frame");
        storage.flush().await.expect("flush");

        let runs = storage.list_recent_runs(10).await.expect("list runs");
        assert_eq!(runs.len(), 1);
        let frames = storage.fetch_frames(runs[0].run_id).await.expect("fetch frames");
        assert_eq!(frames.len(), 1);
        assert_eq!(frames[0].ts_ms, 1000);

        drop(storage);
        let _ = fs::remove_file(db_path);
    }

    #[tokio::test]
    async fn pre_run_frames_are_prepended_with_original_timestamps() {
        let db_path = test_db_path("pre-run-prepend");
        let storage = Storage::open(&test_config(&db_path)).await.expect("open storage");

        // Three idle frames before the run begins.
        storage.record_live_frame(frame(100, RunState::Idle, 500.0, None, None)).await.expect("pre 1");
        storage.record_live_frame(frame(110, RunState::Idle, 600.0, None, None)).await.expect("pre 2");
        storage.record_live_frame(frame(120, RunState::Armed, 1600.0, None, None)).await.expect("pre 3");

        // Run start trigger.
        storage.record_live_frame(frame(1000, RunState::Recording, 3000.0, Some(60.0), Some(120.0))).await.expect("rec 1");
        storage.record_live_frame(frame(1100, RunState::Stopping, 2000.0, Some(30.0), Some(80.0))).await.expect("stopping");
        storage.record_live_frame(frame(1200, RunState::Idle, 500.0, None, None)).await.expect("close");
        storage.flush().await.expect("flush");

        let runs = storage.list_recent_runs(10).await.expect("list runs");
        assert_eq!(runs.len(), 1);

        let frames = storage.fetch_frames(runs[0].run_id).await.expect("fetch frames");
        // 3 pre-run frames + 1 Recording (included in drain) + 1 Stopping = 5.
        assert_eq!(frames.len(), 5);
        // Pre-run frames appear first in original timestamp order.
        assert_eq!(frames[0].ts_ms, 100);
        assert_eq!(frames[0].run_state, RunState::Idle);
        assert_eq!(frames[1].ts_ms, 110);
        assert_eq!(frames[1].run_state, RunState::Idle);
        assert_eq!(frames[2].ts_ms, 120);
        assert_eq!(frames[2].run_state, RunState::Armed);
        assert_eq!(frames[3].ts_ms, 1000);
        assert_eq!(frames[3].run_state, RunState::Recording);
        assert_eq!(frames[4].ts_ms, 1100);
        assert_eq!(frames[4].run_state, RunState::Stopping);
        // Peak values are unaffected by pre-run frames that have no power/torque.
        assert_eq!(runs[0].peak_power_hp, 60.0);
        assert_eq!(runs[0].peak_torque_nm, 120.0);

        drop(storage);
        let _ = fs::remove_file(db_path);
    }

    #[tokio::test]
    async fn pre_run_buffer_caps_at_300_and_oldest_are_dropped() {
        let db_path = test_db_path("pre-run-cap");
        let storage = Storage::open(&test_config(&db_path)).await.expect("open storage");

        // Send 400 idle frames — buffer holds at most PRE_RUN_BUFFER_CAP (300).
        for i in 0i64..400 {
            storage.record_live_frame(frame(i * 10, RunState::Idle, 500.0, None, None))
                .await.expect("idle frame");
        }

        // Recording frame is also pushed to the buffer before the drain, evicting
        // one more entry; the drain therefore yields exactly PRE_RUN_BUFFER_CAP frames.
        storage.record_live_frame(frame(10_000, RunState::Recording, 3000.0, Some(60.0), Some(120.0)))
            .await.expect("recording frame");
        storage.record_live_frame(frame(10_100, RunState::Idle, 500.0, None, None))
            .await.expect("close");
        storage.flush().await.expect("flush");

        let runs = storage.list_recent_runs(10).await.expect("list runs");
        assert_eq!(runs.len(), 1);

        let frames = storage.fetch_frames(runs[0].run_id).await.expect("fetch frames");
        assert_eq!(frames.len(), PRE_RUN_BUFFER_CAP);

        // Last frame is the Recording trigger with its original timestamp preserved.
        assert_eq!(frames.last().unwrap().ts_ms, 10_000);
        assert_eq!(frames.last().unwrap().run_state, RunState::Recording);

        // The oldest 100 of the 400 idle frames were evicted; ts_ms starts > 0.
        assert!(frames[0].ts_ms > 0);

        drop(storage);
        let _ = fs::remove_file(db_path);
    }

    #[tokio::test]
    async fn storage_task_records_watch_updates() {
        let db_path = test_db_path("task");
        let storage = Storage::open(&test_config(&db_path)).await.expect("open storage");
        let (tx, rx) = watch::channel(LiveFrame::idle(0));
        let task = StorageTask::spawn(storage.clone(), rx);

        tx.send(frame(1000, RunState::Recording, 3000.0, Some(45.0), Some(118.0)))
            .expect("send recording frame");
        tokio::time::sleep(Duration::from_millis(20)).await;
        tx.send(frame(1100, RunState::Idle, 1000.0, None, None))
            .expect("send closing idle frame");
        tokio::time::sleep(Duration::from_millis(20)).await;
        storage.flush().await.expect("flush");

        let runs = storage.list_recent_runs(10).await.expect("list runs");
        assert_eq!(runs.len(), 1);
        assert_eq!(runs[0].peak_power_hp, 45.0);

        drop(task);
        drop(storage);
        let _ = fs::remove_file(db_path);
    }
}
