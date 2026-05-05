//! Lightweight JSON API for historical run queries.
//!
//! The API layer is intentionally thin: it maps HTTP routes to storage helper
//! calls and returns explicit DTOs, keeping SQLite access isolated inside the
//! storage module.

use axum::{
    extract::{Path, State},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use tokio::sync::watch;
use tokio::net::TcpListener;
use tokio::task::JoinHandle;
use tracing::{error, info};

use crate::calibration::{
    CalibrationProfile, CalibrationProfileEvent, CalibrationProfileEventType,
    CalibrationProfileInput, CalibrationValidation, validate_profile, validate_profile_input,
    validate_profile_name,
};
use crate::health::StartupHealth;
use crate::storage::{Storage, StoredFrame, StoredRun};

pub struct ApiTask {
    handle: JoinHandle<()>,
}

#[derive(Clone)]
struct ApiState {
    storage: Storage,
    calibration_tx: watch::Sender<CalibrationProfile>,
    startup_health: StartupHealth,
}

#[derive(Debug, Serialize)]
pub struct RunSummaryDto {
    pub run_id: i64,
    pub started_at_ms: i64,
    pub ended_at_ms: Option<i64>,
    pub date: String,
    pub source_mode: String,
    pub correction_mode: String,
    pub peak_power_hp: f32,
    pub peak_power_rpm: f32,
    pub peak_torque_nm: f32,
    pub peak_torque_rpm: f32,
}

#[derive(Debug, Serialize)]
pub struct RunDetailDto {
    pub run_id: i64,
    pub started_at_ms: i64,
    pub ended_at_ms: Option<i64>,
    pub date: String,
    pub source_mode: String,
    pub correction_mode: String,
    pub calibration_profile_id: Option<i64>,
    pub calibration_profile_name: Option<String>,
    pub roller_diameter_m: f32,
    pub encoder_pulses_per_rev: f32,
    pub roller_inertia_kg_m2: f32,
    pub sample_window_ms: u32,
    pub peak_power_hp: f32,
    pub peak_power_rpm: f32,
    pub peak_torque_nm: f32,
    pub peak_torque_rpm: f32,
}

#[derive(Debug, Serialize)]
pub struct FrameSeriesResponseDto {
    pub run_id: i64,
    pub frames: Vec<RunFrameDto>,
}

#[derive(Debug, Serialize)]
pub struct RunFrameDto {
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
    pub run_state: String,
}

#[derive(Debug, Deserialize, Serialize)]
pub struct CompareRunsRequestDto {
    pub run_ids: Vec<i64>,
}

#[derive(Debug, Serialize)]
pub struct CompareRunsResponseDto {
    pub runs: Vec<ComparedRunDto>,
}

#[derive(Debug, Serialize)]
pub struct ComparedRunDto {
    pub run: RunDetailDto,
    pub frames: Vec<RunFrameDto>,
}

#[derive(Debug, Serialize)]
pub struct DeleteRunResponseDto {
    pub run_id: i64,
    pub deleted: bool,
}

#[derive(Debug, Serialize)]
pub struct CalibrationResponseDto {
    pub profile: CalibrationProfileDto,
    pub validation: CalibrationValidation,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub activated: Option<bool>,
}

#[derive(Debug, Serialize)]
pub struct StartupHealthDto {
    pub status: String,
    pub source_mode: String,
    pub checks: Vec<crate::health::StartupCheck>,
}

#[derive(Debug, Serialize)]
pub struct CalibrationProfileDto {
    pub profile_id: i64,
    pub name: String,
    pub created_at_ms: i64,
    pub updated_at_ms: i64,
    pub is_active: bool,
    pub roller_diameter_m: f32,
    pub encoder_pulses_per_rev: f32,
    pub roller_inertia_kg_m2: f32,
    pub sample_window_ms: u64,
    pub engine_pulses_per_rev_hint: Option<f32>,
    pub engine_rpm_scale: Option<f32>,
    pub notes: Option<String>,
}

#[derive(Debug, Deserialize, Serialize)]
pub struct ActivateCalibrationRequestDto {
    pub profile_id: i64,
}

#[derive(Debug, Deserialize, Serialize)]
pub struct CalibrationUpsertRequestDto {
    pub name: String,
    pub roller_diameter_m: f32,
    pub encoder_pulses_per_rev: f32,
    pub roller_inertia_kg_m2: f32,
    pub sample_window_ms: u64,
    pub engine_pulses_per_rev_hint: Option<f32>,
    pub engine_rpm_scale: Option<f32>,
    pub notes: Option<String>,
    pub activate_after_save: Option<bool>,
}

#[derive(Debug, Deserialize, Serialize)]
pub struct DuplicateCalibrationProfileRequestDto {
    pub name: Option<String>,
    pub activate_after_save: Option<bool>,
}

#[derive(Debug, Serialize)]
pub struct CalibrationProfileEventDto {
    pub event_id: i64,
    pub profile_id: i64,
    pub event_type: CalibrationProfileEventType,
    pub created_at_ms: i64,
    pub summary: String,
    pub previous_values_json: Option<Value>,
    pub new_values_json: Option<Value>,
}

#[derive(Debug, Serialize)]
struct ErrorBody {
    error: String,
}

enum ApiError {
    NotFound(String),
    BadRequest(String),
    Internal(anyhow::Error),
}

impl ApiTask {
    pub fn spawn(
        bind_addr: &str,
        storage: Storage,
        calibration_tx: watch::Sender<CalibrationProfile>,
        startup_health: StartupHealth,
    ) -> Self {
        let bind_addr = bind_addr.to_owned();
        let handle = tokio::spawn(async move {
            api_task_loop(bind_addr, storage, calibration_tx, startup_health).await;
        });
        Self { handle }
    }
}

impl Drop for ApiTask {
    fn drop(&mut self) {
        self.handle.abort();
    }
}

pub fn router(
    storage: Storage,
    calibration_tx: watch::Sender<CalibrationProfile>,
    startup_health: StartupHealth,
) -> Router {
    Router::new()
        .route("/healthz", get(get_startup_health))
        .route("/api/calibration", get(get_active_calibration))
        .route(
            "/api/calibration/profiles",
            get(get_calibration_profiles).post(create_calibration_profile),
        )
        .route(
            "/api/calibration/profiles/:id",
            axum::routing::put(update_calibration_profile),
        )
        .route(
            "/api/calibration/profiles/:id/duplicate",
            post(duplicate_calibration_profile),
        )
        .route(
            "/api/calibration/profiles/:id/events",
            get(get_calibration_profile_events),
        )
        .route("/api/calibration/activate", post(activate_calibration))
        .route("/api/runs", get(get_runs))
        .route("/api/runs/compare", post(compare_runs))
        .route("/api/runs/:id", get(get_run).delete(delete_run))
        .route("/api/runs/:id/frames", get(get_run_frames))
        .with_state(ApiState {
            storage,
            calibration_tx,
            startup_health,
        })
}

async fn api_task_loop(
    bind_addr: String,
    storage: Storage,
    calibration_tx: watch::Sender<CalibrationProfile>,
    startup_health: StartupHealth,
) {
    let listener = match TcpListener::bind(&bind_addr).await {
        Ok(listener) => {
            info!("api: listening on {bind_addr}");
            listener
        }
        Err(err) => {
            error!("api: failed to bind {bind_addr}: {err}");
            return;
        }
    };

    if let Err(err) = axum::serve(
        listener,
        router(storage, calibration_tx, startup_health).into_make_service(),
    )
    .await
    {
        error!("api: server error on {bind_addr}: {err}");
    }
}

async fn get_startup_health(
    State(state): State<ApiState>,
) -> Result<Json<StartupHealthDto>, ApiError> {
    Ok(Json(startup_health_dto(state.startup_health)))
}

async fn get_runs(State(state): State<ApiState>) -> Result<Json<Vec<RunSummaryDto>>, ApiError> {
    let runs = state
        .storage
        .list_recent_runs(20)
        .await
        .map_err(ApiError::Internal)?;
    Ok(Json(runs.into_iter().map(run_summary_dto).collect()))
}

async fn get_active_calibration(
    State(state): State<ApiState>,
) -> Result<Json<CalibrationResponseDto>, ApiError> {
    let profile = state
        .storage
        .fetch_active_calibration()
        .await
        .map_err(ApiError::Internal)?
        .ok_or_else(|| ApiError::NotFound("active calibration profile not found".to_owned()))?;

    Ok(Json(CalibrationResponseDto {
        validation: validate_profile(&profile),
        profile: calibration_profile_dto(profile),
        activated: None,
    }))
}

async fn get_calibration_profiles(
    State(state): State<ApiState>,
) -> Result<Json<Vec<CalibrationProfileDto>>, ApiError> {
    let profiles = state
        .storage
        .list_calibration_profiles()
        .await
        .map_err(ApiError::Internal)?;
    Ok(Json(
        profiles.into_iter().map(calibration_profile_dto).collect(),
    ))
}

async fn create_calibration_profile(
    State(state): State<ApiState>,
    Json(request): Json<CalibrationUpsertRequestDto>,
) -> Result<Json<CalibrationResponseDto>, ApiError> {
    let input = calibration_profile_input(&request);
    let validation = validate_profile_input(&input);
    if !validation.is_valid {
        return Err(ApiError::BadRequest(format!(
            "calibration profile is invalid: {}",
            validation.errors.join("; ")
        )));
    }

    let change = state
        .storage
        .create_calibration_profile(input, request.activate_after_save.unwrap_or(false))
        .await
        .map_err(ApiError::Internal)?;
    maybe_publish_runtime_calibration(&state, &change.profile);

    Ok(Json(CalibrationResponseDto {
        profile: calibration_profile_dto(change.profile.clone()),
        validation: validate_profile(&change.profile),
        activated: Some(change.activated),
    }))
}

async fn update_calibration_profile(
    Path(profile_id): Path<i64>,
    State(state): State<ApiState>,
    Json(request): Json<CalibrationUpsertRequestDto>,
) -> Result<Json<CalibrationResponseDto>, ApiError> {
    let input = calibration_profile_input(&request);
    let validation = validate_profile_input(&input);
    if !validation.is_valid {
        return Err(ApiError::BadRequest(format!(
            "calibration profile is invalid: {}",
            validation.errors.join("; ")
        )));
    }

    let change = state
        .storage
        .update_calibration_profile(profile_id, input, request.activate_after_save.unwrap_or(false))
        .await
        .map_err(ApiError::Internal)?
        .ok_or_else(|| ApiError::NotFound(format!("calibration profile {profile_id} not found")))?;
    maybe_publish_runtime_calibration(&state, &change.profile);

    Ok(Json(CalibrationResponseDto {
        profile: calibration_profile_dto(change.profile.clone()),
        validation: validate_profile(&change.profile),
        activated: Some(change.activated),
    }))
}

async fn duplicate_calibration_profile(
    Path(profile_id): Path<i64>,
    State(state): State<ApiState>,
    Json(request): Json<DuplicateCalibrationProfileRequestDto>,
) -> Result<Json<CalibrationResponseDto>, ApiError> {
    if let Some(name) = request.name.as_deref() {
        let name_validation = validate_profile_name(name);
        if !name_validation.is_valid() {
            return Err(ApiError::BadRequest(format!(
                "duplicate calibration profile name is invalid: {}",
                name_validation.errors.join("; ")
            )));
        }
    }

    let change = state
        .storage
        .duplicate_calibration_profile(
            profile_id,
            request.name.clone(),
            request.activate_after_save.unwrap_or(false),
        )
        .await
        .map_err(ApiError::Internal)?
        .ok_or_else(|| ApiError::NotFound(format!("calibration profile {profile_id} not found")))?;
    maybe_publish_runtime_calibration(&state, &change.profile);

    Ok(Json(CalibrationResponseDto {
        profile: calibration_profile_dto(change.profile.clone()),
        validation: validate_profile(&change.profile),
        activated: Some(change.activated),
    }))
}

async fn get_calibration_profile_events(
    Path(profile_id): Path<i64>,
    State(state): State<ApiState>,
) -> Result<Json<Vec<CalibrationProfileEventDto>>, ApiError> {
    let profile_exists = state
        .storage
        .fetch_calibration_profile(profile_id)
        .await
        .map_err(ApiError::Internal)?
        .is_some();
    if !profile_exists {
        return Err(ApiError::NotFound(format!(
            "calibration profile {profile_id} not found"
        )));
    }

    let events = state
        .storage
        .list_calibration_profile_events(profile_id)
        .await
        .map_err(ApiError::Internal)?;
    Ok(Json(
        events
            .into_iter()
            .map(calibration_profile_event_dto)
            .collect(),
    ))
}

async fn activate_calibration(
    State(state): State<ApiState>,
    Json(request): Json<ActivateCalibrationRequestDto>,
) -> Result<Json<CalibrationResponseDto>, ApiError> {
    let profile = state
        .storage
        .fetch_calibration_profile(request.profile_id)
        .await
        .map_err(ApiError::Internal)?
        .ok_or_else(|| ApiError::NotFound(format!("calibration profile {} not found", request.profile_id)))?;
    let validation = validate_profile(&profile);

    if !validation.is_valid {
        return Err(ApiError::BadRequest(format!(
            "calibration profile {} is invalid: {}",
            profile.name,
            validation.errors.join("; ")
        )));
    }

    let changed = state
        .storage
        .set_active_calibration(profile.profile_id)
        .await
        .map_err(ApiError::Internal)?;
    if !changed {
        return Err(ApiError::NotFound(format!(
            "calibration profile {} not found",
            request.profile_id
        )));
    }

    let active_profile = state
        .storage
        .fetch_active_calibration()
        .await
        .map_err(ApiError::Internal)?
        .ok_or_else(|| ApiError::Internal(anyhow::anyhow!("active calibration profile missing after activation")))?;
    let validation = validate_profile(&active_profile);
    state.calibration_tx.send_replace(active_profile.clone());

    Ok(Json(CalibrationResponseDto {
        profile: calibration_profile_dto(active_profile),
        validation,
        activated: Some(true),
    }))
}

async fn get_run(
    Path(run_id): Path<i64>,
    State(state): State<ApiState>,
) -> Result<Json<RunDetailDto>, ApiError> {
    let run = state
        .storage
        .fetch_run(run_id)
        .await
        .map_err(ApiError::Internal)?
        .ok_or_else(|| ApiError::NotFound(format!("run {run_id} not found")))?;
    Ok(Json(run_detail_dto(run)))
}

async fn get_run_frames(
    Path(run_id): Path<i64>,
    State(state): State<ApiState>,
) -> Result<Json<FrameSeriesResponseDto>, ApiError> {
    let run = state
        .storage
        .fetch_run(run_id)
        .await
        .map_err(ApiError::Internal)?;
    if run.is_none() {
        return Err(ApiError::NotFound(format!("run {run_id} not found")));
    }

    let frames = state
        .storage
        .fetch_frames(run_id)
        .await
        .map_err(ApiError::Internal)?;

    Ok(Json(FrameSeriesResponseDto {
        run_id,
        frames: frames.into_iter().map(run_frame_dto).collect(),
    }))
}

async fn compare_runs(
    State(state): State<ApiState>,
    Json(request): Json<CompareRunsRequestDto>,
) -> Result<Json<CompareRunsResponseDto>, ApiError> {
    if request.run_ids.is_empty() || request.run_ids.len() > 4 {
        return Err(ApiError::BadRequest(
            "run_ids must contain between 1 and 4 run IDs".to_owned(),
        ));
    }

    let mut runs = Vec::with_capacity(request.run_ids.len());
    for run_id in request.run_ids {
        let run = state
            .storage
            .fetch_run(run_id)
            .await
            .map_err(ApiError::Internal)?
            .ok_or_else(|| ApiError::NotFound(format!("run {run_id} not found")))?;
        let frames = state
            .storage
            .fetch_frames(run_id)
            .await
            .map_err(ApiError::Internal)?;

        runs.push(ComparedRunDto {
            run: run_detail_dto(run),
            frames: frames.into_iter().map(run_frame_dto).collect(),
        });
    }

    Ok(Json(CompareRunsResponseDto { runs }))
}

async fn delete_run(
    Path(run_id): Path<i64>,
    State(state): State<ApiState>,
) -> Result<Json<DeleteRunResponseDto>, ApiError> {
    let deleted = state
        .storage
        .delete_run(run_id)
        .await
        .map_err(ApiError::Internal)?;
    if !deleted {
        return Err(ApiError::NotFound(format!("run {run_id} not found")));
    }
    Ok(Json(DeleteRunResponseDto { run_id, deleted }))
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let (status, message) = match self {
            Self::NotFound(message) => (StatusCode::NOT_FOUND, message),
            Self::BadRequest(message) => (StatusCode::BAD_REQUEST, message),
            Self::Internal(err) => {
                error!("api: request failed: {err:#}");
                (StatusCode::INTERNAL_SERVER_ERROR, "internal server error".to_owned())
            }
        };

        (status, Json(ErrorBody { error: message })).into_response()
    }
}

