package com.dyno.history;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class CompareRunsRequestDto {
    @JsonProperty("run_ids")
    private final List<Long> runIds;

    public CompareRunsRequestDto(List<Long> runIds) {
        this.runIds = runIds;
    }

    public List<Long> getRunIds() {
        return runIds;
    }
}
