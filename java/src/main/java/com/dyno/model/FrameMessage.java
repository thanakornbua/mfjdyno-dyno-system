package com.dyno.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class FrameMessage {
    @JsonProperty("run_state")
    @JsonAlias("state")
    private String runState;

    @JsonProperty("ts_ms")
    private Double tsMs;

    @JsonProperty("ts")
    private Double tsSeconds;

    @JsonProperty("speed_kmh")
    private Double speedKmh;

    @JsonProperty("engine_rpm")
    private Double engineRpm;

    @JsonProperty("roller_rpm")
    @JsonAlias("rpm")
    private Double rollerRpm;

    @JsonProperty("torque_nm")
    private Double torqueNm;

    @JsonProperty("power_hp")
    private Double powerHp;

    @JsonProperty("correction_factor")
    @JsonAlias("sae_cf")
    private Double correctionFactor;

    private Double afr;
    private Double lambda;

    @JsonProperty("ambient_temp_c")
    @JsonAlias("temp")
    private Double ambientTempC;

    @JsonProperty("pressure_hpa")
    private Double pressureHpa;

    @JsonProperty("humidity_pct")
    private Double humidityPct;

    private List<String> faults;
    private Map<String, String> alerts;

    public FrameMessage() {
    }

    public String getState() {
        return runState;
    }

    public Double getTs() {
        if (tsMs != null) {
            return Double.valueOf(tsMs.doubleValue() / 1000.0d);
        }
        return tsSeconds;
    }

    public Double getRpm() {
        return rollerRpm;
    }

    public Double getSpeedKmh() {
        return speedKmh;
    }

    public Double getEngineRpm() {
        return engineRpm;
    }

    public Double getRollerRpm() {
        return rollerRpm;
    }

    public Double getTorqueNm() {
        return torqueNm;
    }

    public Double getPowerHp() {
        return powerHp;
    }

    public Double getAfr() {
        return afr;
    }

    public Double getLambda() {
        return lambda;
    }

    public Double getTemp() {
        return ambientTempC;
    }

    public Double getAmbientTempC() {
        return ambientTempC;
    }

    public Double getPressureHpa() {
        return pressureHpa;
    }

    public Double getPressureKpa() {
        return pressureHpa != null ? Double.valueOf(pressureHpa.doubleValue() / 10.0d) : null;
    }

    public Double getHumidityPct() {
        return humidityPct;
    }

    public Integer getFaultCount() {
        return faults != null ? Integer.valueOf(faults.size()) : null;
    }

    public Double getSaeCf() {
        return correctionFactor;
    }

    public Double getCorrectionFactor() {
        return correctionFactor;
    }

    public Double getTorqueCorrNm() {
        return torqueNm;
    }

    public Double getPowerCorrHp() {
        return powerHp;
    }

    public Boolean getRelay1Closed() {
        return null;
    }

    public Boolean getRelay2Closed() {
        return null;
    }

    public Double getZeroToMaxSec() {
        return null;
    }

    public Double getZeroToMaxPeakKmh() {
        return null;
    }

    public Double getZeroToMaxLiveSec() {
        return null;
    }

    public List<String> getFaults() {
        return faults;
    }

    public Map<String, String> getAlerts() {
        return alerts;
    }
}
