package com.dyno.operator.config.model;

public record Esp32DaqConfigUpdateRequestDto(
    int enginePulsePin,
    double enginePulsesPerRev,
    EngineEdgeMode engineEdgeMode,
    int encoderPin,
    int encoderPpr,
    int canRxPin,
    int canTxPin,
    int canBitrate,
    int uartTxPin,
    int uartRxPin,
    int uartBaud,
    int telemetryRateHz
) {
}
