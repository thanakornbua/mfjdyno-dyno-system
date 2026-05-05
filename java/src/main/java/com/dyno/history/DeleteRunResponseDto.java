package com.dyno.history;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class DeleteRunResponseDto {
    @JsonProperty("run_id")
    private Long runId;

    private Boolean deleted;

    public Long getRunId() {
        return runId;
    }

    public Boolean getDeleted() {
        return deleted;
    }
}
