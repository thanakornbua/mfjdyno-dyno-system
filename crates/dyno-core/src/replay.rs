//! Deterministic replay/simulation source for end-to-end frontend exercise.
//!
//! This task publishes `LiveFrame` values directly onto the same watch channel
//! used by the live hardware path so the WebSocket contract remains identical.

use tokio::sync::watch;
use tokio::task::JoinHandle;
use tokio::time;
use tracing::info;

use dyno_types::{AlertLevel, Esp32TelemetryStatus, LiveAlerts, LiveFrame, RunState};

use crate::{calibration::CalibrationProfile, physics};

/// Handle to the replay loop.
pub struct ReplayTask {
    handle: JoinHandle<()>,
}

impl ReplayTask {
    pub fn spawn(calibration_rx: watch::Receiver<CalibrationProfile>, tx: watch::Sender<LiveFrame>) -> Self {
        let handle = tokio::spawn(async move {
            replay_task_loop(tx, calibration_rx).await;
        });

        info!("replay: deterministic sweep task spawned");
        Self { handle }
    }
}

impl Drop for ReplayTask {
    fn drop(&mut self) {
        self.handle.abort();
    }
}

async fn replay_task_loop(
    tx: watch::Sender<LiveFrame>,
    calibration_rx: watch::Receiver<CalibrationProfile>,
) {
    let mut tick: u64 = 0;

    loop {
        let step_ms = {
            let calibration = calibration_rx.borrow();
            u32::try_from(calibration.sample_window_ms)
                .unwrap_or(u32::MAX)
                .max(20)
        };
        time::sleep(std::time::Duration::from_millis(u64::from(step_ms))).await;

        let roller_diameter_m = calibration_rx.borrow().roller_diameter_m;
        let frame = replay_frame_at_tick(tick, step_ms, roller_diameter_m);
        tick = tick.wrapping_add(1);

        if tx.send(frame).is_err() {
            info!("replay: live frame channel closed - task stopping");
            return;
        }
    }
}

