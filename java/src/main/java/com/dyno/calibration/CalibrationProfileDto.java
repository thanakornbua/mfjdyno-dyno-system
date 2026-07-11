package com.dyno.calibration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class CalibrationProfileDto {
    @JsonProperty("profile_id")
    private Long profileId;

    private String name;

    @JsonProperty("created_at_ms")
    private Long createdAtMs;

    @JsonProperty("updated_at_ms")
    private Long updatedAtMs;

    @JsonProperty("is_active")
    private Boolean active;

    @JsonProperty("roller_diameter_m")
    private Double rollerDiameterM;

    @JsonProperty("encoder_pulses_per_rev")
    private Double encoderPulsesPerRev;

    @JsonProperty("roller_inertia_kg_m2")
    private Double rollerInertiaKgM2;

    @JsonProperty("sample_window_ms")
    private Long sampleWindowMs;

    @JsonProperty("engine_pulses_per_rev_hint")
    private Double enginePulsesPerRevHint;

    @JsonProperty("engine_rpm_scale")
    private Double engineRpmScale;

    @JsonProperty("engine_stroke")
    private Integer engineStroke;

    @JsonProperty("engine_cylinders")
    private Integer engineCylinders;

    private String notes;

    public Long getProfileId() {
        return profileId;
    }

    public String getName() {
        return name;
    }

    public Long getCreatedAtMs() {
        return createdAtMs;
    }

    public Long getUpdatedAtMs() {
        return updatedAtMs;
    }

    public Boolean getActive() {
        return active;
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
}
