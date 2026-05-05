package com.dyno.history;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class CompareRunsResponseDto {
    private List<ComparedRunDto> runs;

    public List<ComparedRunDto> getRuns() {
        return runs;
    }
}