fn run_summary_dto(run: StoredRun) -> RunSummaryDto {
    RunSummaryDto {
        run_id: run.run_id,
        started_at_ms: run.started_at_ms,
        ended_at_ms: run.ended_at_ms,
        date: format_started_at_ms(run.started_at_ms),
        source_mode: run.source_mode.to_string(),
        correction_mode: run.correction_mode.to_string(),
        peak_power_hp: run.peak_power_hp,
        peak_power_rpm: run.peak_power_rpm,
        peak_torque_nm: run.peak_torque_nm,
        peak_torque_rpm: run.peak_torque_rpm,
    }
}

fn calibration_profile_input(request: &CalibrationUpsertRequestDto) -> CalibrationProfileInput {
    CalibrationProfileInput {
        name: request.name.clone(),
        roller_diameter_m: request.roller_diameter_m,
        encoder_pulses_per_rev: request.encoder_pulses_per_rev,
        roller_inertia_kg_m2: request.roller_inertia_kg_m2,
        sample_window_ms: request.sample_window_ms,
        engine_pulses_per_rev_hint: request.engine_pulses_per_rev_hint,
        engine_rpm_scale: request.engine_rpm_scale,
        notes: request.notes.clone(),
    }
}

fn calibration_profile_dto(profile: CalibrationProfile) -> CalibrationProfileDto {
    CalibrationProfileDto {
        profile_id: profile.profile_id,
        name: profile.name,
        created_at_ms: profile.created_at_ms,
        updated_at_ms: profile.updated_at_ms,
        is_active: profile.is_active,
        roller_diameter_m: profile.roller_diameter_m,
        encoder_pulses_per_rev: profile.encoder_pulses_per_rev,
        roller_inertia_kg_m2: profile.roller_inertia_kg_m2,
        sample_window_ms: profile.sample_window_ms,
        engine_pulses_per_rev_hint: profile.engine_pulses_per_rev_hint,
        engine_rpm_scale: profile.engine_rpm_scale,
        notes: profile.notes,
    }
}

