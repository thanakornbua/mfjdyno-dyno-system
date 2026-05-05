package com.dyno.calibration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class CalibrationProfileEventDto {
    @JsonProperty("event_id")
    private Long eventId;

    @JsonProperty("profile_id")
    private Long profileId;

    @JsonProperty("event_type")
    private String eventType;

    @JsonProperty("created_at_ms")
    private Long createdAtMs;

    private String summary;

    @JsonProperty("previous_values_json")
    private JsonNode previousValuesJson;

    @JsonProperty("new_values_json")
    private JsonNode newValuesJson;

    public Long getEventId() {
        return eventId;
    }

    public Long getProfileId() {
        return profileId;
    }

    public String getEventType() {
        return eventType;
    }

    public Long getCreatedAtMs() {
        return createdAtMs;
    }

    public String getSummary() {
        return summary;
    }

    public JsonNode getPreviousValuesJson() {
        return previousValuesJson;
    }

    public JsonNode getNewValuesJson() {
        return newValuesJson;
    }
}
