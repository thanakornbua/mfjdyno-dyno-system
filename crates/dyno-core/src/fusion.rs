//! Data fusion: combines ESP32 frames with ambient data into `LiveFrame`.
//!
//! This task keeps only the minimal local state needed for inertial dyno
//! physics: the prior roller angular velocity and source timestamp.

use tokio::sync::watch;
use tracing::{debug, info};

use dyno_protocol::{
    DynoFrameV1, CAN_STATUS_BUS_OFF, CAN_STATUS_NO_DATA, CAN_STATUS_NOT_INIT, CAN_STATUS_STALE,
    FLT_CAN_BUS_OFF, FLT_CAN_INIT, FLT_CONFIG_INVALID, FLT_ENCODER_INIT, FLT_ENGINE_INIT,
    FLT_UART_OVERRUN, SIG_AFR_VALID, SIG_CAN_ACTIVE, SIG_ENGINE_STALL, SIG_ENGINE_VALID,
    SIG_ROLLER_STOP, SIG_ROLLER_VALID,
};
use dyno_types::{AlertLevel, Esp32TelemetryStatus, FaultCode, LiveAlerts, LiveFrame, RunState};

use crate::{
    bme280::AmbientSample,
    calibration::CalibrationProfile,
    can::CanSample,
    correction::{correction_factor, CorrectionMode},
    run_control::RunControl,
    physics::{
        angular_accel_rad_s2, apply_correction, inertial_power_w, roller_rpm_from_encoder_delta,
        rpm_to_rad_s, speed_kmh_from_roller_rpm, torque_nm_from_power_and_omega, watts_to_hp,
    },
};

/// Number of early frames to log for bring-up.
const DEBUG_LOG_FRAMES: u64 = 5;

#[derive(Debug, Clone, Copy)]
pub struct FusionPhysicsConfig {
    roller_diameter_m: f32,
    encoder_pulses_per_rev: f32,
    sample_window_s: f32,
    roller_inertia_kg_m2: f32,
}

