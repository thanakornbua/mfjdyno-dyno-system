package com.dyno.calibration;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class CalibrationUpsertRequestDto {
    private final String name;

    @JsonProperty("roller_diameter_m")
    private final Double rollerDiameterM;

    @JsonProperty("encoder_pulses_per_rev")
    private final Double encoderPulsesPerRev;

    @JsonProperty("roller_inertia_kg_m2")
    private final Double rollerInertiaKgM2;

    @JsonProperty("sample_window_ms")
    private final Long sampleWindowMs;

    @JsonProperty("engine_pulses_per_rev_hint")
    private final Double enginePulsesPerRevHint;

    @JsonProperty("engine_rpm_scale")
    private final Double engineRpmScale;

    @JsonProperty("engine_stroke")
    private final Integer engineStroke;

    @JsonProperty("engine_cylinders")
    private final Integer engineCylinders;

    private final String notes;

    @JsonProperty("activate_after_save")
    private final Boolean activateAfterSave;

    public CalibrationUpsertRequestDto(
        String name,
        Double rollerDiameterM,
        Double encoderPulsesPerRev,
        Double rollerInertiaKgM2,
        Long sampleWindowMs,
        Double enginePulsesPerRevHint,
        Double engineRpmScale,
        Integer engineStroke,
        Integer engineCylinders,
        String notes,
        Boolean activateAfterSave
    ) {
        this.name = name;
        this.rollerDiameterM = rollerDiameterM;
        this.encoderPulsesPerRev = encoderPulsesPerRev;
        this.rollerInertiaKgM2 = rollerInertiaKgM2;
        this.sampleWindowMs = sampleWindowMs;
        this.enginePulsesPerRevHint = enginePulsesPerRevHint;
        this.engineRpmScale = engineRpmScale;
        this.engineStroke = engineStroke;
        this.engineCylinders = engineCylinders;
        this.notes = notes;
        this.activateAfterSave = activateAfterSave;
    }

    public String getName() {
        return name;
    }

    public Double getRollerDiameterM() {
        return rollerDiameterM;
    }

    public Double getEncoderPulsesPerRev() {
        return encoderPulsesPerRev;
    }

    public Double getRollerInertiaKgM2() {
        return rollerInertiaKgM2;
    }

    public Long getSampleWindowMs() {
        return sampleWindowMs;
    }

    public Double getEnginePulsesPerRevHint() {
        return enginePulsesPerRevHint;
    }

    public Double getEngineRpmScale() {
        return engineRpmScale;
    }

    public Integer getEngineStroke() {
        return engineStroke;
    }

    public Integer getEngineCylinders() {
        return engineCylinders;
    }

    public String getNotes() {
        return notes;
    }

    public Boolean getActivateAfterSave() {
        return activateAfterSave;
    }
}
