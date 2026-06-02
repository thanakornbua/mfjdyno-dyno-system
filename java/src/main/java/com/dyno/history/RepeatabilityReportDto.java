package com.dyno.history;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class RepeatabilityReportDto {
    @JsonProperty("run_ids") private List<Long> runIds;
    @JsonProperty("peak_hp") private RepeatabilityMetricDto peakHp;
    @JsonProperty("peak_torque_nm") private RepeatabilityMetricDto peakTorqueNm;
    @JsonProperty("peak_speed_kmh") private RepeatabilityMetricDto peakSpeedKmh;

    public List<Long> getRunIds() { return runIds; }
    public RepeatabilityMetricDto getPeakHp() { return peakHp; }
    public RepeatabilityMetricDto getPeakTorqueNm() { return peakTorqueNm; }
    public RepeatabilityMetricDto getPeakSpeedKmh() { return peakSpeedKmh; }
}
