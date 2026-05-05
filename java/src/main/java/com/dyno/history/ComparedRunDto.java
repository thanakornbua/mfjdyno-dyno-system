package com.dyno.history;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class ComparedRunDto {
    private RunHistoryDetailDto run;
    private List<RunHistoryFrameDto> frames;

    public RunHistoryDetailDto getRun() {
        return run;
    }

    public List<RunHistoryFrameDto> getFrames() {
        return frames;
    }
}
