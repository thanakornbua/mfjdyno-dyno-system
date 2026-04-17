package com.dyno.operator.config.model;

import java.util.List;

public record Esp32DaqConfigValidationDto(
    boolean valid,
    List<String> errors,
    List<String> warnings
) {
    public Esp32DaqConfigValidationDto {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
