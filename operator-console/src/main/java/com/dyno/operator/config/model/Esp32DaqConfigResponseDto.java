package com.dyno.operator.config.model;

public record Esp32DaqConfigResponseDto(
    Esp32DaqConfigDto config,
    Esp32DaqConfigValidationDto validation,
    String statusMessage,
    boolean applied
) {
}
