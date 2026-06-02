use serde::{Deserialize, Serialize};

#[derive(Debug, Serialize, Deserialize)]
pub struct RepeatabilityReport {
    pub run_ids: Vec<i64>,
    pub peak_hp: RepeatabilityMetric,
    pub peak_torque_nm: RepeatabilityMetric,
    pub peak_speed_kmh: Option<RepeatabilityMetric>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct RepeatabilityMetric {
    pub min: f64,
    pub max: f64,
    pub mean: f64,
    /// `(max - min) / mean * 100`
    pub span_percent: f64,
    /// Peak value per run, in the same order as `RepeatabilityReport::run_ids`.
    pub per_run: Vec<f64>,
}
