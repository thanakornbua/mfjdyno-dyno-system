package com.dyno.history;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class RunHistorySummaryDto {
    @JsonProperty("run_id")
    private Long runId;

    @JsonProperty("started_at_ms")
    private Long startedAtMs;

    @JsonProperty("ended_at_ms")
    private Long endedAtMs;

    private String date;

    @JsonProperty("source_mode")
    private String sourceMode;

    @JsonProperty("correction_mode")
    private String correctionMode;

    @JsonProperty("vehicle_name")
    private String vehicleName;

    @JsonProperty("license_plate")
    private String licensePlate;

    @JsonProperty("run_no")
    private Long runNo;

    @JsonProperty("display_id")
    private String displayId;

    @JsonProperty("customer_name")
    private String customerName;

    @JsonProperty("customer_phone")
    private String customerPhone;

    @JsonProperty("notes")
    private String notes;

    @JsonProperty("peak_power_hp")
    private Double peakPowerHp;

    @JsonProperty("peak_power_rpm")
    private Double peakPowerRpm;

    @JsonProperty("peak_torque_nm")
    private Double peakTorqueNm;

    @JsonProperty("peak_torque_rpm")
    private Double peakTorqueRpm;

    public Long getRunId() {
        return runId;
    }

    public Long getStartedAtMs() {
        return startedAtMs;
    }

    public Long getEndedAtMs() {
        return endedAtMs;
    }

    public String getDate() {
        return date;
    }

    public String getSourceMode() {
        return sourceMode;
    }

    public String getCorrectionMode() {
        return correctionMode;
    }

    public String getVehicleName() {
        return vehicleName;
    }

    public String getLicensePlate() {
        return licensePlate;
    }

    public Long getRunNo() {
        return runNo;
    }

    public String getDisplayId() {
        return displayId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public String getNotes() {
        return notes;
    }

    public Double getPeakPowerHp() {
        return peakPowerHp;
    }

    public Double getPeakPowerRpm() {
        return peakPowerRpm;
    }

    public Double getPeakTorqueNm() {
        return peakTorqueNm;
    }

    public Double getPeakTorqueRpm() {
        return peakTorqueRpm;
    }
}
