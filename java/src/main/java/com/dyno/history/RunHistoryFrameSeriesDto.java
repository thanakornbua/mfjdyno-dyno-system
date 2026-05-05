package com.dyno.history;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class RunHistoryFrameSeriesDto {
    @JsonProperty("run_id")
    private Long runId;

    private List<RunHistoryFrameDto> frames;

    public Long getRunId() {
        return runId;
    }

    public List<RunHistoryFrameDto> getFrames() {
        return frames;
    }
}
