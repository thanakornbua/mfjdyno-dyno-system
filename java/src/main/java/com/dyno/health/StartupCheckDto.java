package com.dyno.health;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class StartupCheckDto {
    @JsonProperty("name")
    private String name;
    @JsonProperty("required")
    private Boolean required;
    @JsonProperty("status")
    private String status;
    @JsonProperty("summary")
    private String summary;

    public String getName() {
        return name;
    }

    public Boolean getRequired() {
        return required;
    }

    public String getStatus() {
        return status;
    }

    public String getSummary() {
        return summary;
    }
}
