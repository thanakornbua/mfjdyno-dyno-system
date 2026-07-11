//! Top-level application object; owns all subsystem handles.

use anyhow::Context;
use tokio::sync::watch;
use tracing::{info, warn};

use dyno_protocol::DynoFrameV1;
use dyno_types::LiveFrame;

use crate::{
    api::ApiTask,
    audit::AuditLogger,
    bme280::{AmbientSample, Bme280Task},
    calibration::{CalibrationLock, CalibrationProfile, validate_profile},
    can::{CanSample, CanTask},
    config::{Config, SourceMode},
    esp32_config::Esp32ConfigManager,
    fusion::FusionTask,
    health::{StartupHealth, collect_startup_health, log_startup_health},
    replay::ReplayTask,
    run_control::RunControl,
    serial::SerialTask,
    serial_gate::serial_gate,
    state::StateMachine,
    storage::{Storage, StorageTask},
    ws::WsTask,
};

/// Owns the handles to all spawned tasks and subsystems.
///
/// Dropping `App` triggers an orderly shutdown:
/// 1. `SerialTask` is dropped → its `JoinHandle` is aborted → serial port closes.
/// 2. The UART `watch::Sender` is dropped → the fusion task observes a closed
///    input channel and exits on the next `rx.changed()` call.
/// 3. The retained `LiveFrame` receiver is dropped → future publishers and
///    subscribers observe channel closure.
/// 4. All other placeholder tasks are dropped.
pub struct App {
    pub config: Config,
    pub state: StateMachine,
    pub startup_health: StartupHealth,
    // ── Active tasks ─────────────────────────────────────────────────────────
    _serial: Option<SerialTask>,
    _can: Option<CanTask>,
    _replay: Option<ReplayTask>,
    // Retained until the WebSocket layer consumes the live stream directly.
    _live_rx: watch::Receiver<LiveFrame>,
    _calibration_rx: watch::Receiver<CalibrationProfile>,
    _run_control: RunControl,
    // ── Placeholder stubs (not yet implemented) ───────────────────────────────
    _bme280:  Option<Bme280Task>,
    _fusion:  Option<FusionTask>,
    _api:     ApiTask,
    _ws:      WsTask,
    _storage: Storage,
    _storage_task: StorageTask,
}