impl FusionPhysicsConfig {
    pub fn from_calibration(profile: &CalibrationProfile) -> Self {
        Self {
            roller_diameter_m: profile.roller_diameter_m,
            encoder_pulses_per_rev: profile.encoder_pulses_per_rev,
            sample_window_s: profile.sample_window_ms as f32 / 1_000.0,
            roller_inertia_kg_m2: profile.roller_inertia_kg_m2,
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub struct RunThresholds {
    record_rpm: f32,
    /// Mains frequency (Hz) for phantom-RPM rejection; `0` disables. See
    /// [`reject_mains_noise`].
    mains_noise_hz: f32,
}

/// Half-width (RPM) of the band around each mains harmonic treated as noise.
/// Wide enough to catch mains hum (which is rock-steady) without reaching a
/// real engine holding a nearby RPM.
const MAINS_NOISE_BAND_RPM: f32 = 120.0;

/// True if `rpm` sits within [`MAINS_NOISE_BAND_RPM`] of an integer harmonic of
/// the mains fundamental (`mains_hz * 60` RPM). A floating ignition input reads
/// mains hum as a steady harmonic (e.g. 50 Hz → 3000/6000/9000 RPM).
fn is_mains_harmonic(rpm: f32, mains_hz: f32) -> bool {
    if mains_hz <= 0.0 || !rpm.is_finite() {
        return false;
    }
    let fundamental = mains_hz * 60.0;
    if rpm < fundamental * 0.5 {
        return false;
    }
    let nearest = (rpm / fundamental).round() * fundamental;
    (rpm - nearest).abs() <= MAINS_NOISE_BAND_RPM
}

/// Suppress an engine RPM reading that is almost certainly mains hum on a
/// floating ignition input: it lands on a mains harmonic *and* the roller is
/// stationary. The roller guard is what keeps this from ever touching a real
/// pull — during a pull the roller is always turning.
fn reject_mains_noise(
    engine_rpm: Option<f32>,
    roller_stationary: bool,
    mains_hz: f32,
) -> Option<f32> {
    match engine_rpm {
        Some(rpm) if roller_stationary && is_mains_harmonic(rpm, mains_hz) => None,
        other => other,
    }
}

/// Converts the latest ESP32 frame into a frontend-ready `LiveFrame`.
pub struct FusionTask {
    frame_rx: watch::Receiver<DynoFrameV1>,
    ambient_rx: watch::Receiver<AmbientSample>,
    can_rx: watch::Receiver<CanSample>,
    tx: watch::Sender<LiveFrame>,
    correction_mode: CorrectionMode,
    calibration_rx: watch::Receiver<CalibrationProfile>,
    run_control: RunControl,
    run_thresholds: RunThresholds,
}

impl FusionTask {
    /// Spawn the fusion loop.
    pub fn spawn(
        frame_rx: watch::Receiver<DynoFrameV1>,
        ambient_rx: watch::Receiver<AmbientSample>,
        can_rx: watch::Receiver<CanSample>,
        tx: watch::Sender<LiveFrame>,
        correction_mode: CorrectionMode,
        calibration_rx: watch::Receiver<CalibrationProfile>,
        run_control: RunControl,
        _arm_rpm: f32,
        record_rpm: f32,
        _stop_rpm: f32,
        engine_noise_mains_hz: f32,
    ) -> Self {
        let task = Self {
            frame_rx,
            ambient_rx,
            can_rx,
            tx,
            correction_mode,
            calibration_rx,
            run_control,
            run_thresholds: RunThresholds {
                record_rpm,
                mains_noise_hz: engine_noise_mains_hz,
            },
        };

        tokio::spawn(fusion_task_loop(
            task.frame_rx.clone(),
            task.ambient_rx.clone(),
            task.can_rx.clone(),
            task.tx.clone(),
            task.correction_mode,
            task.calibration_rx.clone(),
            task.run_control.clone(),
            task.run_thresholds,
        ));
        info!("fusion task spawned");

        task
    }

    /// Produce a synthetic idle frame (used before fusion is running).
    pub fn idle_frame() -> LiveFrame {
        LiveFrame::idle(0)
    }
}

async fn fusion_task_loop(
    mut frame_rx: watch::Receiver<DynoFrameV1>,
    ambient_rx: watch::Receiver<AmbientSample>,
    can_rx: watch::Receiver<CanSample>,
    tx: watch::Sender<LiveFrame>,
    correction_mode: CorrectionMode,
    calibration_rx: watch::Receiver<CalibrationProfile>,
    run_control: RunControl,
    run_thresholds: RunThresholds,
) {
    let mut frame_count = 0u64;
    let mut physics_state = PhysicsState::default();

    loop {
        match frame_rx.changed().await {
            Ok(()) => {
                frame_count += 1;

                let input = frame_rx.borrow().clone();
                let ambient = *ambient_rx.borrow();
                let can = can_rx.borrow().clone();
                let physics = {
                    let calibration = calibration_rx.borrow();
                    FusionPhysicsConfig::from_calibration(&calibration)
                };
                let runtime_state = run_control.snapshot().await;
                // A stationary roller (no valid roller signal, or zero encoder
                // movement this window) is the guard that lets us treat a
                // mains-harmonic engine reading as phantom noise.
                let roller_stationary =
                    input.signal_flags & SIG_ROLLER_VALID == 0 || input.encoder_delta == 0;
                let raw_engine_rpm = if input.signal_flags & SIG_ENGINE_VALID != 0 {
                    engine_rpm_from_period(input.engine_period_us)
                } else {
                    None
                };
                let runtime_engine_rpm = reject_mains_noise(
                    raw_engine_rpm,
                    roller_stationary,
                    run_thresholds.mains_noise_hz,
                );
                let run_state = next_run_state(
                    runtime_state.started,
                    runtime_engine_rpm,
                    run_thresholds,
                );
                run_control.update_runtime_state(run_state).await;
                let live = fuse_frame(
                    &input,
                    ambient,
                    &can,
                    correction_mode,
                    physics,
                    &mut physics_state,
                    run_state,
                    run_thresholds.mains_noise_hz,
                );
                let ambient = ambient.sanitized();
                let correction = correction_factor(
                    correction_mode,
                    ambient.temp_c,
                    ambient.pressure_hpa,
                    ambient.humidity_pct,
                );

                if frame_count <= DEBUG_LOG_FRAMES {
                    debug!(
                        count = frame_count,
                        seq = input.seq,
                        ts_us = input.ts_us,
                        engine_rpm = live.engine_rpm,
                        roller_rpm = live.roller_rpm,
                        speed_kmh = live.speed_kmh,
                        power_hp = live.power_hp,
                        torque_nm = live.torque_nm,
                        lambda = live.lambda,
                        correction_factor = live.correction_factor,
                        vapor_pressure_kpa = correction.vapor_pressure_kpa,
                        dry_pressure_kpa = correction.dry_pressure_kpa,
                        "fusion: produced live frame"
                    );
                }

                if tx.send(live).is_err() {
                    info!(
                        "fusion: live frame channel closed after {frame_count} frames \
                         - task stopping"
                    );
                    return;
                }
            }
            Err(_) => {
                info!("fusion: input channel closed after {frame_count} frames - task stopping");
                return;
            }
        }
    }
}

#[derive(Debug, Default, Clone, Copy)]
struct PhysicsState {
    prev_omega_rad_s: Option<f32>,
    prev_ts_us: Option<u32>,
}

fn fuse_frame(
    frame: &DynoFrameV1,
    ambient: AmbientSample,
    can: &CanSample,
    correction_mode: CorrectionMode,
    physics: FusionPhysicsConfig,
    physics_state: &mut PhysicsState,
    run_state: RunState,
    mains_noise_hz: f32,
) -> LiveFrame {
    let esp32_status = build_esp32_status(frame);
    let ambient = ambient.sanitized();
    let correction = correction_factor(
        correction_mode,
        ambient.temp_c,
        ambient.pressure_hpa,
        ambient.humidity_pct,
    );

    let raw_engine_rpm = if esp32_status.engine_signal_valid {
        engine_rpm_from_period(frame.engine_period_us)
    } else {
        None
    };

    let roller_rpm = if esp32_status.roller_signal_valid {
        roller_rpm_from_encoder_delta(
            frame.encoder_delta,
            physics.encoder_pulses_per_rev,
            physics.sample_window_s,
        )
    } else {
        None
    };
    // Reject phantom engine RPM (mains hum on a floating ignition input) when
    // the roller is stationary. Kept consistent with the run-state machine,
    // which gates the same reading in the fusion loop.
    let roller_stationary = roller_rpm.map_or(true, |rpm| !(rpm > 0.0));
    let engine_rpm = reject_mains_noise(raw_engine_rpm, roller_stationary, mains_noise_hz);
    let roller_omega_rad_s = roller_rpm
        .map(rpm_to_rad_s)
        .filter(|omega| omega.is_finite() && *omega > 0.0);
    let alpha_rad_s2 = match (
        physics_state.prev_omega_rad_s,
        physics_state.prev_ts_us,
        roller_omega_rad_s,
    ) {
        (Some(prev_omega), Some(prev_ts_us), Some(curr_omega)) => {
            dt_s_from_timestamps(prev_ts_us, frame.ts_us)
                .and_then(|dt_s| angular_accel_rad_s2(prev_omega, curr_omega, dt_s))
        }
        _ => None,
    };
    let raw_power_w = match (roller_omega_rad_s, alpha_rad_s2) {
        (Some(omega_rad_s), Some(alpha_rad_s2)) => {
            Some(inertial_power_w(physics.roller_inertia_kg_m2, omega_rad_s, alpha_rad_s2))
        }
        _ => None,
    }
    .filter(|power_w| power_w.is_finite());
    let raw_torque_nm = match (raw_power_w, roller_omega_rad_s) {
        (Some(power_w), Some(omega_rad_s)) => torque_nm_from_power_and_omega(power_w, omega_rad_s),
        _ => None,
    };
    let corrected_power_hp = raw_power_w.map(|power_w| {
        let raw_power_hp = watts_to_hp(power_w);
        apply_correction(raw_power_hp, correction.factor)
    });
    let corrected_torque_nm =
        raw_torque_nm.map(|torque_nm| apply_correction(torque_nm, correction.factor));

    if let Some(curr_omega_rad_s) = roller_omega_rad_s {
        physics_state.prev_omega_rad_s = Some(curr_omega_rad_s);
        physics_state.prev_ts_us = Some(frame.ts_us);
    } else {
        physics_state.prev_omega_rad_s = None;
        physics_state.prev_ts_us = Some(frame.ts_us);
    }

    let afr = if can.afr_valid {
        can.afr
    } else if esp32_status.afr_valid {
        scaled_value_x100(frame.afr_scaled_x100)
    } else {
        None
    };

    let lambda = if can.afr_valid {
        can.lambda
    } else if esp32_status.lambda_valid {
        scaled_value_x1000(frame.lambda_scaled_x1000)
    } else {
        None
    };
    let faults = map_frame_faults(frame, &esp32_status);

    LiveFrame {
        ts_ms: i64::from(frame.ts_us / 1_000),
        engine_rpm,
        roller_rpm,
        speed_kmh: roller_rpm.and_then(|rpm| speed_kmh_from_roller_rpm(rpm, physics.roller_diameter_m)),
        power_hp: corrected_power_hp,
        torque_nm: corrected_torque_nm,
        afr,
        lambda,
        can_present: can.can_present,
        can_frames_seen: can.can_frames_seen,
        afr_valid: can.afr_valid || esp32_status.afr_valid,
        can_valid: can.can_valid,
        can_status_text: can.status_text.clone(),
        correction_factor: correction.factor,
        ambient_temp_c: Some(ambient.temp_c),
        humidity_pct: Some(ambient.humidity_pct),
        pressure_hpa: Some(ambient.pressure_hpa),
        esp32_status,
        run_state,
        faults,
        alerts: LiveAlerts {
            exhaust_temp: AlertLevel::Ok,
            o2_ratio: o2_alert_from_afr(afr),
            lambda: lambda_alert(lambda),
        },
    }
}

fn next_run_state(
    started: bool,
    engine_rpm: Option<f32>,
    thresholds: RunThresholds,
) -> RunState {
    if !started {
        return RunState::Idle;
    }

    let rpm = engine_rpm.unwrap_or(0.0);
    if rpm >= thresholds.record_rpm {
        return RunState::Recording;
    }

    RunState::Armed
}

#[inline]
fn dt_s_from_timestamps(prev_ts_us: u32, curr_ts_us: u32) -> Option<f32> {
    let dt_us = curr_ts_us.wrapping_sub(prev_ts_us);
    if dt_us == 0 {
        return None;
    }

    let dt_s = dt_us as f32 / 1_000_000.0;
    (dt_s.is_finite() && dt_s > 0.0).then_some(dt_s)
}

#[inline]
fn engine_rpm_from_period(period_us: u32) -> Option<f32> {
    if period_us == 0 {
        return None;
    }

    Some(60_000_000.0 / period_us as f32)
}

#[inline]
fn scaled_value_x100(value: i16) -> Option<f32> {
    (value > 0).then_some(value as f32 / 100.0)
}

#[inline]
fn scaled_value_x1000(value: i16) -> Option<f32> {
    (value > 0).then_some(value as f32 / 1_000.0)
}

#[inline]
fn build_esp32_status(frame: &DynoFrameV1) -> Esp32TelemetryStatus {
    let signal_flags = frame.signal_flags;

    Esp32TelemetryStatus {
        signal_flags,
        fault_flags: frame.fault_flags,
        can_status: frame.can_status,
        engine_signal_valid: signal_flags & SIG_ENGINE_VALID != 0,
        roller_signal_valid: signal_flags & SIG_ROLLER_VALID != 0,
        afr_valid: signal_flags & SIG_AFR_VALID != 0,
        lambda_valid: signal_flags & SIG_AFR_VALID != 0,
        can_active: signal_flags & SIG_CAN_ACTIVE != 0,
        engine_stalled: signal_flags & SIG_ENGINE_STALL != 0,
        roller_stopped: signal_flags & SIG_ROLLER_STOP != 0,
    }
}

fn map_frame_faults(frame: &DynoFrameV1, status: &Esp32TelemetryStatus) -> Vec<FaultCode> {
    let mut faults = Vec::new();

    let mut push_fault = |fault: FaultCode| {
        if !faults.contains(&fault) {
            faults.push(fault);
        }
    };

    if !status.engine_signal_valid && (status.engine_stalled || frame.engine_period_us == 0 || frame.fault_flags & FLT_ENGINE_INIT != 0) {
        push_fault(FaultCode::EnginePulseInvalid);
    }
    if !status.roller_signal_valid && (status.roller_stopped || frame.fault_flags & FLT_ENCODER_INIT != 0) {
        push_fault(FaultCode::EncoderInvalid);
    }

    match frame.can_status {
        CAN_STATUS_NO_DATA | CAN_STATUS_STALE => push_fault(FaultCode::CanTimeout),
        CAN_STATUS_BUS_OFF | CAN_STATUS_NOT_INIT => push_fault(FaultCode::CanInvalid),
        _ => {}
    }

    if frame.fault_flags & FLT_CAN_INIT != 0 || frame.fault_flags & FLT_CAN_BUS_OFF != 0 {
        push_fault(FaultCode::CanInvalid);
    }
    if frame.fault_flags & FLT_UART_OVERRUN != 0 {
        push_fault(FaultCode::Overflow);
    }
    if frame.fault_flags & FLT_CONFIG_INVALID != 0 {
        push_fault(FaultCode::Unknown);
    }

    faults
}

#[inline]
fn lambda_alert(lambda: Option<f32>) -> AlertLevel {
    match lambda {
        Some(value) if value >= 1.10 || value <= 0.75 => AlertLevel::Critical,
        Some(value) if value >= 1.00 || value <= 0.80 => AlertLevel::Warning,
        Some(_) | None => AlertLevel::Ok,
    }
}

#[inline]
fn o2_alert_from_afr(afr: Option<f32>) -> AlertLevel {
    match afr {
        Some(value) if value >= 16.0 || value <= 11.0 => AlertLevel::Critical,
        Some(value) if value >= 15.0 || value <= 11.8 => AlertLevel::Warning,
        Some(_) | None => AlertLevel::Ok,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn thresholds() -> RunThresholds {
        RunThresholds { record_rpm: 2_000.0, mains_noise_hz: 0.0 }
    }

    fn sample_frame() -> DynoFrameV1 {
        DynoFrameV1 {
            magic: dyno_protocol::MAGIC,
            version: 1,
            packet_type: dyno_protocol::PacketType::Telemetry as u8,
            seq: 7,
            ts_us: 200_000,
            encoder_count_total: 1_200,
            encoder_delta: 120,
            engine_period_us: 8_333,
            engine_pulse_count_window: 4,
            afr_raw: 0,
            afr_scaled_x100: 1_380,
            lambda_scaled_x1000: 939,
            can_status: 0,
            signal_flags: SIG_ENGINE_VALID | SIG_ROLLER_VALID | SIG_AFR_VALID | SIG_CAN_ACTIVE,
            fault_flags: 0,
            crc16: 0,
        }
    }

    #[test]
    fn mains_harmonic_detection_matches_hum_but_not_real_rpm() {
        // 50 Hz mains → 3000/6000/9000 RPM harmonics.
        assert!(is_mains_harmonic(6_000.0, 50.0));
        assert!(is_mains_harmonic(3_000.0, 50.0));
        assert!(is_mains_harmonic(6_008.0, 50.0)); // within band
        assert!(!is_mains_harmonic(6_300.0, 50.0)); // off-harmonic real RPM
        assert!(!is_mains_harmonic(4_500.0, 50.0));
        assert!(!is_mains_harmonic(6_000.0, 0.0)); // disabled
        // 60 Hz mains → 3600/7200 RPM.
        assert!(is_mains_harmonic(7_200.0, 60.0));
        assert!(!is_mains_harmonic(6_000.0, 60.0));
    }

    #[test]
    fn reject_mains_noise_only_when_roller_stationary() {
        // Phantom 6000 RPM with a stopped roller is suppressed…
        assert_eq!(reject_mains_noise(Some(6_000.0), true, 50.0), None);
        // …but the same RPM with the roller turning (a real pull) is kept.
        assert_eq!(reject_mains_noise(Some(6_000.0), false, 50.0), Some(6_000.0));
        // Non-harmonic RPM is always kept.
        assert_eq!(reject_mains_noise(Some(6_300.0), true, 50.0), Some(6_300.0));
        // Disabled gate keeps everything.
        assert_eq!(reject_mains_noise(Some(6_000.0), true, 0.0), Some(6_000.0));
    }

    #[test]
    fn fuse_frame_suppresses_mains_hum_with_stationary_roller() {
        // Nothing connected: engine reads a 50 Hz harmonic, roller not moving.
        let mut frame = sample_frame();
        frame.signal_flags = SIG_ENGINE_VALID; // no roller signal
        frame.encoder_delta = 0;
        frame.engine_period_us = 10_000; // 6000 RPM

        let live = fuse_frame(
            &frame,
            AmbientSample::stub(),
            &CanSample::missing(),
            CorrectionMode::None,
            FusionPhysicsConfig {
                roller_diameter_m: 0.318,
                encoder_pulses_per_rev: 60.0,
                sample_window_s: 0.1,
                roller_inertia_kg_m2: 3.5,
            },
            &mut PhysicsState::default(),
            RunState::Idle,
            50.0,
        );
        assert_eq!(live.engine_rpm, None, "mains hum should be suppressed");

        // With the mains gate disabled the same reading passes through.
        let live_disabled = fuse_frame(
            &frame,
            AmbientSample::stub(),
            &CanSample::missing(),
            CorrectionMode::None,
            FusionPhysicsConfig {
                roller_diameter_m: 0.318,
                encoder_pulses_per_rev: 60.0,
                sample_window_s: 0.1,
                roller_inertia_kg_m2: 3.5,
            },
            &mut PhysicsState::default(),
            RunState::Idle,
            0.0,
        );
        assert!(live_disabled.engine_rpm.unwrap() > 5_000.0);
    }

    #[test]
    fn fuse_frame_preserves_distinct_measurements_and_status() {
        let mut physics_state = PhysicsState {
            prev_omega_rad_s: Some(rpm_to_rad_s(1_000.0)),
            prev_ts_us: Some(100_000),
        };
        let live = fuse_frame(
            &sample_frame(),
            AmbientSample {
                temp_c: 24.5,
                humidity_pct: 55.0,
                pressure_hpa: 1013.25,
            },
            &CanSample::missing(),
            CorrectionMode::None,
            FusionPhysicsConfig {
                roller_diameter_m: 0.318,
                encoder_pulses_per_rev: 60.0,
                sample_window_s: 0.1,
                roller_inertia_kg_m2: 3.5,
            },
            &mut physics_state,
            RunState::Idle,
            0.0,
        );

        assert!(live.engine_rpm.unwrap() > 0.0);
        assert!(live.roller_rpm.unwrap() > 0.0);
        assert!(live.speed_kmh.unwrap() > 0.0);
        assert_eq!(live.afr, Some(13.8));
        assert_eq!(live.lambda, Some(0.939));
        assert!(live.esp32_status.engine_signal_valid);
        assert!(live.esp32_status.roller_signal_valid);
        assert!(live.esp32_status.afr_valid);
        assert!(live.faults.is_empty());
    }

    #[test]
    fn invalid_signal_bits_suppress_only_the_affected_domains() {
        let mut frame = sample_frame();
        frame.signal_flags = SIG_ROLLER_VALID | SIG_CAN_ACTIVE;
        frame.engine_period_us = 0;
        frame.afr_scaled_x100 = 0;
        frame.lambda_scaled_x1000 = 0;

        let live = fuse_frame(
            &frame,
            AmbientSample {
                temp_c: 24.5,
                humidity_pct: 55.0,
                pressure_hpa: 1013.25,
            },
            &CanSample::missing(),
            CorrectionMode::None,
            FusionPhysicsConfig {
                roller_diameter_m: 0.318,
                encoder_pulses_per_rev: 60.0,
                sample_window_s: 0.1,
                roller_inertia_kg_m2: 3.5,
            },
            &mut PhysicsState::default(),
            RunState::Idle,
            0.0,
        );

        assert_eq!(live.engine_rpm, None);
        assert!(live.roller_rpm.is_some());
        assert_eq!(live.afr, None);
        assert_eq!(live.lambda, None);
        assert!(live.faults.contains(&FaultCode::EnginePulseInvalid));
    }

    #[test]
    fn can_and_fault_flags_are_mapped_into_domain_faults() {
        let mut frame = sample_frame();
        frame.can_status = CAN_STATUS_BUS_OFF;
        frame.fault_flags = FLT_UART_OVERRUN | FLT_CONFIG_INVALID;
        let status = build_esp32_status(&frame);
        let faults = map_frame_faults(&frame, &status);

        assert!(faults.contains(&FaultCode::CanInvalid));
        assert!(faults.contains(&FaultCode::Overflow));
        assert!(faults.contains(&FaultCode::Unknown));
    }

    #[test]
    fn next_run_state_returns_idle_when_not_started_even_above_record_rpm() {
        assert_eq!(next_run_state(false, Some(4_000.0), thresholds()), RunState::Idle);
    }

    #[test]
    fn next_run_state_records_only_at_or_above_record_rpm() {
        assert_eq!(next_run_state(true, Some(1_999.0), thresholds()), RunState::Armed);
        assert_eq!(next_run_state(true, Some(2_000.0), thresholds()), RunState::Recording);
    }

    #[test]
    fn next_run_state_dip_while_recording_returns_armed_not_stopping() {
        assert_eq!(next_run_state(true, Some(900.0), thresholds()), RunState::Armed);
    }

    #[test]
    fn next_run_state_never_emits_stopping() {
        for rpm in [None, Some(0.0), Some(999.0), Some(1_999.0), Some(2_000.0), Some(8_000.0)] {
            assert_ne!(next_run_state(true, rpm, thresholds()), RunState::Stopping);
        }
    }

    #[test]
    fn dt_s_from_timestamps_handles_u32_wraparound() {
        // Firmware `micros()` (and the truncated `ts_us as u32` field) wraps
        // every ~71.6 minutes; `wrapping_sub` recovers the true forward delta
        // instead of producing a negative/None dt that would permanently
        // stall alpha/power computation after every wraparound.
        let prev_ts_us = u32::MAX - 1_000; // just before wraparound
        let curr_ts_us = 4_000u32; // just after wraparound
        let expected_dt_us = curr_ts_us.wrapping_sub(prev_ts_us);

        let dt_s = dt_s_from_timestamps(prev_ts_us, curr_ts_us).expect("dt across wraparound");

        assert!((dt_s - expected_dt_us as f32 / 1_000_000.0).abs() < 1e-6);
        assert!(dt_s > 0.0);
    }

    #[test]
    fn dt_s_from_timestamps_zero_delta_is_none() {
        assert_eq!(dt_s_from_timestamps(100, 100), None);
    }

    /// End-to-end: a firmware-shaped JSON telemetry line, through the same
    /// encode (`telemetry_to_frame`) / decode (`fuse_frame`) mapping the
    /// live serial→fusion pipeline uses, produces non-null engine_rpm,
    /// roller_rpm, and ambient values in the `LiveFrame` the operator
    /// console renders.
    #[test]
    fn firmware_json_line_produces_populated_live_frame() {
        use crate::calibration::CalibrationProfile;
        use crate::esp32_json::{parse_json_telemetry_line, telemetry_to_frame, JsonTelemetryMapping};

        let profile = CalibrationProfile {
            profile_id: 1,
            name: "Bench profile".to_owned(),
            created_at_ms: 0,
            updated_at_ms: 0,
            is_active: true,
            roller_diameter_m: 0.318,
            encoder_pulses_per_rev: 60.0,
            roller_inertia_kg_m2: 3.5,
            sample_window_ms: 50,
            engine_pulses_per_rev_hint: None,
            engine_rpm_scale: None,
            engine_stroke: None,
            engine_cylinders: None,
            notes: None,
        };
        let mapping = JsonTelemetryMapping::from_calibration(&profile);

        let line = r#"{"seq":1,"ts_us":50000,"engine_rpm":3200.0,"roller_rpm":1150.0,"encoder_count":1000,"encoder_delta":0,"temp_c":27.5,"humidity":45.0,"pressure":1009.0,"afr":0.0,"lambda":0.0,"engine_valid":true,"encoder_valid":true,"bme_valid":true,"can_valid":false}"#;
        let telemetry = parse_json_telemetry_line(line)
            .expect("valid json")
            .expect("telemetry present");
        let frame = telemetry_to_frame(&telemetry, mapping);

        let physics = FusionPhysicsConfig::from_calibration(&profile);
        let live = fuse_frame(
            &frame,
            AmbientSample {
                temp_c: telemetry.temp_c,
                humidity_pct: telemetry.humidity,
                pressure_hpa: telemetry.pressure,
            },
            &CanSample::missing(),
            CorrectionMode::None,
            physics,
            &mut PhysicsState::default(),
            RunState::Idle,
            0.0,
        );

        assert!(live.engine_rpm.is_some(), "engine_rpm should not be null");
        assert!(
            (live.engine_rpm.unwrap() - 3200.0).abs() < 5.0,
            "engine_rpm should round-trip to ~3200, got {:?}",
            live.engine_rpm
        );
        assert!(live.roller_rpm.is_some(), "roller_rpm should not be null");
        assert!(
            (live.roller_rpm.unwrap() - 1150.0).abs() < 20.0,
            "roller_rpm should round-trip to ~1150, got {:?}",
            live.roller_rpm
        );
        assert_eq!(live.ambient_temp_c, Some(27.5));
    }
}