fn calibration_profile_event_dto(event: CalibrationProfileEvent) -> CalibrationProfileEventDto {
    CalibrationProfileEventDto {
        event_id: event.event_id,
        profile_id: event.profile_id,
        event_type: event.event_type,
        created_at_ms: event.created_at_ms,
        summary: event.summary,
        previous_values_json: event.previous_values_json,
        new_values_json: event.new_values_json,
    }
}

fn maybe_publish_runtime_calibration(state: &ApiState, profile: &CalibrationProfile) {
    if profile.is_active {
        state.calibration_tx.send_replace(profile.clone());
    }
}

fn startup_health_dto(health: StartupHealth) -> StartupHealthDto {
    StartupHealthDto {
        status: match health.status {
            crate::health::HealthStatus::Ok => "ok".to_owned(),
            crate::health::HealthStatus::Degraded => "degraded".to_owned(),
            crate::health::HealthStatus::Error => "error".to_owned(),
        },
        source_mode: health.source_mode,
        checks: health.checks,
    }
}

fn run_detail_dto(run: StoredRun) -> RunDetailDto {
    RunDetailDto {
        run_id: run.run_id,
        started_at_ms: run.started_at_ms,
        ended_at_ms: run.ended_at_ms,
        date: format_started_at_ms(run.started_at_ms),
        source_mode: run.source_mode.to_string(),
        correction_mode: run.correction_mode.to_string(),
        calibration_profile_id: run.calibration_profile_id,
        calibration_profile_name: run.calibration_profile_name,
        roller_diameter_m: run.roller_diameter_m,
        encoder_pulses_per_rev: run.encoder_pulses_per_rev,
        roller_inertia_kg_m2: run.roller_inertia_kg_m2,
        sample_window_ms: run.sample_window_ms,
        peak_power_hp: run.peak_power_hp,
        peak_power_rpm: run.peak_power_rpm,
        peak_torque_nm: run.peak_torque_nm,
        peak_torque_rpm: run.peak_torque_rpm,
    }
}