impl App {
    /// Construct and start all subsystems.
    pub async fn start(mut config: Config) -> anyhow::Result<Self> {
        // ── Storage ───────────────────────────────────────────────────────────
        //
        // Opened before the startup health snapshot so the persisted read
        // serial port override below is already applied when health checks the
        // serial device — otherwise /healthz would permanently report the
        // pre-override port.
        info!("opening storage at {}", config.db_path);
        let storage = Storage::open(&config)
            .await
            .with_context(|| format!("could not open storage at {}", config.db_path))?;

        // ── Persisted read serial port override ────────────────────────────────
        //
        // Precedence for the ESP read port: an explicit `DYNO_SERIAL_PORT` env
        // (anything other than `auto`) wins, then the operator-selected port
        // persisted during first-boot setup, then boot-time autodetection.
        if !serial_port_pinned_by_env() {
            if let Some(persisted) = storage.get_read_serial_port().await? {
                let persisted = persisted.trim().to_owned();
                if !persisted.is_empty() && persisted != config.serial_port {
                    info!(
                        "using operator-selected read serial port {persisted} \
                         (overriding autodetected {})",
                        config.serial_port
                    );
                    config.serial_port = persisted;
                }
            }
        }

        let startup_health = collect_startup_health(&config);
        log_startup_health(&startup_health);
        if startup_health.has_errors() {
            return Err(anyhow::anyhow!(
                "startup checks failed: {}",
                startup_health
                    .checks
                    .iter()
                    .filter(|check| check.status == crate::health::HealthStatus::Error)
                    .map(|check| format!("{}: {}", check.name, check.summary))
                    .collect::<Vec<_>>()
                    .join("; ")
            ));
        }

        let calibration = storage
            .fetch_active_calibration()
            .await?
            .ok_or_else(|| anyhow::anyhow!("active calibration profile not found after storage init"))?;
        let calibration_validation = validate_profile(&calibration);

        for warning_message in &calibration_validation.warnings {
            warn!(
                profile_id = calibration.profile_id,
                profile_name = %calibration.name,
                "{warning_message}"
            );
        }

        if !calibration_validation.is_valid {
            return Err(anyhow::anyhow!(
                "active calibration profile {} is unusable: {}",
                calibration.name,
                calibration_validation.errors.join("; ")
            ));
        }
        let (calibration_tx, calibration_rx) = watch::channel(calibration.clone());
        let run_control = RunControl::new();
        let calibration_lock = CalibrationLock::with_storage(storage.clone()).await;
        let audit_logger = AuditLogger::new(storage.clone());

        let (live_tx, live_rx) = watch::channel::<LiveFrame>(FusionTask::idle_frame());
        let ws = WsTask::spawn(&config.ws_bind, live_rx.clone()).await?;
        let storage_task = StorageTask::spawn(storage.clone(), live_rx.clone());

        // Suspend/resume gate between the live serial reader and anything
        // needing exclusive port access (ESP32 flashing). Seed `actual=false`
        // and let the reader publish true only once it has successfully opened
        // the port.
        let (serial_gate_handle, serial_gate_worker) = serial_gate(false);

        let api = ApiTask::spawn(
            &config.api_bind,
            storage.clone(),
            calibration_tx.clone(),
            startup_health.clone(),
            run_control.clone(),
            calibration_lock,
            audit_logger,
            config.clone(),
            serial_gate_handle,
        )
        .await?;
        let state = StateMachine::new();

        let (serial, can, replay, bme280, fusion) = match config.source_mode {
            SourceMode::Live => {
                let esp32_config_manager = Esp32ConfigManager::from_runtime_config(&config);
                match esp32_config_manager
                    .synchronize_startup(&config.serial_port, config.serial_baud)
                    .await
                {
                    Ok(sync_result) => {
                        info!(
                            device_name = %sync_result.device_info.device_name,
                            firmware = format_args!(
                                "{}.{}.{}",
                                sync_result.device_info.firmware_major,
                                sync_result.device_info.firmware_minor,
                                sync_result.device_info.firmware_patch
                            ),
                            status = ?sync_result.status,
                            "completed ESP32 config sync"
                        );
                    }
                    Err(err) if err.is_retryable() => {
                        warn!(
                            "ESP32 config sync skipped during startup because the serial link is not ready: {err}"
                        );
                    }
                    Err(err) => {
                        return Err(anyhow::anyhow!(
                            "ESP32 config sync failed during startup: {err}"
                        ));
                    }
                }

                // ── Frame watch channel ───────────────────────────────────────
                //
                // `watch` holds the single latest frame value. The serial task
                // overwrites it on every new frame; consumers read the latest
                // value without queuing or drop policy.
                let (frame_tx, frame_rx) = watch::channel::<DynoFrameV1>(DynoFrameV1::default());
                let (ambient_tx, ambient_rx) =
                    watch::channel::<AmbientSample>(AmbientSample::stub());
                let (can_tx, can_rx) = watch::channel::<CanSample>(CanSample::missing());
                let serial = SerialTask::spawn(
                    &config,
                    frame_tx,
                    ambient_tx,
                    serial_gate_worker,
                    calibration_rx.clone(),
                );
                let can = CanTask::spawn(config.can_iface.clone(), can_tx);
                let fusion = FusionTask::spawn(
                    frame_rx,
                    ambient_rx,
                    can_rx,
                    live_tx,
                    config.correction_mode,
                    calibration_rx.clone(),
                    run_control.clone(),
                    config.arm_rpm,
                    config.record_rpm,
                    config.stop_rpm,
                    config.engine_noise_mains_hz,
                );
                (Some(serial), Some(can), None, None, Some(fusion))
            }
            SourceMode::Replay => {
                info!("starting in replay mode; UART, BME280, and fusion tasks are bypassed");
                let replay = ReplayTask::spawn(calibration_rx.clone(), live_tx);
                (None, None, Some(replay), None, None)
            }
        };

        Ok(Self {
            config,
            state,
            startup_health,
            _serial: serial,
            _can: can,
            _replay: replay,
            _live_rx: live_rx,
            _calibration_rx: calibration_rx,
            _run_control: run_control,
            _bme280:  bme280,
            _fusion:  fusion,
            _api:     api,
            _ws:      ws,
            _storage: storage,
            _storage_task: storage_task,
        })
    }

