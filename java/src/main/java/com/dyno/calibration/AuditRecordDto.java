package com.dyno.calibration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class AuditRecordDto {
    @JsonProperty("id")
    private long id;

    @JsonProperty("occurred_at")
    private String occurredAt;

    @JsonProperty("event")
    private String event;

    @JsonProperty("calibration_profile_id")
    private Long calibrationProfileId;

    @JsonProperty("params_snapshot")
    private Object paramsSnapshot;

    public long getId() {
        return id;
    }

    public String getOccurredAt() {
        return occurredAt;
    }

    public String getEvent() {
        return event;
    }

    public Long getCalibrationProfileId() {
        return calibrationProfileId;
    }

    public Object getParamsSnapshot() {
        return paramsSnapshot;
    }
}
