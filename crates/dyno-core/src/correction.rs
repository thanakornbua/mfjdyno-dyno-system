//! Ambient correction factor models.
//!
//! These formulas are intentionally "standards-style" rather than claiming
//! full standards compliance. They preserve the current lightweight backend
//! architecture while incorporating humidity via a Tetens-style vapor-pressure
//! estimate and using dry barometric pressure in the correction ratio.

use std::fmt;
use std::str::FromStr;

/// Supported correction standards for dyno power normalization.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CorrectionMode {
    None,
    SAEJ1349,
    ISO1585,
}

impl Default for CorrectionMode {
    fn default() -> Self {
        Self::None
    }
}

impl fmt::Display for CorrectionMode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let value = match self {
            Self::None => "none",
            Self::SAEJ1349 => "sae_j1349",
            Self::ISO1585 => "iso_1585",
        };
        f.write_str(value)
    }
}

impl FromStr for CorrectionMode {
    type Err = ParseCorrectionModeError;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match s.trim().to_ascii_lowercase().as_str() {
            "none" => Ok(Self::None),
            "sae_j1349" | "sae-j1349" | "saej1349" => Ok(Self::SAEJ1349),
            "iso_1585" | "iso-1585" | "iso1585" => Ok(Self::ISO1585),
            _ => Err(ParseCorrectionModeError),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ParseCorrectionModeError;

impl fmt::Display for ParseCorrectionModeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("invalid correction mode")
    }
}

impl std::error::Error for ParseCorrectionModeError {}

/// Indicates whether the returned result is just a safe fallback or a
/// standards-style approximation using humidity-adjusted dry pressure.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CorrectionQuality {
    Approximate,
    StandardsStyle,
}

/// Rich correction metadata kept toggle-ready for future UI exposure.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct CorrectionResult {
    pub factor: f32,
    pub quality: CorrectionQuality,
    pub vapor_pressure_kpa: Option<f32>,
    pub dry_pressure_kpa: Option<f32>,
}

impl CorrectionResult {
    #[inline]
    pub const fn approximate(factor: f32) -> Self {
        Self {
            factor,
            quality: CorrectionQuality::Approximate,
            vapor_pressure_kpa: None,
            dry_pressure_kpa: None,
        }
    }
}

/// Convert Celsius to Kelvin.
#[inline]
pub fn temp_c_to_k(temp_c: f32) -> f32 {
    temp_c + 273.15
}

/// Convert hPa to kPa.
#[inline]
pub fn pressure_hpa_to_kpa(pressure_hpa: f32) -> f32 {
    pressure_hpa / 10.0
}

/// Tetens-style saturation vapor pressure approximation in kPa.
///
/// Assumption: this uses a simple water-vapor approximation suitable for
/// dyno correction preprocessing, not a claim of full psychrometric accuracy.
#[inline]
pub fn saturation_vapor_pressure_kpa(temp_c: f32) -> f32 {
    if !temp_c.is_finite() {
        return f32::NAN;
    }

    0.61078 * ((17.2694 * temp_c) / (temp_c + 237.3)).exp()
}

/// Compute actual vapor pressure from temperature and relative humidity.
///
/// Relative humidity is clamped to `0..=100` before calculation.
#[inline]
pub fn actual_vapor_pressure_kpa(temp_c: f32, humidity_pct: f32) -> f32 {
    let humidity_pct = if humidity_pct.is_finite() {
        humidity_pct.clamp(0.0, 100.0)
    } else {
        return f32::NAN;
    };

    saturation_vapor_pressure_kpa(temp_c) * (humidity_pct / 100.0)
}

