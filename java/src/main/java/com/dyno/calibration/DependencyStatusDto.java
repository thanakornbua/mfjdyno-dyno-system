package com.dyno.calibration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class DependencyStatusDto {
    private List<DependencyDto> dependencies;

    public List<DependencyDto> getDependencies() {
        return dependencies == null ? List.of() : dependencies;
    }
}
