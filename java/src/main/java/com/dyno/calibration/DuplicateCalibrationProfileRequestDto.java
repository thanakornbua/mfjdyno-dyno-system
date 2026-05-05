package com.dyno.calibration;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class DuplicateCalibrationProfileRequestDto {
    private final String name;

    @JsonProperty("activate_after_save")
    private final Boolean activateAfterSave;

    public DuplicateCalibrationProfileRequestDto(String name, Boolean activateAfterSave) {
        this.name = name;
        this.activateAfterSave = activateAfterSave;
    }

    public String getName() {
        return name;
    }

    public Boolean getActivateAfterSave() {
        return activateAfterSave;
    }
}
