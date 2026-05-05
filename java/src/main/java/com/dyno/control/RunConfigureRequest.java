package com.dyno.control;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class RunConfigureRequest {
    @JsonProperty("license_plate")
    private final String licensePlate;

    @JsonProperty("run_mode")
    private final String runMode;

    @JsonProperty("notes")
    private final String notes;

    public RunConfigureRequest(String licensePlate, String runMode, String notes) {
        this.licensePlate = licensePlate;
        this.runMode = runMode;
        this.notes = notes;
    }

    public String getLicensePlate() {
        return licensePlate;
    }

    public String getRunMode() {
        return runMode;
    }

    public String getNotes() {
        return notes;
    }
}
