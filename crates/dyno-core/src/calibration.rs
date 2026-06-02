//! Calibration profiles, audit events, and validation helpers.
//!
//! Calibration is persisted in SQLite and loaded into runtime at startup so
//! environment variables act only as bootstrap defaults.

use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::fmt;
use std::sync::Arc;
use thiserror::Error;
use tracing::warn;

use crate::config::Config;
use crate::storage::Storage;

pub const CALIBRATION_UNLOCK_PASSWORD: &str = "MFJ123456";

#[derive(Debug, Error)]
pub enum CalibrationError {
    #[error("calibration is locked")]
    Locked,
    #[error("wrong password")]
    WrongPassword,
    #[error("calibration is already locked")]
    AlreadyLocked,
    #[error("calibration is not locked")]
    AlreadyUnlocked,
}

const SETTING_CALIBRATION_LOCKED: &str = "calibration_locked";

/// Runtime lock guard for the active calibration profile.
///
/// Cloning is cheap — the inner mutex and optional storage handle are both reference-counted.
#[derive(Clone)]
pub struct CalibrationLock {
    locked: Arc<tokio::sync::Mutex<bool>>,
    storage: Option<Storage>,
}

impl Default for CalibrationLock {
    fn default() -> Self {
        Self::new()
    }
}

impl CalibrationLock {
    /// Create a lock with no persistence (starts unlocked). Used in tests and as a default.
    pub fn new() -> Self {
        Self {
            locked: Arc::new(tokio::sync::Mutex::new(false)),
            storage: None,
        }
    }

    /// Create a lock backed by persistent storage. Reads the persisted lock state on init.
    pub async fn with_storage(storage: Storage) -> Self {
        let initial = match storage.get_setting(SETTING_CALIBRATION_LOCKED).await {
            Ok(Some(value)) => value == "true",
            Ok(None) => false,
            Err(err) => {
                warn!("calibration_lock: failed to read persisted lock state: {err:#}");
                false
            }
        };
        Self {
            locked: Arc::new(tokio::sync::Mutex::new(initial)),
            storage: Some(storage),
        }
    }

    pub async fn lock_calibration(&self, password: &str) -> Result<(), CalibrationError> {
        if password != CALIBRATION_UNLOCK_PASSWORD {
            return Err(CalibrationError::WrongPassword);
        }
        let mut guard = self.locked.lock().await;
        if *guard {
            return Err(CalibrationError::AlreadyLocked);
        }
        *guard = true;
        if let Some(storage) = &self.storage {
            if let Err(err) = storage.set_setting(SETTING_CALIBRATION_LOCKED, "true").await {
                warn!("calibration_lock: failed to persist locked state: {err:#}");
            }
        }
        Ok(())
    }

    pub async fn unlock_calibration(&self, password: &str) -> Result<(), CalibrationError> {
        if password != CALIBRATION_UNLOCK_PASSWORD {
            return Err(CalibrationError::WrongPassword);
        }
        let mut guard = self.locked.lock().await;
        if !*guard {
            return Err(CalibrationError::AlreadyUnlocked);
        }
        *guard = false;
        if let Some(storage) = &self.storage {
            if let Err(err) = storage.set_setting(SETTING_CALIBRATION_LOCKED, "false").await {
                warn!("calibration_lock: failed to persist unlocked state: {err:#}");
            }
        }
        Ok(())
    }

    pub async fn is_locked(&self) -> bool {
        *self.locked.lock().await
    }
}

const ROLLER_DIAMETER_WARNING_MIN_M: f32 = 0.1;
const ROLLER_DIAMETER_WARNING_MAX_M: f32 = 1.0;
const ROLLER_DIAMETER_HARD_MIN_M: f32 = 0.01;
const ROLLER_DIAMETER_HARD_MAX_M: f32 = 5.0;

const ENCODER_PPR_WARNING_MIN: f32 = 1.0;
const ENCODER_PPR_WARNING_MAX: f32 = 2_048.0;
const ENCODER_PPR_HARD_MIN: f32 = 0.1;
const ENCODER_PPR_HARD_MAX: f32 = 100_000.0;

const ROLLER_INERTIA_WARNING_MIN: f32 = 0.05;
const ROLLER_INERTIA_WARNING_MAX: f32 = 250.0;
const ROLLER_INERTIA_HARD_MIN: f32 = 0.000_1;
const ROLLER_INERTIA_HARD_MAX: f32 = 100_000.0;

