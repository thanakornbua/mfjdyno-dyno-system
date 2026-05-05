package com.dyno.history;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class RunHistoryFrameDto {
    @JsonProperty("run_id")
    private Long runId;

    @JsonProperty("ts_ms")
    private Long tsMs;

    @JsonProperty("engine_rpm")
    private Double engineRpm;

    @JsonProperty("roller_rpm")
    private Double rollerRpm;

    @JsonProperty("speed_kmh")
    private Double speedKmh;

    @JsonProperty("power_hp")
    private Double powerHp;

    @JsonProperty("torque_nm")
    private Double torqueNm;

    private Double afr;
    private Double lambda;

    @JsonProperty("ambient_temp_c")
    private Double ambientTempC;

    @JsonProperty("humidity_pct")
    private Double humidityPct;

    @JsonProperty("pressure_hpa")
    private Double pressureHpa;

    @JsonProperty("correction_factor")
    private Double correctionFactor;

    @JsonProperty("run_state")
    private String runState;

    public Long getRunId() {
        return runId;
    }

    public Long getTsMs() {
        return tsMs;
    }

    public Double getEngineRpm() {
        return engineRpm;
    }

    public Double getRollerRpm() {
        return rollerRpm;
    }

    public Double getSpeedKmh() {
        return speedKmh;
    }

    public Double getPowerHp() {
        return powerHp;
    }

    public Double getTorqueNm() {
        return torqueNm;
    }

    public Double getAfr() {
        return afr;
    }

    public Double getLambda() {
        return lambda;
    }

    public Double getAmbientTempC() {
        return ambientTempC;
    }

    public Double getHumidityPct() {
        return humidityPct;
    }

    public Double getPressureHpa() {
        return pressureHpa;
    }

    public Double getCorrectionFactor() {
        return correctionFactor;
    }

    public String getRunState() {
        return runState;
    }
}
