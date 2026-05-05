package com.dyno.history;

import java.util.List;

public final class CompareRunsRequestDto {
    private final List<Long> runIds;

    public CompareRunsRequestDto(List<Long> runIds) {
        this.runIds = runIds;
    }

    public List<Long> getRunIds() {
        return runIds;
    }
}