fn run_frame_dto(frame: StoredFrame) -> RunFrameDto {
    RunFrameDto {
        run_id: frame.run_id,
        ts_ms: frame.ts_ms,
        engine_rpm: frame.engine_rpm,
        roller_rpm: frame.roller_rpm,
        speed_kmh: frame.speed_kmh,
        power_hp: frame.power_hp,
        torque_nm: frame.torque_nm,
        afr: frame.afr,
        lambda: frame.lambda,
        ambient_temp_c: frame.ambient_temp_c,
        humidity_pct: frame.humidity_pct,
        pressure_hpa: frame.pressure_hpa,
        correction_factor: frame.correction_factor,
        run_state: frame.run_state.to_string(),
    }
}

fn format_started_at_ms(started_at_ms: i64) -> String {
    chrono::DateTime::<chrono::Utc>::from_timestamp_millis(started_at_ms)
        .map(|date| date.to_rfc3339())
        .unwrap_or_else(|| started_at_ms.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{
        body::{to_bytes, Body},
        http::{Method, Request},
    };
    use rusqlite::{params, Connection};
    use serde_json::Value;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU64, Ordering};
    use tower::util::ServiceExt;

    use crate::{
        config::{Config, SourceMode},
        correction::CorrectionMode,
        health::collect_startup_health,
    };
    use dyno_types::{Esp32TelemetryStatus, LiveFrame, RunState};

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
            db_path: db_path.to_owned(),
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

    fn sample_frame(ts_ms: i64, run_state: RunState, engine_rpm: f32, power_hp: Option<f32>, torque_nm: Option<f32>) -> LiveFrame {
        LiveFrame {
            ts_ms,
            engine_rpm: Some(engine_rpm),
            roller_rpm: Some(engine_rpm / 4.0),
            speed_kmh: Some(engine_rpm / 60.0),
            power_hp,
            torque_nm,
            correction_factor: 1.02,
            afr: Some(13.1),
            lambda: Some(0.89),
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

    async fn seeded_storage(label: &str) -> (Storage, PathBuf) {
        let unique = NEXT_TEST_ID.fetch_add(1, Ordering::Relaxed);
        let db_path = std::env::temp_dir().join(format!("dyno-api-{label}-{unique}.sqlite"));
        let _ = std::fs::remove_file(&db_path);
        let storage = Storage::open(&test_config(&db_path.display().to_string()))
            .await
            .expect("open storage");
        storage
            .record_live_frame(sample_frame(1000, RunState::Recording, 2800.0, Some(42.0), Some(110.0)))
            .await
            .expect("record 1");
        storage
            .record_live_frame(sample_frame(1100, RunState::Recording, 4200.0, Some(88.0), Some(132.0)))
            .await
            .expect("record 2");
        storage
            .record_live_frame(sample_frame(1200, RunState::Stopping, 2500.0, Some(30.0), Some(90.0)))
            .await
            .expect("record 3");
        storage
            .record_live_frame(sample_frame(1300, RunState::Idle, 1000.0, None, None))
            .await
            .expect("record 4");
        storage.flush().await.expect("flush");
        (storage, db_path)
    }

    async fn test_router(storage: Storage) -> axum::Router {
        let active = storage
            .fetch_active_calibration()
            .await
            .expect("fetch active calibration")
            .expect("active calibration");
        let (calibration_tx, _calibration_rx) = watch::channel(active);
        let health = collect_startup_health(&test_config(":memory:"));
        router(storage, calibration_tx, health)
    }

    #[tokio::test]
    async fn api_task_spawn_smoke_test() {
        let unique = NEXT_TEST_ID.fetch_add(1, Ordering::Relaxed);
        let db_path = std::env::temp_dir().join(format!("dyno-api-smoke-{unique}.sqlite"));
        let _ = std::fs::remove_file(&db_path);
        let storage = Storage::open(&test_config(&db_path.display().to_string()))
            .await
            .expect("open storage");
        let active = storage
            .fetch_active_calibration()
            .await
            .expect("fetch active calibration")
            .expect("active calibration");
        let (calibration_tx, _calibration_rx) = watch::channel(active);
        let health = collect_startup_health(&test_config(&db_path.display().to_string()));
        let task = ApiTask::spawn("127.0.0.1:0", storage, calibration_tx, health);
        tokio::time::sleep(std::time::Duration::from_millis(25)).await;
        drop(task);
    }

    #[tokio::test]
    async fn health_endpoint_returns_startup_checks() {
        let (storage, db_path) = seeded_storage("health").await;
        let active = storage
            .fetch_active_calibration()
            .await
            .expect("fetch active calibration")
            .expect("active calibration");
        let (calibration_tx, _calibration_rx) = watch::channel(active);
        let health = collect_startup_health(&test_config(&db_path.display().to_string()));
        let app = router(storage, calibration_tx, health);

        let response = app
            .oneshot(Request::builder().uri("/healthz").body(Body::empty()).unwrap())
            .await
            .expect("health response");

        assert_eq!(response.status(), StatusCode::OK);
        let body = to_bytes(response.into_body(), usize::MAX)
            .await
            .expect("health bytes");
        let json: Value = serde_json::from_slice(&body).expect("health json");
        assert_eq!(json["status"], "ok");
        assert_eq!(json["source_mode"], "replay");
        assert!(json["checks"].as_array().expect("checks array").len() >= 1);
        assert_eq!(json["checks"][0]["name"], "database_path");
    }

    #[tokio::test]
    async fn get_runs_and_frames_have_expected_shape() {
        let (storage, _db_path) = seeded_storage("shape").await;
        let app = test_router(storage.clone()).await;

        let runs_response = app
            .clone()
            .oneshot(Request::builder().uri("/api/runs").body(Body::empty()).unwrap())
            .await
            .expect("runs response");
        assert_eq!(runs_response.status(), StatusCode::OK);
        let runs_body = to_bytes(runs_response.into_body(), usize::MAX).await.expect("runs bytes");
        let runs_json: Value = serde_json::from_slice(&runs_body).expect("runs json");

        let first_run_id = runs_json[0]["run_id"].as_i64().expect("run id");
        assert_eq!(runs_json[0]["source_mode"], "replay");
        assert_eq!(runs_json[0]["correction_mode"], "sae_j1349");
        assert_eq!(runs_json[0]["peak_power_hp"], 88.0);

        let frames_response = app
            .oneshot(
                Request::builder()
                    .uri(format!("/api/runs/{first_run_id}/frames"))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .expect("frames response");
        assert_eq!(frames_response.status(), StatusCode::OK);
        let frames_body = to_bytes(frames_response.into_body(), usize::MAX).await.expect("frames bytes");
        let frames_json: Value = serde_json::from_slice(&frames_body).expect("frames json");

        assert_eq!(frames_json["run_id"], first_run_id);
        assert_eq!(frames_json["frames"].as_array().expect("frames array").len(), 3);
        assert_eq!(frames_json["frames"][0]["run_state"], "recording");
        assert_eq!(frames_json["frames"][2]["run_state"], "stopping");
    }

    #[tokio::test]
    async fn calibration_endpoint_returns_active_profile_and_validation() {
        let (storage, _db_path) = seeded_storage("calibration").await;
        let app = test_router(storage).await;

        let response = app
            .oneshot(
                Request::builder()
                    .uri("/api/calibration")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .expect("calibration response");

        assert_eq!(response.status(), StatusCode::OK);
        let body = to_bytes(response.into_body(), usize::MAX)
            .await
            .expect("calibration bytes");
        let json: Value = serde_json::from_slice(&body).expect("calibration json");

        assert_eq!(json["profile"]["name"], "Default bootstrap profile");
        assert_eq!(json["profile"]["roller_diameter_m"], 0.318);
        assert_eq!(json["profile"]["sample_window_ms"], 100);
        assert_eq!(json["validation"]["is_valid"], true);
        assert!(json["validation"]["warnings"]
            .as_array()
            .expect("warnings array")
            .is_empty());
    }

    #[tokio::test]
    async fn activation_endpoint_switches_active_profile_and_returns_warnings() {
        let (storage, db_path) = seeded_storage("activate").await;
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
                "Large roller profile",
                2_000_i64,
                2_000_i64,
                1.2_f32,
                48.0_f32,
                4.0_f32,
                100_i64,
                "warning-only profile",
            ],
        )
        .expect("insert warning-only profile");
        let profile_id = conn.last_insert_rowid();
        drop(conn);

        let (calibration_tx, mut calibration_rx) = watch::channel(
            storage
                .fetch_active_calibration()
                .await
                .expect("fetch active calibration")
                .expect("active calibration"),
        );
        let health = collect_startup_health(&test_config(&db_path.display().to_string()));
        let app = router(storage.clone(), calibration_tx, health);

        let request = Request::builder()
            .method(Method::POST)
            .uri("/api/calibration/activate")
            .header("content-type", "application/json")
            .body(Body::from(
                serde_json::to_vec(&ActivateCalibrationRequestDto { profile_id }).unwrap(),
            ))
            .unwrap();
        let response = app.oneshot(request).await.expect("activate response");

        assert_eq!(response.status(), StatusCode::OK);
        calibration_rx.changed().await.expect("calibration update");
        assert_eq!(calibration_rx.borrow().profile_id, profile_id);

        let body = to_bytes(response.into_body(), usize::MAX).await.expect("activate bytes");
        let json: Value = serde_json::from_slice(&body).expect("activate json");
        assert_eq!(json["profile"]["profile_id"], profile_id);
        assert_eq!(json["profile"]["is_active"], true);
        assert_eq!(json["validation"]["is_valid"], true);
        assert_eq!(json["validation"]["warnings"].as_array().expect("warnings").len(), 1);

        let active = storage
            .fetch_active_calibration()
            .await
            .expect("fetch active calibration")
            .expect("active calibration");
        assert_eq!(active.profile_id, profile_id);
    }

    #[tokio::test]
    async fn activation_endpoint_rejects_invalid_profile() {
        let (storage, db_path) = seeded_storage("activate-invalid").await;
        let conn = Connection::open(&db_path).expect("open db for invalid calibration insert");
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
                "Broken profile",
                3_000_i64,
                3_000_i64,
                0.0_f32,
                48.0_f32,
                4.0_f32,
                100_i64,
                "invalid profile",
            ],
        )
        .expect("insert invalid profile");
        let profile_id = conn.last_insert_rowid();
        drop(conn);

        let initial_active = storage
            .fetch_active_calibration()
            .await
            .expect("fetch active calibration")
            .expect("active calibration");
        let (calibration_tx, calibration_rx) = watch::channel(initial_active.clone());
        let health = collect_startup_health(&test_config(&db_path.display().to_string()));
        let app = router(storage.clone(), calibration_tx, health);

        let request = Request::builder()
            .method(Method::POST)
            .uri("/api/calibration/activate")
            .header("content-type", "application/json")
            .body(Body::from(
                serde_json::to_vec(&ActivateCalibrationRequestDto { profile_id }).unwrap(),
            ))
            .unwrap();
        let response = app.oneshot(request).await.expect("activate invalid response");

        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
        let body = to_bytes(response.into_body(), usize::MAX)
            .await
            .expect("invalid activate bytes");
        let json: Value = serde_json::from_slice(&body).expect("invalid activate json");
        assert!(json["error"]
            .as_str()
            .expect("error string")
            .contains("invalid"));
        assert_eq!(calibration_rx.borrow().profile_id, initial_active.profile_id);

        let active = storage
            .fetch_active_calibration()
            .await
            .expect("fetch active calibration")
            .expect("active calibration");
        assert_eq!(active.profile_id, initial_active.profile_id);
    }

    #[tokio::test]
    async fn create_profile_endpoint_returns_profile_validation_and_event() {
        let (storage, _db_path) = seeded_storage("create-profile").await;
        let app = test_router(storage.clone()).await;

        let request = Request::builder()
            .method(Method::POST)
            .uri("/api/calibration/profiles")
            .header("content-type", "application/json")
            .body(Body::from(
                serde_json::to_vec(&CalibrationUpsertRequestDto {
                    name: "Street tune".to_owned(),
                    roller_diameter_m: 0.325,
                    encoder_pulses_per_rev: 72.0,
                    roller_inertia_kg_m2: 3.8,
                    sample_window_ms: 90,
                    engine_pulses_per_rev_hint: Some(1.0),
                    engine_rpm_scale: Some(1.0),
                    notes: Some("new profile".to_owned()),
                    activate_after_save: Some(false),
                })
                .unwrap(),
            ))
            .unwrap();
        let response = app.oneshot(request).await.expect("create profile response");

        assert_eq!(response.status(), StatusCode::OK);
        let body = to_bytes(response.into_body(), usize::MAX)
            .await
            .expect("create profile bytes");
        let json: Value = serde_json::from_slice(&body).expect("create profile json");
        assert_eq!(json["profile"]["name"], "Street tune");
        assert_eq!(json["validation"]["is_valid"], true);
        assert_eq!(json["activated"], false);

        let profile_id = json["profile"]["profile_id"].as_i64().expect("profile id");
        let events = storage
            .list_calibration_profile_events(profile_id)
            .await
            .expect("list profile events");
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].event_type.to_string(), "created");
    }

    #[tokio::test]
    async fn create_profile_endpoint_rejects_invalid_payload() {
        let (storage, _db_path) = seeded_storage("create-invalid").await;
        let app = test_router(storage).await;

        let request = Request::builder()
            .method(Method::POST)
            .uri("/api/calibration/profiles")
            .header("content-type", "application/json")
            .body(Body::from(
                serde_json::to_vec(&CalibrationUpsertRequestDto {
                    name: "Broken".to_owned(),
                    roller_diameter_m: 0.0,
                    encoder_pulses_per_rev: 72.0,
                    roller_inertia_kg_m2: 3.8,
                    sample_window_ms: 90,
                    engine_pulses_per_rev_hint: Some(1.0),
                    engine_rpm_scale: Some(1.0),
                    notes: None,
                    activate_after_save: Some(false),
                })
                .unwrap(),
            ))
            .unwrap();
        let response = app
            .oneshot(request)
            .await
            .expect("create invalid response");

        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
        let body = to_bytes(response.into_body(), usize::MAX)
            .await
            .expect("create invalid bytes");
        let json: Value = serde_json::from_slice(&body).expect("create invalid json");
        assert!(json["error"].as_str().expect("error").contains("invalid"));
    }

    #[tokio::test]
    async fn update_profile_endpoint_emits_audit_event() {
        let (storage, _db_path) = seeded_storage("update-profile").await;
        let original = storage
            .fetch_active_calibration()
            .await
            .expect("fetch active calibration")
            .expect("active calibration");
        let app = test_router(storage.clone()).await;

        let request = Request::builder()
            .method(Method::PUT)
            .uri(format!("/api/calibration/profiles/{}", original.profile_id))
            .header("content-type", "application/json")
            .body(Body::from(
                serde_json::to_vec(&CalibrationUpsertRequestDto {
                    name: "Updated profile".to_owned(),
                    roller_diameter_m: 0.329,
                    encoder_pulses_per_rev: 64.0,
                    roller_inertia_kg_m2: 4.0,
                    sample_window_ms: 110,
                    engine_pulses_per_rev_hint: Some(1.0),
                    engine_rpm_scale: Some(1.0),
                    notes: Some("updated".to_owned()),
                    activate_after_save: Some(false),
                })
                .unwrap(),
            ))
            .unwrap();
        let response = app.oneshot(request).await.expect("update profile response");

        assert_eq!(response.status(), StatusCode::OK);
        let body = to_bytes(response.into_body(), usize::MAX)
            .await
            .expect("update profile bytes");
        let json: Value = serde_json::from_slice(&body).expect("update profile json");
        assert_eq!(json["profile"]["name"], "Updated profile");
        assert_eq!(json["profile"]["is_active"], true);
        assert_eq!(json["activated"], false);

        let events = storage
            .list_calibration_profile_events(original.profile_id)
            .await
            .expect("list updated profile events");
        assert_eq!(events[0].event_type.to_string(), "updated");
    }

    #[tokio::test]
    async fn duplicate_profile_endpoint_and_events_endpoint_return_expected_shape() {
        let (storage, _db_path) = seeded_storage("duplicate-profile").await;
        let original = storage
            .fetch_active_calibration()
            .await
            .expect("fetch active calibration")
            .expect("active calibration");
        let app = test_router(storage).await;

        let duplicate_request = Request::builder()
            .method(Method::POST)
            .uri(format!(
                "/api/calibration/profiles/{}/duplicate",
                original.profile_id
            ))
            .header("content-type", "application/json")
            .body(Body::from(
                serde_json::to_vec(&DuplicateCalibrationProfileRequestDto {
                    name: None,
                    activate_after_save: Some(false),
                })
                .unwrap(),
            ))
            .unwrap();
        let duplicate_response = app
            .clone()
            .oneshot(duplicate_request)
            .await
            .expect("duplicate response");

        assert_eq!(duplicate_response.status(), StatusCode::OK);
        let duplicate_body = to_bytes(duplicate_response.into_body(), usize::MAX)
            .await
            .expect("duplicate bytes");
        let duplicate_json: Value = serde_json::from_slice(&duplicate_body).expect("duplicate json");
        let duplicated_profile_id = duplicate_json["profile"]["profile_id"]
            .as_i64()
            .expect("duplicated profile id");
        assert_eq!(duplicate_json["profile"]["name"], "Default bootstrap profile-1");

        let events_request = Request::builder()
            .uri(format!(
                "/api/calibration/profiles/{duplicated_profile_id}/events"
            ))
            .body(Body::empty())
            .unwrap();
        let events_response = app
            .oneshot(events_request)
            .await
            .expect("events response");

        assert_eq!(events_response.status(), StatusCode::OK);
        let events_body = to_bytes(events_response.into_body(), usize::MAX)
            .await
            .expect("events bytes");
        let events_json: Value = serde_json::from_slice(&events_body).expect("events json");
        assert_eq!(events_json.as_array().expect("events array").len(), 1);
        assert_eq!(events_json[0]["event_type"], "duplicated");
        assert!(events_json[0]["summary"]
            .as_str()
            .expect("summary")
            .contains("Duplicated profile"));
    }

    #[tokio::test]
    async fn compare_endpoint_returns_summary_and_frames() {
        let (storage, _db_path) = seeded_storage("compare").await;
        let run_id = storage
            .list_recent_runs(1)
            .await
            .expect("list runs")[0]
            .run_id;
        let app = test_router(storage).await;

        let request = Request::builder()
            .method(Method::POST)
            .uri("/api/runs/compare")
            .header("content-type", "application/json")
            .body(Body::from(
                serde_json::to_vec(&CompareRunsRequestDto { run_ids: vec![run_id] }).unwrap(),
            ))
            .unwrap();
        let response = app.oneshot(request).await.expect("compare response");

        assert_eq!(response.status(), StatusCode::OK);
        let body = to_bytes(response.into_body(), usize::MAX).await.expect("compare bytes");
        let json: Value = serde_json::from_slice(&body).expect("compare json");
        assert_eq!(json["runs"].as_array().expect("runs").len(), 1);
        assert_eq!(json["runs"][0]["run"]["run_id"], run_id);
        assert_eq!(
            json["runs"][0]["run"]["calibration_profile_name"],
            "Default bootstrap profile"
        );
        assert_eq!(json["runs"][0]["frames"].as_array().expect("frames").len(), 3);
    }
}