const SAMPLE_WINDOW_WARNING_MIN_MS: u64 = 10;
const SAMPLE_WINDOW_WARNING_MAX_MS: u64 = 1_000;
const SAMPLE_WINDOW_HARD_MAX_MS: u64 = 60_000;

const ENGINE_PPR_HINT_WARNING_MIN: f32 = 0.25;
const ENGINE_PPR_HINT_WARNING_MAX: f32 = 8.0;
const ENGINE_PPR_HINT_HARD_MAX: f32 = 1_000.0;

const ENGINE_RPM_SCALE_WARNING_MIN: f32 = 0.1;
const ENGINE_RPM_SCALE_WARNING_MAX: f32 = 10.0;
const ENGINE_RPM_SCALE_HARD_MAX: f32 = 100.0;

const PROFILE_NAME_MAX_LEN: usize = 120;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CalibrationProfile {
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

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CalibrationProfileInput {
    pub name: String,
    pub roller_diameter_m: f32,
    pub encoder_pulses_per_rev: f32,
    pub roller_inertia_kg_m2: f32,
    pub sample_window_ms: u64,
    pub engine_pulses_per_rev_hint: Option<f32>,
    pub engine_rpm_scale: Option<f32>,
    pub notes: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CalibrationProfileEventType {
    Created,
    Updated,
    Duplicated,
    Activated,
}

impl fmt::Display for CalibrationProfileEventType {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let value = match self {
            Self::Created => "created",
            Self::Updated => "updated",
            Self::Duplicated => "duplicated",
            Self::Activated => "activated",
        };
        f.write_str(value)
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CalibrationProfileEvent {
    pub event_id: i64,
    pub profile_id: i64,
    pub event_type: CalibrationProfileEventType,
    pub created_at_ms: i64,
    pub summary: String,
    pub previous_values_json: Option<Value>,
    pub new_values_json: Option<Value>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CalibrationProfileChange {
    pub profile: CalibrationProfile,
    pub activated: bool,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
pub struct CalibrationFieldValidation {
    pub warnings: Vec<String>,
    pub errors: Vec<String>,
}

impl CalibrationFieldValidation {
    pub fn is_valid(&self) -> bool {
        self.errors.is_empty()
    }
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
pub struct CalibrationValidation {
    pub is_valid: bool,
    pub warnings: Vec<String>,
    pub errors: Vec<String>,
}

impl CalibrationValidation {
    fn push_prefixed(&mut self, field: &str, result: CalibrationFieldValidation) {
        self.warnings.extend(
            result
                .warnings
                .into_iter()
                .map(|warning| format!("{field}: {warning}")),
        );
        self.errors.extend(
            result
                .errors
                .into_iter()
                .map(|error| format!("{field}: {error}")),
        );
        self.is_valid = self.errors.is_empty();
    }
}

impl CalibrationProfile {
    pub fn bootstrap_default(config: &Config, now_ms: i64) -> Self {
        Self {
            profile_id: 0,
            name: "Default bootstrap profile".to_owned(),
            created_at_ms: now_ms,
            updated_at_ms: now_ms,
            is_active: true,
            roller_diameter_m: config.roller_diameter_m,
            encoder_pulses_per_rev: config.encoder_pulses_per_rev,
            roller_inertia_kg_m2: config.roller_inertia_kg_m2,
            sample_window_ms: u64::from(config.sample_window_ms),
            engine_pulses_per_rev_hint: None,
            engine_rpm_scale: None,
            notes: Some(
                "Bootstrapped from DYNO_* environment defaults on first startup".to_owned(),
            ),
        }
    }
}

impl CalibrationProfileInput {
    pub fn normalized(&self) -> Self {
        Self {
            name: self.name.trim().to_owned(),
            roller_diameter_m: self.roller_diameter_m,
            encoder_pulses_per_rev: self.encoder_pulses_per_rev,
            roller_inertia_kg_m2: self.roller_inertia_kg_m2,
            sample_window_ms: self.sample_window_ms,
            engine_pulses_per_rev_hint: self.engine_pulses_per_rev_hint,
            engine_rpm_scale: self.engine_rpm_scale,
            notes: self
                .notes
                .as_deref()
                .map(str::trim)
                .filter(|value| !value.is_empty())
                .map(ToOwned::to_owned),
        }
    }

    pub fn into_profile(
        self,
        profile_id: i64,
        created_at_ms: i64,
        updated_at_ms: i64,
        is_active: bool,
    ) -> CalibrationProfile {
        CalibrationProfile {
            profile_id,
            name: self.name,
            created_at_ms,
            updated_at_ms,
            is_active,
            roller_diameter_m: self.roller_diameter_m,
            encoder_pulses_per_rev: self.encoder_pulses_per_rev,
            roller_inertia_kg_m2: self.roller_inertia_kg_m2,
            sample_window_ms: self.sample_window_ms,
            engine_pulses_per_rev_hint: self.engine_pulses_per_rev_hint,
            engine_rpm_scale: self.engine_rpm_scale,
            notes: self.notes,
        }
    }
}

pub fn validate_profile_name(name: &str) -> CalibrationFieldValidation {
    let mut result = CalibrationFieldValidation::default();
    let trimmed = name.trim();

    if trimmed.is_empty() {
        result.errors.push("must not be blank".to_owned());
        return result;
    }

    if trimmed.len() > PROFILE_NAME_MAX_LEN {
        result.errors.push(format!(
            "must be no longer than {PROFILE_NAME_MAX_LEN} characters"
        ));
    }

    result
}

pub fn validate_roller_diameter_m(value: f32) -> CalibrationFieldValidation {
    let mut result = CalibrationFieldValidation::default();

    if !value.is_finite() {
        result.errors.push("must be finite".to_owned());
        return result;
    }

    if value <= 0.0 {
        result.errors.push("must be positive".to_owned());
        return result;
    }

    if !(ROLLER_DIAMETER_HARD_MIN_M..=ROLLER_DIAMETER_HARD_MAX_M).contains(&value) {
        result
            .errors
            .push(format!("must be between {ROLLER_DIAMETER_HARD_MIN_M} m and {ROLLER_DIAMETER_HARD_MAX_M} m"));
        return result;
    }

    if !(ROLLER_DIAMETER_WARNING_MIN_M..=ROLLER_DIAMETER_WARNING_MAX_M).contains(&value) {
        result.warnings.push(format!(
            "value {value:.3} m is outside the typical dyno range of \
             {ROLLER_DIAMETER_WARNING_MIN_M:.3} to {ROLLER_DIAMETER_WARNING_MAX_M:.3} m"
        ));
    }

    result
}

pub fn validate_encoder_pulses_per_rev(value: f32) -> CalibrationFieldValidation {
    let mut result = CalibrationFieldValidation::default();

    if !value.is_finite() {
        result.errors.push("must be finite".to_owned());
        return result;
    }

    if value <= 0.0 {
        result.errors.push("must be positive".to_owned());
        return result;
    }

    if !(ENCODER_PPR_HARD_MIN..=ENCODER_PPR_HARD_MAX).contains(&value) {
        result.errors.push(format!(
            "must be between {ENCODER_PPR_HARD_MIN} and {ENCODER_PPR_HARD_MAX}"
        ));
        return result;
    }

    if !(ENCODER_PPR_WARNING_MIN..=ENCODER_PPR_WARNING_MAX).contains(&value) {
        result.warnings.push(format!(
            "value {value:.3} is outside the typical encoder range of \
             {ENCODER_PPR_WARNING_MIN:.1} to {ENCODER_PPR_WARNING_MAX:.1} pulses/rev"
        ));
    }

    result
}

pub fn validate_roller_inertia_kg_m2(value: f32) -> CalibrationFieldValidation {
    let mut result = CalibrationFieldValidation::default();

    if !value.is_finite() {
        result.errors.push("must be finite".to_owned());
        return result;
    }

    if value <= 0.0 {
        result.errors.push("must be positive".to_owned());
        return result;
    }

    if !(ROLLER_INERTIA_HARD_MIN..=ROLLER_INERTIA_HARD_MAX).contains(&value) {
        result.errors.push(format!(
            "must be between {ROLLER_INERTIA_HARD_MIN} and {ROLLER_INERTIA_HARD_MAX} kg·m²"
        ));
        return result;
    }

    if !(ROLLER_INERTIA_WARNING_MIN..=ROLLER_INERTIA_WARNING_MAX).contains(&value) {
        result.warnings.push(format!(
            "value {value:.3} kg·m² is outside the typical dyno range of \
             {ROLLER_INERTIA_WARNING_MIN:.3} to {ROLLER_INERTIA_WARNING_MAX:.3} kg·m²"
        ));
    }

    result
}

pub fn validate_sample_window_ms(value: u64) -> CalibrationFieldValidation {
    let mut result = CalibrationFieldValidation::default();

    if value == 0 {
        result.errors.push("must be positive".to_owned());
        return result;
    }

    if value > SAMPLE_WINDOW_HARD_MAX_MS {
        result.errors.push(format!(
            "must be no greater than {SAMPLE_WINDOW_HARD_MAX_MS} ms"
        ));
        return result;
    }

    if !(SAMPLE_WINDOW_WARNING_MIN_MS..=SAMPLE_WINDOW_WARNING_MAX_MS).contains(&value) {
        result.warnings.push(format!(
            "value {value} ms is outside the typical measurement-window range of \
             {SAMPLE_WINDOW_WARNING_MIN_MS} to {SAMPLE_WINDOW_WARNING_MAX_MS} ms"
        ));
    }

    result
}

pub fn validate_engine_pulses_per_rev_hint(value: Option<f32>) -> CalibrationFieldValidation {
    validate_optional_positive_field(
        value,
        "engine_pulses_per_rev_hint",
        ENGINE_PPR_HINT_WARNING_MIN,
        ENGINE_PPR_HINT_WARNING_MAX,
        ENGINE_PPR_HINT_HARD_MAX,
    )
}

pub fn validate_engine_rpm_scale(value: Option<f32>) -> CalibrationFieldValidation {
    validate_optional_positive_field(
        value,
        "engine_rpm_scale",
        ENGINE_RPM_SCALE_WARNING_MIN,
        ENGINE_RPM_SCALE_WARNING_MAX,
        ENGINE_RPM_SCALE_HARD_MAX,
    )
}

pub fn validate_profile(profile: &CalibrationProfile) -> CalibrationValidation {
    let mut result = CalibrationValidation {
        is_valid: true,
        warnings: Vec::new(),
        errors: Vec::new(),
    };

    result.push_prefixed(
        "roller_diameter_m",
        validate_roller_diameter_m(profile.roller_diameter_m),
    );
    result.push_prefixed(
        "encoder_pulses_per_rev",
        validate_encoder_pulses_per_rev(profile.encoder_pulses_per_rev),
    );
    result.push_prefixed(
        "roller_inertia_kg_m2",
        validate_roller_inertia_kg_m2(profile.roller_inertia_kg_m2),
    );
    result.push_prefixed(
        "sample_window_ms",
        validate_sample_window_ms(profile.sample_window_ms),
    );
    result.push_prefixed(
        "engine_pulses_per_rev_hint",
        validate_engine_pulses_per_rev_hint(profile.engine_pulses_per_rev_hint),
    );
    result.push_prefixed(
        "engine_rpm_scale",
        validate_engine_rpm_scale(profile.engine_rpm_scale),
    );
    result.is_valid = result.errors.is_empty();

    result
}

pub fn validate_profile_input(input: &CalibrationProfileInput) -> CalibrationValidation {
    let normalized = input.normalized();
    let mut result = CalibrationValidation {
        is_valid: true,
        warnings: Vec::new(),
        errors: Vec::new(),
    };

    result.push_prefixed("name", validate_profile_name(&normalized.name));
    result.push_prefixed(
        "roller_diameter_m",
        validate_roller_diameter_m(normalized.roller_diameter_m),
    );
    result.push_prefixed(
        "encoder_pulses_per_rev",
        validate_encoder_pulses_per_rev(normalized.encoder_pulses_per_rev),
    );
    result.push_prefixed(
        "roller_inertia_kg_m2",
        validate_roller_inertia_kg_m2(normalized.roller_inertia_kg_m2),
    );
    result.push_prefixed(
        "sample_window_ms",
        validate_sample_window_ms(normalized.sample_window_ms),
    );
    result.push_prefixed(
        "engine_pulses_per_rev_hint",
        validate_engine_pulses_per_rev_hint(normalized.engine_pulses_per_rev_hint),
    );
    result.push_prefixed(
        "engine_rpm_scale",
        validate_engine_rpm_scale(normalized.engine_rpm_scale),
    );
    result.is_valid = result.errors.is_empty();

    result
}

fn validate_optional_positive_field(
    value: Option<f32>,
    field_name: &str,
    warning_min: f32,
    warning_max: f32,
    hard_max: f32,
) -> CalibrationFieldValidation {
    let mut result = CalibrationFieldValidation::default();
    let Some(value) = value else {
        return result;
    };

    if !value.is_finite() {
        result.errors.push("must be finite when provided".to_owned());
        return result;
    }

    if value <= 0.0 {
        result.errors.push("must be positive when provided".to_owned());
        return result;
    }

    if value > hard_max {
        result
            .errors
            .push(format!("must be no greater than {hard_max} when provided"));
        return result;
    }

    if !(warning_min..=warning_max).contains(&value) {
        result.warnings.push(format!(
            "{field_name} value {value:.3} is outside the typical range of \
             {warning_min:.3} to {warning_max:.3}"
        ));
    }

    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::correction::CorrectionMode;

    fn sample_profile() -> CalibrationProfile {
        let _ = CorrectionMode::None;
        CalibrationProfile {
            profile_id: 1,
            name: "Test profile".to_owned(),
            created_at_ms: 1_700_000_000_000,
            updated_at_ms: 1_700_000_000_000,
            is_active: true,
            roller_diameter_m: 0.318,
            encoder_pulses_per_rev: 60.0,
            roller_inertia_kg_m2: 3.5,
            sample_window_ms: 100,
            engine_pulses_per_rev_hint: Some(1.0),
            engine_rpm_scale: Some(1.0),
            notes: None,
        }
    }

    fn sample_input() -> CalibrationProfileInput {
        CalibrationProfileInput {
            name: "Test profile".to_owned(),
            roller_diameter_m: 0.318,
            encoder_pulses_per_rev: 60.0,
            roller_inertia_kg_m2: 3.5,
            sample_window_ms: 100,
            engine_pulses_per_rev_hint: Some(1.0),
            engine_rpm_scale: Some(1.0),
            notes: Some("  baseline  ".to_owned()),
        }
    }

    #[test]
    fn valid_profile_passes_without_warnings() {
        let validation = validate_profile(&sample_profile());
        assert!(validation.is_valid);
        assert!(validation.errors.is_empty());
        assert!(validation.warnings.is_empty());
    }

    #[test]
    fn unusual_values_warn_without_failing() {
        let mut profile = sample_profile();
        profile.roller_diameter_m = 1.2;
        profile.sample_window_ms = 2_000;

        let validation = validate_profile(&profile);
        assert!(validation.is_valid);
        assert!(validation.errors.is_empty());
        assert_eq!(validation.warnings.len(), 2);
    }

    #[test]
    fn unusable_values_fail_validation() {
        let mut profile = sample_profile();
        profile.encoder_pulses_per_rev = 0.0;
        profile.roller_inertia_kg_m2 = -1.0;
        profile.engine_rpm_scale = Some(0.0);

        let validation = validate_profile(&profile);
        assert!(!validation.is_valid);
        assert_eq!(validation.errors.len(), 3);
    }

    #[test]
    fn bootstrap_profile_uses_config_defaults() {
        let config = Config {
            serial_port: "/dev/null".to_owned(),
            serial_baud: 921_600,
            can_iface: "can0".to_owned(),
            profile: "production".to_owned(),
            modbus_afr_enabled: false,
            ws_bind: "127.0.0.1:0".to_owned(),
            api_bind: "127.0.0.1:0".to_owned(),
            db_path: "test.sqlite".to_owned(),
            esp32_config_path: "esp32-device-config.json".to_owned(),
            esp32_applied_config_path: "esp32-last-applied.json".to_owned(),
            esp32_command_timeout_ms: 1_500,
            esp32_command_retries: 3,
            bme280_enabled: false,
            source_mode: crate::config::SourceMode::Replay,
            correction_mode: CorrectionMode::None,
            roller_diameter_m: 0.4,
            encoder_pulses_per_rev: 48.0,
            roller_inertia_kg_m2: 4.2,
            sample_window_ms: 125,
            ui_broadcast_rate_hz: 20,
            arm_rpm: 1500.0,
            record_rpm: 2000.0,
            stop_rpm: 1000.0,
        };

        let profile = CalibrationProfile::bootstrap_default(&config, 1234);
        assert_eq!(profile.profile_id, 0);
        assert_eq!(profile.name, "Default bootstrap profile");
        assert_eq!(profile.roller_diameter_m, 0.4);
        assert_eq!(profile.encoder_pulses_per_rev, 48.0);
        assert_eq!(profile.roller_inertia_kg_m2, 4.2);
        assert_eq!(profile.sample_window_ms, 125);
        assert!(profile.is_active);
    }

    #[test]
    fn blank_profile_name_fails_validation() {
        let mut input = sample_input();
        input.name = "   ".to_owned();

        let validation = validate_profile_input(&input);
        assert!(!validation.is_valid);
        assert_eq!(validation.errors, vec!["name: must not be blank"]);
    }

    #[test]
    fn input_normalization_trims_name_and_notes() {
        let normalized = sample_input().normalized();
        assert_eq!(normalized.name, "Test profile");
        assert_eq!(normalized.notes.as_deref(), Some("baseline"));
    }
}
