package com.dyno.calibration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class CalibrationResponseDto {
    private CalibrationProfileDto profile;
    private CalibrationValidationDto validation;
    private Boolean activated;

    @JsonProperty("locked")
    private Boolean locked;

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

    public boolean isLocked() {
        return locked != null && locked.booleanValue();
    }
}
