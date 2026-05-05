package com.dyno.health;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class StartupHealthDto {
    @JsonProperty("status")
    private String status;
    @JsonProperty("source_mode")
    private String sourceMode;
    @JsonProperty("checks")
    private List<StartupCheckDto> checks;

    public String getStatus() {
        return status;
    }

    public String getSourceMode() {
        return sourceMode;
    }

    public List<StartupCheckDto> getChecks() {
        return checks;
    }
}
