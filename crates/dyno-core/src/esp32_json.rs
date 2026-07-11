use serde::Deserialize;

use dyno_protocol::{
    DynoFrameV1, PacketType, CAN_STATUS_NO_DATA, CAN_STATUS_OK, MAGIC, SIG_AFR_VALID,
    SIG_CAN_ACTIVE, SIG_ENGINE_VALID, SIG_ROLLER_VALID,
};

use crate::bme280::AmbientSample;

#[derive(Debug, Clone, PartialEq, Deserialize)]
pub struct Esp32JsonTelemetry {
    pub seq: u32,
    pub ts_us: u64,
    pub engine_rpm: f32,
    pub roller_rpm: f32,
    pub encoder_count: i64,
    pub encoder_delta: i64,
    pub temp_c: f32,
    pub humidity: f32,
    pub pressure: f32,
    #[serde(default)]
    pub afr: f32,
    #[serde(default)]
    pub lambda: f32,
    pub engine_valid: bool,
    pub encoder_valid: bool,
    pub bme_valid: bool,
    #[serde(default)]
    pub can_valid: bool,
}

#[derive(Debug, Clone, Copy)]
pub struct JsonTelemetryMapping {
    pub encoder_pulses_per_rev: f32,
    pub sample_window_s: f32,
}

impl JsonTelemetryMapping {
    pub fn from_runtime_config(config: &crate::config::Config) -> Self {
        Self {
            encoder_pulses_per_rev: config.encoder_pulses_per_rev,
            sample_window_s: config.sample_window_ms as f32 / 1_000.0,
        }
    }

    /// Derives the mapping from the same active calibration profile that
    /// `fusion::FusionPhysicsConfig::from_calibration` uses to decode
    /// `encoder_delta` back into `roller_rpm`. Both sides of the round-trip
    /// (encode here, decode in fusion) must read identical
    /// encoder_pulses_per_rev/sample_window_s, or the reconstructed
    /// roller_rpm drifts from — or zeroes out relative to — the firmware's
    /// original value whenever the calibration profile diverges from the
    /// runtime env config.
    pub fn from_calibration(profile: &crate::calibration::CalibrationProfile) -> Self {
        Self {
            encoder_pulses_per_rev: profile.encoder_pulses_per_rev,
            sample_window_s: profile.sample_window_ms as f32 / 1_000.0,
        }
    }
}

pub fn parse_json_telemetry_line(
    line: &str,
) -> Result<Option<Esp32JsonTelemetry>, serde_json::Error> {
    let trimmed = line.trim();
    if trimmed.is_empty() || !trimmed.starts_with('{') {
        return Ok(None);
    }

    serde_json::from_str(trimmed).map(Some)
}

pub fn telemetry_to_frame(
    telemetry: &Esp32JsonTelemetry,
    mapping: JsonTelemetryMapping,
) -> DynoFrameV1 {
    let mut signal_flags = 0u16;
    if telemetry.engine_valid {
        signal_flags |= SIG_ENGINE_VALID;
    }
    if telemetry.encoder_valid {
        signal_flags |= SIG_ROLLER_VALID;
    }
    if telemetry.can_valid {
        signal_flags |= SIG_CAN_ACTIVE | SIG_AFR_VALID;
    }

    DynoFrameV1 {
        magic: MAGIC,
        version: 1,
        packet_type: PacketType::Telemetry as u8,
        seq: telemetry.seq,
        ts_us: telemetry.ts_us as u32,
        encoder_count_total: saturating_u32_from_i64(telemetry.encoder_count),
        encoder_delta: encoder_delta_for_pipeline(telemetry, mapping),
        engine_period_us: engine_period_us(telemetry.engine_rpm, telemetry.engine_valid),
        engine_pulse_count_window: u16::from(telemetry.engine_valid),
        afr_raw: 0,
        afr_scaled_x100: scaled_i16(telemetry.afr, 100.0, telemetry.can_valid),
        lambda_scaled_x1000: scaled_i16(telemetry.lambda, 1_000.0, telemetry.can_valid),
        can_status: if telemetry.can_valid {
            CAN_STATUS_OK
        } else {
            CAN_STATUS_NO_DATA
        },
        signal_flags,
        fault_flags: 0,
        crc16: 0,
    }
}

