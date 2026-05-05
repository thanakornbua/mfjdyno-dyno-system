package com.dyno.calibration;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class ActivateCalibrationRequestDto {
    @JsonProperty("profile_id")
    private final Long profileId;

    public ActivateCalibrationRequestDto(Long profileId) {
        this.profileId = profileId;
    }

    public Long getProfileId() {
        return profileId;
    }
}
