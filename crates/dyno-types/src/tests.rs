//! Unit tests for dyno-types.

#[cfg(test)]
mod live_frame_serde {
    use crate::{Esp32TelemetryStatus, FaultCode, LiveAlerts, LiveFrame, RunState};

    fn sample_frame() -> LiveFrame {
        LiveFrame {
            ts_ms: 1_700_000_000_000,
            engine_rpm: Some(4500.0),
            roller_rpm: Some(1200.0),
            speed_kmh: Some(95.4),
            power_hp: Some(87.3),
            torque_nm: Some(135.0),
            correction_factor: 0.992,
            afr: Some(13.8),
            lambda: Some(0.939),
            can_present: true,
            can_frames_seen: 7,
            afr_valid: true,
            can_valid: true,
            can_status_text: "AEM UEGO active".to_owned(),
            ambient_temp_c: Some(24.5),
            humidity_pct: Some(55.0),
            pressure_hpa: Some(1013.25),
            esp32_status: Esp32TelemetryStatus {
                signal_flags: 0b0000_1111,
                fault_flags: 0,
                can_status: 0,
                engine_signal_valid: true,
                roller_signal_valid: true,
                afr_valid: true,
                lambda_valid: true,
                can_active: true,
                engine_stalled: false,
                roller_stopped: false,
            },
            run_state: RunState::Recording,
            faults: vec![],
            alerts: LiveAlerts::default(),
        }
    }

    #[test]
    fn round_trip_json() {
        let original = sample_frame();
        let json = serde_json::to_string(&original).expect("serialize");
        let restored: LiveFrame = serde_json::from_str(&json).expect("deserialize");

        assert_eq!(original.ts_ms, restored.ts_ms);
        assert_eq!(original.engine_rpm, restored.engine_rpm);
        assert_eq!(original.roller_rpm, restored.roller_rpm);
        assert_eq!(original.correction_factor, restored.correction_factor);
        assert_eq!(original.esp32_status, restored.esp32_status);
        assert_eq!(original.run_state, restored.run_state);
        assert_eq!(original.faults, restored.faults);
    }

    #[test]
    fn engine_and_roller_rpm_are_distinct_fields() {
        // Verify the JSON contains both field names unambiguously.
        let frame = sample_frame();
        let json = serde_json::to_string(&frame).expect("serialize");
        assert!(json.contains("\"engine_rpm\""), "engine_rpm field must be present");
        assert!(json.contains("\"roller_rpm\""), "roller_rpm field must be present");
    }

    #[test]
    fn optional_fields_serialise_as_null() {
        let frame = LiveFrame::idle(0);
        let json = serde_json::to_string(&frame).expect("serialize");
        // All measurement fields should be null in an idle frame.
        assert!(json.contains("\"engine_rpm\":null"));
        assert!(json.contains("\"roller_rpm\":null"));
        assert!(json.contains("\"speed_kmh\":null"));
        assert!(json.contains("\"power_hp\":null"));
        assert!(json.contains("\"esp32_status\""));
    }

    #[test]
    fn frame_with_faults_round_trips() {
        let mut frame = sample_frame();
        frame.faults = vec![FaultCode::SerialCrcMismatch, FaultCode::SerialDesync];
        let json = serde_json::to_string(&frame).expect("serialize");
        let restored: LiveFrame = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(restored.faults, frame.faults);
    }
}

#[cfg(test)]
mod enum_stability {
    use crate::{AlertLevel, FaultCode, RunState};

    #[test]
    fn run_state_variants_exist() {
        // If a variant is accidentally removed, this test will fail to compile.
        let _states = [
            RunState::Idle,
            RunState::Armed,
            RunState::Recording,
            RunState::Stopping,
            RunState::Fault,
        ];
    }

    #[test]
    fn fault_code_variants_exist() {
        let _faults = [
            FaultCode::CanTimeout,
            FaultCode::CanInvalid,
            FaultCode::EncoderInvalid,
            FaultCode::EnginePulseInvalid,
            FaultCode::SerialCrcMismatch,
            FaultCode::SerialDesync,
            FaultCode::AmbientSensorFault,
            FaultCode::Overflow,
            FaultCode::Timeout,
            FaultCode::Unknown,
        ];
    }

    #[test]
    fn alert_level_variants_exist() {
        let _levels = [AlertLevel::Ok, AlertLevel::Warning, AlertLevel::Critical];
    }

    #[test]
    fn run_state_display() {
        assert_eq!(RunState::Idle.to_string(), "idle");
        assert_eq!(RunState::Recording.to_string(), "recording");
        assert_eq!(RunState::Fault.to_string(), "fault");
    }

    #[test]
    fn fault_code_debug_is_readable() {
        // Regression guard: ensure Debug output matches the variant name.
        assert_eq!(format!("{}", FaultCode::SerialCrcMismatch), "SerialCrcMismatch");
    }
}