pub fn telemetry_ambient_or_stub(telemetry: &Esp32JsonTelemetry) -> AmbientSample {
    if telemetry.bme_valid {
        AmbientSample {
            temp_c: telemetry.temp_c,
            humidity_pct: telemetry.humidity,
            pressure_hpa: telemetry.pressure,
        }
        .sanitized()
    } else {
        AmbientSample::stub()
    }
}

fn engine_period_us(engine_rpm: f32, engine_valid: bool) -> u32 {
    if !engine_valid || !engine_rpm.is_finite() || engine_rpm <= 0.0 {
        return 0;
    }

    (60_000_000.0 / engine_rpm).round().clamp(1.0, u32::MAX as f32) as u32
}

fn encoder_delta_for_pipeline(
    telemetry: &Esp32JsonTelemetry,
    mapping: JsonTelemetryMapping,
) -> u32 {
    if !telemetry.encoder_valid
        || !telemetry.roller_rpm.is_finite()
        || telemetry.roller_rpm <= 0.0
    {
        return 0;
    }

    let pulses = telemetry.roller_rpm
        * mapping.encoder_pulses_per_rev.max(1.0)
        * mapping.sample_window_s.max(0.001)
        / 60.0;
    pulses.round().clamp(0.0, u32::MAX as f32) as u32
}

fn scaled_i16(value: f32, scale: f32, valid: bool) -> i16 {
    if !valid || !value.is_finite() || value <= 0.0 {
        return 0;
    }

    (value * scale).round().clamp(i16::MIN as f32, i16::MAX as f32) as i16
}

