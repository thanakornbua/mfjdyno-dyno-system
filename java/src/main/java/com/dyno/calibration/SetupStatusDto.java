package com.dyno.calibration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class SetupStatusDto {
    @JsonProperty("password_set")
    private Boolean passwordSet;

    public boolean isPasswordSet() {
        return passwordSet != null && passwordSet.booleanValue();
    }
}