    pub fn active_calibration(&self) -> CalibrationProfile {
        self._calibration_rx.borrow().clone()
    }
}

/// True when `DYNO_SERIAL_PORT` is set to an explicit device (not `auto`),
/// meaning the operator/deployment pinned it and a persisted selection must
/// not override it.
fn serial_port_pinned_by_env() -> bool {
    std::env::var("DYNO_SERIAL_PORT")
        .map(|value| {
            let value = value.trim();
            !value.is_empty() && !value.eq_ignore_ascii_case(crate::detect::AUTO)
        })
        .unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusqlite::Connection;
    use std::sync::atomic::{AtomicU64, Ordering};

    use crate::correction::CorrectionMode;

    static NEXT_TEST_ID: AtomicU64 = AtomicU64::new(1);

    fn test_config(db_path: &str) -> Config {
        Config {
            serial_port: "/dev/null".to_owned(),
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
            bme280_enabled: false,
            source_mode: SourceMode::Replay,
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

    #[tokio::test]
    async fn startup_bootstraps_calibration_when_profiles_are_missing() {
        let unique = NEXT_TEST_ID.fetch_add(1, Ordering::Relaxed);
        let db_path = std::env::temp_dir().join(format!("dyno-app-startup-{unique}.sqlite"));
        let _ = std::fs::remove_file(&db_path);

        let app = App::start(test_config(&db_path.display().to_string()))
            .await
            .expect("start app");
        tokio::time::sleep(std::time::Duration::from_millis(25)).await;

        assert_eq!(app.active_calibration().name, "Default bootstrap profile");
        drop(app);

        let conn = Connection::open(&db_path).expect("open sqlite db");
        let calibration_count: i64 = conn
            .query_row("SELECT COUNT(*) FROM calibration_profiles", [], |row| row.get(0))
            .expect("count calibration profiles");
        assert_eq!(calibration_count, 1);

        let _ = std::fs::remove_file(db_path);
    }

    #[tokio::test]
    async fn startup_fails_when_database_path_is_unusable() {
        let unique = NEXT_TEST_ID.fetch_add(1, Ordering::Relaxed);
        let blocker = std::env::temp_dir().join(format!("dyno-app-health-block-{unique}"));
        std::fs::write(&blocker, "block").expect("create blocker file");
        let db_path = blocker.join("dyno.sqlite");

        // Storage now opens before the health snapshot (so the persisted
        // serial-port override is reflected in health), so an unusable db
        // path fails at storage open with a legible message.
        let err = App::start(test_config(&db_path.display().to_string()))
            .await
            .err()
            .expect("startup should fail");
        assert!(err.to_string().contains("could not open storage"), "got {err:#}");

        let _ = std::fs::remove_file(blocker);
    }

    #[tokio::test]
    async fn startup_health_reflects_persisted_read_serial_port_override() {
        let _guard = crate::test_env_lock();
        std::env::remove_var("DYNO_SERIAL_PORT");
        let unique = NEXT_TEST_ID.fetch_add(1, Ordering::Relaxed);
        let db_path = std::env::temp_dir().join(format!("dyno-app-override-{unique}.sqlite"));
        let _ = std::fs::remove_file(&db_path);

        // Persist an operator-selected read port before the app starts.
        let mut config = test_config(&db_path.display().to_string());
        let storage = Storage::open(&config).await.expect("open storage");
        storage
            .set_read_serial_port("/dev/dyno-override-test")
            .await
            .expect("persist read port");
        storage.flush().await.expect("flush");
        drop(storage);

        // Live mode so the health snapshot includes the serial_port check.
        // The missing device only degrades health; the ESP32 sync failure on
        // open is retryable and startup continues.
        config.source_mode = SourceMode::Live;
        config.serial_port = "/dev/dyno-autodetected-test".to_owned();
        let app = App::start(config).await.expect("start app");

        assert_eq!(app.config.serial_port, "/dev/dyno-override-test");
        let serial_check = app
            .startup_health
            .checks
            .iter()
            .find(|check| check.name == "serial_port")
            .expect("serial_port check present in live mode");
        assert!(
            serial_check.summary.contains("/dev/dyno-override-test"),
            "health must report the overridden port, got: {}",
            serial_check.summary
        );
        drop(app);

        let _ = std::fs::remove_file(db_path);
    }
}
