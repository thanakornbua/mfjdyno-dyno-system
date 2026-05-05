package com.dyno.calibration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class CalibrationResponseDto {
    private CalibrationProfileDto profile;
    private CalibrationValidationDto validation;
    private Boolean activated;

    public CalibrationProfileDto getProfile() {
        return profile;
    }

    public CalibrationValidationDto getValidation() {
        return validation;
    }

    public Boolean getActivated() {
        return activated;
    }

    public boolean isActivated() {
        return Boolean.TRUE.equals(activated);
    }
}
