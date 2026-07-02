package com.dyno.control;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class RunConfigureRequest {
    @JsonProperty("license_plate")
    private final String licensePlate;

    @JsonProperty("run_mode")
    private final String runMode;

    @JsonProperty("notes")
    private final String notes;

    @JsonProperty("customer_name")
    private final String customerName;

    @JsonProperty("customer_phone")
    private final String customerPhone;

    public RunConfigureRequest(String licensePlate, String runMode, String notes) {
        this(licensePlate, runMode, notes, null, null);
    }

    public RunConfigureRequest(String licensePlate, String runMode, String notes,
                               String customerName, String customerPhone) {
        this.licensePlate = licensePlate;
        this.runMode = runMode;
        this.notes = notes;
        this.customerName = customerName;
        this.customerPhone = customerPhone;
    }

    public String getLicensePlate() {
        return licensePlate;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public String getRunMode() {
        return runMode;
    }

    public String getNotes() {
        return notes;
    }
}