/// Compute a humidity-aware ambient correction result.
///
/// `SAEJ1349` and `ISO1585` use dry pressure:
/// `dry_pressure_kpa = barometric_pressure_kpa - vapor_pressure_kpa`
///
/// Assumptions:
/// - Vapor pressure is estimated with a simple Tetens-style approximation.
/// - Humidity is treated as relative humidity in percent and clamped to 0..100.
/// - This is "standards-style" correction math, not full certified standards
///   compliance, because the backend intentionally avoids the rest of the
///   standards machinery for now.
/// - Invalid inputs and non-physical dry pressure fall back to a factor of 1.0.
#[inline]
pub fn correction_factor(
    mode: CorrectionMode,
    temp_c: f32,
    pressure_hpa: f32,
    humidity_pct: f32,
) -> CorrectionResult {
    if mode == CorrectionMode::None {
        return CorrectionResult::approximate(1.0);
    }

    let temp_k = temp_c_to_k(temp_c);
    let baro_kpa = pressure_hpa_to_kpa(pressure_hpa);
    let vapor_kpa = actual_vapor_pressure_kpa(temp_c, humidity_pct);

    if !temp_k.is_finite() || !baro_kpa.is_finite() || !vapor_kpa.is_finite() || temp_k <= 0.0 || baro_kpa <= 0.0
    {
        return CorrectionResult::approximate(1.0);
    }

    let dry_pressure_kpa = baro_kpa - vapor_kpa;
    if !dry_pressure_kpa.is_finite() || dry_pressure_kpa <= 0.0 {
        return CorrectionResult {
            factor: 1.0,
            quality: CorrectionQuality::Approximate,
            vapor_pressure_kpa: Some(vapor_kpa),
            dry_pressure_kpa: Some(dry_pressure_kpa),
        };
    }

    let factor = match mode {
        CorrectionMode::None => 1.0,
        CorrectionMode::SAEJ1349 => (99.0 / dry_pressure_kpa) * (temp_k / 298.0).sqrt(),
        CorrectionMode::ISO1585 => (101.3 / dry_pressure_kpa) * (temp_k / 293.0).sqrt(),
    };

    if !factor.is_finite() || factor <= 0.0 {
        return CorrectionResult {
            factor: 1.0,
            quality: CorrectionQuality::Approximate,
            vapor_pressure_kpa: Some(vapor_kpa),
            dry_pressure_kpa: Some(dry_pressure_kpa),
        };
    }

    CorrectionResult {
        factor,
        quality: CorrectionQuality::StandardsStyle,
        vapor_pressure_kpa: Some(vapor_kpa),
        dry_pressure_kpa: Some(dry_pressure_kpa),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn approx_eq(left: f32, right: f32) {
        assert!((left - right).abs() < 0.0005, "{left} != {right}");
    }

    #[test]
    fn parses_supported_modes() {
        assert_eq!("none".parse::<CorrectionMode>().unwrap(), CorrectionMode::None);
        assert_eq!(
            "sae_j1349".parse::<CorrectionMode>().unwrap(),
            CorrectionMode::SAEJ1349
        );
        assert_eq!(
            "iso1585".parse::<CorrectionMode>().unwrap(),
            CorrectionMode::ISO1585
        );
    }

    #[test]
    fn none_mode_returns_one() {
        let result = correction_factor(CorrectionMode::None, 25.0, 1013.25, 50.0);
        approx_eq(result.factor, 1.0);
        assert_eq!(result.quality, CorrectionQuality::Approximate);
        assert_eq!(result.vapor_pressure_kpa, None);
        assert_eq!(result.dry_pressure_kpa, None);
    }

    #[test]
    fn humidity_levels_change_vapor_pressure_monotonically() {
        let dry = actual_vapor_pressure_kpa(25.0, 0.0);
        let mid = actual_vapor_pressure_kpa(25.0, 50.0);
        let wet = actual_vapor_pressure_kpa(25.0, 100.0);

        approx_eq(dry, 0.0);
        assert!(mid > dry);
        assert!(wet > mid);
    }

    #[test]
    fn invalid_humidity_is_clamped() {
        approx_eq(
            actual_vapor_pressure_kpa(25.0, -15.0),
            actual_vapor_pressure_kpa(25.0, 0.0),
        );
        approx_eq(
            actual_vapor_pressure_kpa(25.0, 140.0),
            actual_vapor_pressure_kpa(25.0, 100.0),
        );
    }

    #[test]
    fn invalid_pressure_falls_back_safely() {
        let result = correction_factor(CorrectionMode::SAEJ1349, 25.0, 0.0, 50.0);
        approx_eq(result.factor, 1.0);
        assert_eq!(result.quality, CorrectionQuality::Approximate);
        assert_eq!(result.vapor_pressure_kpa, None);
        assert_eq!(result.dry_pressure_kpa, None);
    }

    #[test]
    fn hotter_lower_pressure_air_increases_factor() {
        let baseline = correction_factor(CorrectionMode::SAEJ1349, 20.0, 1013.25, 40.0);
        let worse_air = correction_factor(CorrectionMode::SAEJ1349, 35.0, 950.0, 40.0);

        assert!(baseline.factor.is_finite());
        assert!(worse_air.factor.is_finite());
        assert!(worse_air.factor > baseline.factor);
    }

    #[test]
    fn dry_pressure_is_never_above_barometric_pressure() {
        let result = correction_factor(CorrectionMode::ISO1585, 30.0, 1000.0, 70.0);
        let baro_kpa = pressure_hpa_to_kpa(1000.0);
        let dry_kpa = result.dry_pressure_kpa.unwrap();

        assert!(dry_kpa <= baro_kpa);
        assert!(dry_kpa > 0.0);
    }

    #[test]
    fn standards_modes_report_metadata() {
        let result = correction_factor(CorrectionMode::ISO1585, 25.0, 1013.25, 50.0);
        assert_eq!(result.quality, CorrectionQuality::StandardsStyle);
        assert!(result.vapor_pressure_kpa.unwrap() >= 0.0);
        assert!(result.dry_pressure_kpa.unwrap() > 0.0);
    }
}
