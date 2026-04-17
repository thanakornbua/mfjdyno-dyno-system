package com.dyno.operator.config.model;

public record Esp32DaqConfigDto(
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
    public Esp32DaqConfigUpdateRequestDto toUpdateRequest() {
        return new Esp32DaqConfigUpdateRequestDto(
            enginePulsePin,
            enginePulsesPerRev,
            engineEdgeMode,
            encoderPin,
            encoderPpr,
            canRxPin,
            canTxPin,
            canBitrate,
            uartTxPin,
            uartRxPin,
            uartBaud,
            telemetryRateHz
        );
    }
}