fn replay_frame_at_tick(tick: u64, step_ms: u32, roller_diameter_m: f32) -> LiveFrame {
    let cycle_ticks = 170_u64;
    let cycle_tick = tick % cycle_ticks;
    let ts_ms = (tick * u64::from(step_ms)) as i64;

    let ambient_temp_c = 24.5_f32;
    let humidity_pct = 55.0_f32;
    let pressure_hpa = 1013.25_f32;
    let correction_factor = 1.02_f32;

    if cycle_tick < 10 {
        return LiveFrame {
            ts_ms,
            engine_rpm: Some(1100.0),
            roller_rpm: Some(0.0),
            speed_kmh: Some(0.0),
            power_hp: None,
            torque_nm: None,
            correction_factor,
            afr: Some(14.2),
            lambda: Some(0.966),
            can_present: true,
            can_frames_seen: 1,
            afr_valid: true,
            can_valid: true,
            can_status_text: "Replay AEM UEGO".to_owned(),
            ambient_temp_c: Some(ambient_temp_c),
            humidity_pct: Some(humidity_pct),
            pressure_hpa: Some(pressure_hpa),
            esp32_status: Esp32TelemetryStatus::default(),
            run_state: RunState::Idle,
            faults: Vec::new(),
            alerts: LiveAlerts::default(),
        };
    }

    if cycle_tick < 20 {
        let progress = (cycle_tick - 10) as f32 / 10.0;
        return LiveFrame {
            ts_ms,
            engine_rpm: Some(1200.0 + progress * 700.0),
            roller_rpm: Some(200.0 + progress * 150.0),
            speed_kmh: physics::speed_kmh_from_roller_rpm(200.0 + progress * 150.0, roller_diameter_m),
            power_hp: None,
            torque_nm: None,
            correction_factor,
            afr: Some(13.8),
            lambda: Some(0.939),
            can_present: true,
            can_frames_seen: 1,
            afr_valid: true,
            can_valid: true,
            can_status_text: "Replay AEM UEGO".to_owned(),
            ambient_temp_c: Some(ambient_temp_c),
            humidity_pct: Some(humidity_pct),
            pressure_hpa: Some(pressure_hpa),
            esp32_status: Esp32TelemetryStatus::default(),
            run_state: RunState::Armed,
            faults: Vec::new(),
            alerts: LiveAlerts::default(),
        };
    }

    if cycle_tick < 145 {
        let progress = (cycle_tick - 20) as f32 / 125.0;
        let engine_rpm = 2000.0 + progress * 6500.0;
        let roller_rpm = 500.0 + progress * 1700.0;
        let torque_nm = corrected_torque_nm(progress) * correction_factor;
        let power_hp = ((torque_nm * engine_rpm) / 7127.0).max(0.0);
        let afr = 12.6 + progress * 1.1;
        let lambda = 0.86 + progress * 0.08;

        return LiveFrame {
            ts_ms,
            engine_rpm: Some(engine_rpm),
            roller_rpm: Some(roller_rpm),
            speed_kmh: physics::speed_kmh_from_roller_rpm(roller_rpm, roller_diameter_m),
            power_hp: Some(power_hp),
            torque_nm: Some(torque_nm),
            correction_factor,
            afr: Some(afr),
            lambda: Some(lambda),
            can_present: true,
            can_frames_seen: 1,
            afr_valid: true,
            can_valid: true,
            can_status_text: "Replay AEM UEGO".to_owned(),
            ambient_temp_c: Some(ambient_temp_c),
            humidity_pct: Some(humidity_pct),
            pressure_hpa: Some(pressure_hpa),
            esp32_status: Esp32TelemetryStatus::default(),
            run_state: RunState::Recording,
            faults: Vec::new(),
            alerts: replay_alerts(lambda),
        };
    }

    let progress = (cycle_tick - 145) as f32 / 25.0;
    let engine_rpm = 2600.0 - progress * 1500.0;
    let roller_rpm = 650.0 - progress * 500.0;

    LiveFrame {
        ts_ms,
        engine_rpm: Some(engine_rpm.max(900.0)),
        roller_rpm: Some(roller_rpm.max(0.0)),
        speed_kmh: physics::speed_kmh_from_roller_rpm(roller_rpm.max(0.0), roller_diameter_m),
        power_hp: None,
        torque_nm: None,
        correction_factor,
        afr: Some(13.9),
        lambda: Some(0.946),
        can_present: true,
        can_frames_seen: 1,
        afr_valid: true,
        can_valid: true,
        can_status_text: "Replay AEM UEGO".to_owned(),
        ambient_temp_c: Some(ambient_temp_c),
        humidity_pct: Some(humidity_pct),
        pressure_hpa: Some(pressure_hpa),
        esp32_status: Esp32TelemetryStatus::default(),
        run_state: RunState::Stopping,
        faults: Vec::new(),
        alerts: LiveAlerts::default(),
    }
}

fn corrected_torque_nm(progress: f32) -> f32 {
    let rise = 175.0 + progress.min(0.35) * 90.0;
    let taper = if progress > 0.45 {
        (progress - 0.45) * 120.0
    } else {
        0.0
    };
    (rise - taper).max(110.0)
}

fn replay_alerts(lambda: f32) -> LiveAlerts {
    let lambda_alert = if lambda >= 1.0 {
        AlertLevel::Warning
    } else {
        AlertLevel::Ok
    };

    LiveAlerts {
        exhaust_temp: AlertLevel::Ok,
        o2_ratio: lambda_alert,
        lambda: lambda_alert,
    }
}

pub fn golden_live_frame() -> LiveFrame {
    replay_frame_at_tick(60, 100, 0.318)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn golden_frame_serializes_with_live_frame_envelope() {
        let json = crate::ws::serialize_live_frame_message(&golden_live_frame()).expect("serialize");
        assert!(json.contains("\"type\":\"live_frame\""));
        assert!(json.contains("\"run_state\":\"recording\""));
        assert!(json.contains("\"power_hp\""));
    }

    #[test]
    fn replay_sweep_has_recording_segment_with_power() {
        let frame = replay_frame_at_tick(60, 100, 0.318);
        assert_eq!(frame.run_state, RunState::Recording);
        assert!(frame.power_hp.unwrap() > 0.0);
        assert!(frame.torque_nm.unwrap() > 0.0);
    }
}
