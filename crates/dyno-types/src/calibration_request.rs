use serde::Deserialize;

/// Partial-update request for PUT /api/calibration/profiles/:id.
///
/// Every field is Option<T>: absent fields keep their existing DB value;
/// present fields overwrite it.  This allows callers to send only the
/// fields they want to change instead of a full profile snapshot.
#[derive(Debug, Deserialize)]
pub struct UpdateCalibrationProfileRequest {
    pub name: Option<String>,
    pub roller_diameter_m: Option<f32>,
    pub encoder_pulses_per_rev: Option<f32>,
    pub roller_inertia_kg_m2: Option<f32>,
    pub sample_window_ms: Option<u64>,
    pub engine_pulses_per_rev_hint: Option<f32>,
    pub engine_rpm_scale: Option<f32>,
    pub engine_stroke: Option<u8>,
    pub engine_cylinders: Option<u8>,
    pub notes: Option<String>,
    pub activate_after_save: Option<bool>,
}
