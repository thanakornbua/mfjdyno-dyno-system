package com.dyno.calibration;

import java.util.ArrayList;
import java.util.List;

public final class CalibrationDraftValidator {
    private static final double ROLLER_DIAMETER_WARNING_MIN_M = 0.1;
    private static final double ROLLER_DIAMETER_WARNING_MAX_M = 1.0;
    private static final double ROLLER_DIAMETER_HARD_MIN_M = 0.01;
    private static final double ROLLER_DIAMETER_HARD_MAX_M = 5.0;

    private static final double ENCODER_PPR_WARNING_MIN = 1.0;
    private static final double ENCODER_PPR_WARNING_MAX = 2048.0;
    private static final double ENCODER_PPR_HARD_MIN = 0.1;
    private static final double ENCODER_PPR_HARD_MAX = 100000.0;

    private static final double ROLLER_INERTIA_WARNING_MIN = 0.05;
    private static final double ROLLER_INERTIA_WARNING_MAX = 250.0;
    private static final double ROLLER_INERTIA_HARD_MIN = 0.0001;
    private static final double ROLLER_INERTIA_HARD_MAX = 100000.0;

    private static final long SAMPLE_WINDOW_WARNING_MIN_MS = 10L;
    private static final long SAMPLE_WINDOW_WARNING_MAX_MS = 1000L;
    private static final long SAMPLE_WINDOW_HARD_MAX_MS = 60000L;

    private static final double ENGINE_PPR_HINT_WARNING_MIN = 0.25;
    private static final double ENGINE_PPR_HINT_WARNING_MAX = 8.0;
    private static final double ENGINE_PPR_HINT_HARD_MAX = 1000.0;

    private static final double ENGINE_RPM_SCALE_WARNING_MIN = 0.1;
    private static final double ENGINE_RPM_SCALE_WARNING_MAX = 10.0;
    private static final double ENGINE_RPM_SCALE_HARD_MAX = 100.0;

    private CalibrationDraftValidator() {
    }

    public static CalibrationValidationDto validate(CalibrationUpsertRequestDto request) {
        List<String> warnings = new ArrayList<String>();
        List<String> errors = new ArrayList<String>();

        validateName(request == null ? null : request.getName(), errors);
        validateRollerDiameter(request == null ? null : request.getRollerDiameterM(), warnings, errors);
        validateEncoderPulses(request == null ? null : request.getEncoderPulsesPerRev(), warnings, errors);
        validateInertia(request == null ? null : request.getRollerInertiaKgM2(), warnings, errors);
        validateSampleWindow(request == null ? null : request.getSampleWindowMs(), warnings, errors);
        validateOptionalPositiveField(
            request == null ? null : request.getEnginePulsesPerRevHint(),
            "engine_pulses_per_rev_hint",
            ENGINE_PPR_HINT_WARNING_MIN,
            ENGINE_PPR_HINT_WARNING_MAX,
            ENGINE_PPR_HINT_HARD_MAX,
            warnings,
            errors
        );
        validateOptionalPositiveField(
            request == null ? null : request.getEngineRpmScale(),
            "engine_rpm_scale",
            ENGINE_RPM_SCALE_WARNING_MIN,
            ENGINE_RPM_SCALE_WARNING_MAX,
            ENGINE_RPM_SCALE_HARD_MAX,
            warnings,
            errors
        );

        return CalibrationValidationDto.of(errors.isEmpty(), warnings, errors);
    }

    private static void validateName(String value, List<String> errors) {
        if (value == null || value.trim().isEmpty()) {
            errors.add("name: must not be blank");
        }
    }

