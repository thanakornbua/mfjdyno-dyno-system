//! Pure roller dyno physics helpers.
//!
//! Assumptions:
//! - This is an inertia-only roller model; drivetrain, tire slip, and aero
//!   losses are intentionally excluded at this stage.
//! - `encoder_delta` is the number of encoder pulses observed over a known
//!   fixed sample window supplied by configuration.
//! - Angular acceleration spikes above `MAX_ABS_ALPHA_RAD_S2` are treated as
//!   implausible sensor artifacts and rejected.

use std::f32::consts::PI;

/// Conversion constant from mechanical watts to horsepower.
const WATTS_PER_HP: f32 = 745.699_9;
/// Reject clearly implausible acceleration spikes without trying to smooth.
const MAX_ABS_ALPHA_RAD_S2: f32 = 20_000.0;
/// Avoid division by zero when torque is derived from power and omega.
const MIN_OMEGA_RAD_S: f32 = 1.0e-6;

/// Convert encoder counts in a fixed window into roller RPM.
#[inline]
pub fn roller_rpm_from_encoder_delta(
    encoder_delta: u32,
    pulses_per_rev: f32,
    sample_window_s: f32,
) -> Option<f32> {
    if encoder_delta == 0
        || !pulses_per_rev.is_finite()
        || !sample_window_s.is_finite()
        || pulses_per_rev <= 0.0
        || sample_window_s <= 0.0
    {
        return None;
    }

    let pulses_per_second = encoder_delta as f32 / sample_window_s;
    let rpm = (pulses_per_second / pulses_per_rev) * 60.0;

    (rpm.is_finite() && rpm > 0.0).then_some(rpm)
}

/// Convert rotational speed from RPM to radians per second.
#[inline]
pub fn rpm_to_rad_s(rpm: f32) -> f32 {
    rpm * (2.0 * PI / 60.0)
}

/// Convert roller RPM to road speed at the roller surface.
#[inline]
pub fn speed_kmh_from_roller_rpm(roller_rpm: f32, roller_diameter_m: f32) -> Option<f32> {
    if !roller_rpm.is_finite()
        || !roller_diameter_m.is_finite()
        || roller_rpm <= 0.0
        || roller_diameter_m <= 0.0
    {
        return None;
    }

    let circumference_m = PI * roller_diameter_m;
    let speed_kmh = roller_rpm * circumference_m * 60.0 / 1_000.0;

    (speed_kmh.is_finite() && speed_kmh >= 0.0).then_some(speed_kmh)
}

/// Compute angular acceleration from two angular velocity samples.
#[inline]
pub fn angular_accel_rad_s2(prev_rad_s: f32, curr_rad_s: f32, dt_s: f32) -> Option<f32> {
    if !prev_rad_s.is_finite() || !curr_rad_s.is_finite() || !dt_s.is_finite() || dt_s <= 0.0 {
        return None;
    }

    let alpha = (curr_rad_s - prev_rad_s) / dt_s;
    (alpha.is_finite() && alpha.abs() <= MAX_ABS_ALPHA_RAD_S2).then_some(alpha)
}

/// Compute inertial power in watts from roller inertia, omega, and alpha.
#[inline]
pub fn inertial_power_w(inertia_kg_m2: f32, omega_rad_s: f32, alpha_rad_s2: f32) -> f32 {
    if !inertia_kg_m2.is_finite()
        || !omega_rad_s.is_finite()
        || !alpha_rad_s2.is_finite()
        || inertia_kg_m2 <= 0.0
    {
        return 0.0;
    }

    let power_w = inertia_kg_m2 * omega_rad_s * alpha_rad_s2;
    if power_w.is_finite() { power_w } else { 0.0 }
}

/// Convert watts to horsepower.
#[inline]
pub fn watts_to_hp(power_w: f32) -> f32 {
    if !power_w.is_finite() {
        return 0.0;
    }

    power_w / WATTS_PER_HP
}

/// Derive torque in N·m from power and angular velocity.
#[inline]
pub fn torque_nm_from_power_and_omega(power_w: f32, omega_rad_s: f32) -> Option<f32> {
    if !power_w.is_finite() || !omega_rad_s.is_finite() || omega_rad_s.abs() <= MIN_OMEGA_RAD_S {
        return None;
    }

    let torque_nm = power_w / omega_rad_s;
    torque_nm.is_finite().then_some(torque_nm)
}

/// Apply a multiplicative correction factor to a raw physics value.
#[inline]
pub fn apply_correction(raw_value: f32, correction_factor: f32) -> f32 {
    if !raw_value.is_finite() {
        return 0.0;
    }

    if !correction_factor.is_finite() || correction_factor <= 0.0 {
        return raw_value;
    }

    raw_value * correction_factor
}

#[cfg(test)]
mod tests {
    use super::*;

    fn approx_eq(left: f32, right: f32) {
        assert!((left - right).abs() < 0.001, "{left} != {right}");
    }

    #[test]
    fn rpm_from_encoder_delta_matches_expected() {
        let rpm = roller_rpm_from_encoder_delta(120, 60.0, 0.1).unwrap();
        approx_eq(rpm, 1200.0);
    }

    #[test]
    fn rpm_to_omega_matches_expected() {
        approx_eq(rpm_to_rad_s(60.0), 2.0 * PI);
    }

    #[test]
    fn angular_accel_preserves_sign_and_magnitude() {
        let alpha_up = angular_accel_rad_s2(10.0, 20.0, 0.5).unwrap();
        let alpha_down = angular_accel_rad_s2(20.0, 10.0, 0.5).unwrap();

        approx_eq(alpha_up, 20.0);
        approx_eq(alpha_down, -20.0);
    }

    #[test]
    fn power_is_positive_under_positive_acceleration() {
        let power_w = inertial_power_w(3.5, 100.0, 20.0);
        assert!(power_w > 0.0);
    }

    #[test]
    fn torque_derives_from_power_and_omega() {
        let torque_nm = torque_nm_from_power_and_omega(1000.0, 50.0).unwrap();
        approx_eq(torque_nm, 20.0);
    }

    #[test]
    fn correction_is_multiplicative() {
        approx_eq(apply_correction(100.0, 1.05), 105.0);
    }

    #[test]
    fn zero_dt_and_zero_omega_are_rejected() {
        assert_eq!(angular_accel_rad_s2(10.0, 20.0, 0.0), None);
        assert_eq!(torque_nm_from_power_and_omega(100.0, 0.0), None);
    }

    #[test]
    fn implausible_alpha_spikes_are_rejected() {
        assert_eq!(angular_accel_rad_s2(0.0, 10_000.0, 0.001), None);
    }
}