fn saturating_u32_from_i64(value: i64) -> u32 {
    value.clamp(0, i64::from(u32::MAX)) as u32
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_line() -> &'static str {
        r#"{"seq":1,"ts_us":12345678,"engine_rpm":1234.5,"roller_rpm":456.7,"encoder_count":123456,"encoder_delta":42,"temp_c":28.25,"humidity":61.2,"pressure":1008.75,"afr":13.2,"lambda":0.898,"engine_valid":true,"encoder_valid":true,"bme_valid":true,"can_valid":true}"#
    }

    #[test]
    fn parses_valid_esp32_json_line() {
        let telemetry = parse_json_telemetry_line(sample_line())
            .expect("parse ok")
            .expect("telemetry");

        assert_eq!(telemetry.seq, 1);
        assert_eq!(telemetry.ts_us, 12_345_678);
        assert_eq!(telemetry.pressure, 1008.75);
    }

    #[test]
    fn parses_minimal_esp32_json_line_without_can_placeholders() {
        let telemetry = parse_json_telemetry_line(
            r#"{"seq":84177,"ts_us":123456789,"engine_rpm":0.0,"roller_rpm":0.0,"encoder_count":0,"encoder_delta":0,"temp_c":28.25,"humidity":61.2,"pressure":1008.75,"engine_valid":false,"encoder_valid":false,"bme_valid":true}"#,
        )
        .expect("parse ok")
        .expect("telemetry");

        assert_eq!(telemetry.seq, 84_177);
        assert_eq!(telemetry.ts_us, 123_456_789);
        assert_eq!(telemetry.afr, 0.0);
        assert_eq!(telemetry.lambda, 0.0);
        assert!(!telemetry.can_valid);
    }

    #[test]
    fn skips_non_json_line() {
        assert_eq!(
            parse_json_telemetry_line("[boot] Runtime telemetry").expect("skip"),
            None
        );
    }

    #[test]
    fn malformed_json_returns_parse_error() {
        let err = parse_json_telemetry_line(r#"{"seq":1"#).expect_err("malformed");
        assert!(err.is_eof());
    }

    #[test]
    fn maps_validity_flags_and_can_values() {
        let telemetry = parse_json_telemetry_line(sample_line())
            .expect("parse ok")
            .expect("telemetry");
        let frame = telemetry_to_frame(
            &telemetry,
            JsonTelemetryMapping {
                encoder_pulses_per_rev: 60.0,
                sample_window_s: 0.1,
            },
        );

        assert_ne!(frame.signal_flags & SIG_ENGINE_VALID, 0);
        assert_ne!(frame.signal_flags & SIG_ROLLER_VALID, 0);
        assert_ne!(frame.signal_flags & SIG_AFR_VALID, 0);
        assert_eq!(frame.afr_scaled_x100, 1320);
        assert_eq!(frame.lambda_scaled_x1000, 898);
        assert!(frame.engine_period_us > 0);
        assert!(frame.encoder_delta > 0);
    }

    #[test]
    fn invalid_bme_falls_back_to_stub_ambient() {
        let mut telemetry = parse_json_telemetry_line(sample_line())
            .expect("parse ok")
            .expect("telemetry");
        telemetry.bme_valid = false;
        telemetry.temp_c = 200.0;

        assert_eq!(telemetry_ambient_or_stub(&telemetry), AmbientSample::stub());
    }

    fn calibration_profile(encoder_pulses_per_rev: f32, sample_window_ms: u64) -> crate::calibration::CalibrationProfile {
        crate::calibration::CalibrationProfile {
            profile_id: 1,
            name: "Test profile".to_owned(),
            created_at_ms: 0,
            updated_at_ms: 0,
            is_active: true,
            roller_diameter_m: 0.318,
            encoder_pulses_per_rev,
            roller_inertia_kg_m2: 3.5,
            sample_window_ms,
            engine_pulses_per_rev_hint: None,
            engine_rpm_scale: None,
            engine_stroke: None,
            engine_cylinders: None,
            notes: None,
        }
    }

    /// Guards against the round-trip bug where `encoder_delta_for_pipeline`
    /// (encode, here) and `fusion::roller_rpm_from_encoder_delta` (decode)
    /// used different mapping sources — env config vs. calibration profile —
    /// silently corrupting roller_rpm whenever the two disagreed. Both sides
    /// must use `JsonTelemetryMapping::from_calibration` against the same
    /// profile.
    #[test]
    fn roller_rpm_round_trips_through_encoder_delta_when_mapping_matches_fusion() {
        let profile = calibration_profile(60.0, 100);
        let mapping = JsonTelemetryMapping::from_calibration(&profile);

        let telemetry = Esp32JsonTelemetry {
            seq: 1,
            ts_us: 1_000_000,
            engine_rpm: 3_000.0,
            roller_rpm: 1_200.0,
            encoder_count: 0,
            encoder_delta: 0,
            temp_c: 25.0,
            humidity: 50.0,
            pressure: 1013.0,
            afr: 0.0,
            lambda: 0.0,
            engine_valid: true,
            encoder_valid: true,
            bme_valid: true,
            can_valid: false,
        };

        let frame = telemetry_to_frame(&telemetry, mapping);

        // Decode exactly as fusion does, using the identical mapping source.
        let decoded_rpm = frame.encoder_delta as f32 * 60.0
            / (mapping.encoder_pulses_per_rev * mapping.sample_window_s);

        assert!(
            (decoded_rpm - telemetry.roller_rpm).abs() < 1.0,
            "expected roller_rpm to round-trip to ~{}, got {decoded_rpm}",
            telemetry.roller_rpm
        );
    }

    #[test]
    fn can_invalid_suppresses_afr_and_lambda() {
        let mut telemetry = parse_json_telemetry_line(sample_line())
            .expect("parse ok")
            .expect("telemetry");
        telemetry.can_valid = false;

        let frame = telemetry_to_frame(
            &telemetry,
            JsonTelemetryMapping {
                encoder_pulses_per_rev: 60.0,
                sample_window_s: 0.1,
            },
        );

        assert_eq!(frame.signal_flags & SIG_AFR_VALID, 0);
        assert_eq!(frame.signal_flags & SIG_CAN_ACTIVE, 0);
        assert_eq!(frame.afr_scaled_x100, 0);
        assert_eq!(frame.lambda_scaled_x1000, 0);
        assert_eq!(frame.can_status, CAN_STATUS_NO_DATA);
    }
}