    private static void validateRollerDiameter(Double value, List<String> warnings, List<String> errors) {
        if (!isFinite(value)) {
            errors.add("roller_diameter_m: must be finite");
            return;
        }
        if (value.doubleValue() <= 0.0) {
            errors.add("roller_diameter_m: must be positive");
            return;
        }
        if (value.doubleValue() < ROLLER_DIAMETER_HARD_MIN_M || value.doubleValue() > ROLLER_DIAMETER_HARD_MAX_M) {
            errors.add("roller_diameter_m: must be between " + ROLLER_DIAMETER_HARD_MIN_M + " m and " + ROLLER_DIAMETER_HARD_MAX_M + " m");
            return;
        }
        if (value.doubleValue() < ROLLER_DIAMETER_WARNING_MIN_M || value.doubleValue() > ROLLER_DIAMETER_WARNING_MAX_M) {
            warnings.add("roller_diameter_m: outside the typical dyno range");
        }
    }

    private static void validateEncoderPulses(Double value, List<String> warnings, List<String> errors) {
        if (!isFinite(value)) {
            errors.add("encoder_pulses_per_rev: must be finite");
            return;
        }
        if (value.doubleValue() <= 0.0) {
            errors.add("encoder_pulses_per_rev: must be positive");
            return;
        }
        if (value.doubleValue() < ENCODER_PPR_HARD_MIN || value.doubleValue() > ENCODER_PPR_HARD_MAX) {
            errors.add("encoder_pulses_per_rev: must be between " + ENCODER_PPR_HARD_MIN + " and " + ENCODER_PPR_HARD_MAX);
            return;
        }
        if (value.doubleValue() < ENCODER_PPR_WARNING_MIN || value.doubleValue() > ENCODER_PPR_WARNING_MAX) {
            warnings.add("encoder_pulses_per_rev: outside the typical encoder range");
        }
    }

    private static void validateInertia(Double value, List<String> warnings, List<String> errors) {
        if (!isFinite(value)) {
            errors.add("roller_inertia_kg_m2: must be finite");
            return;
        }
        if (value.doubleValue() <= 0.0) {
            errors.add("roller_inertia_kg_m2: must be positive");
            return;
        }
        if (value.doubleValue() < ROLLER_INERTIA_HARD_MIN || value.doubleValue() > ROLLER_INERTIA_HARD_MAX) {
            errors.add("roller_inertia_kg_m2: must be between " + ROLLER_INERTIA_HARD_MIN + " and " + ROLLER_INERTIA_HARD_MAX + " kg·m²");
            return;
        }
        if (value.doubleValue() < ROLLER_INERTIA_WARNING_MIN || value.doubleValue() > ROLLER_INERTIA_WARNING_MAX) {
            warnings.add("roller_inertia_kg_m2: outside the typical dyno range");
        }
    }

    private static void validateSampleWindow(Long value, List<String> warnings, List<String> errors) {
        if (value == null) {
            errors.add("sample_window_ms: must be present");
            return;
        }
        if (value.longValue() <= 0L) {
            errors.add("sample_window_ms: must be positive");
            return;
        }
        if (value.longValue() > SAMPLE_WINDOW_HARD_MAX_MS) {
            errors.add("sample_window_ms: must be no greater than " + SAMPLE_WINDOW_HARD_MAX_MS + " ms");
            return;
        }
        if (value.longValue() < SAMPLE_WINDOW_WARNING_MIN_MS || value.longValue() > SAMPLE_WINDOW_WARNING_MAX_MS) {
            warnings.add("sample_window_ms: outside the typical measurement-window range");
        }
    }

    private static void validateOptionalPositiveField(
        Double value,
        String fieldName,
        double warningMin,
        double warningMax,
        double hardMax,
        List<String> warnings,
        List<String> errors
    ) {
        if (value == null) {
            return;
        }
        if (!isFinite(value)) {
            errors.add(fieldName + ": must be finite when provided");
            return;
        }
        if (value.doubleValue() <= 0.0) {
            errors.add(fieldName + ": must be positive when provided");
            return;
        }
        if (value.doubleValue() > hardMax) {
            errors.add(fieldName + ": must be no greater than " + hardMax + " when provided");
            return;
        }
        if (value.doubleValue() < warningMin || value.doubleValue() > warningMax) {
            warnings.add(fieldName + ": outside the typical range");
        }
    }

    private static boolean isFinite(Double value) {
        return value != null && !value.isNaN() && !value.isInfinite();
    }
}
