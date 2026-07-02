package com.dyno.presenter;

public final class RunConfiguration {
    private final String licensePlate;
    private final String customerName;
    private final String customerPhone;
    private final String notes;
    private final RunAxisSelection axisSelection;
    private final ChartScaleSettings scaleSettings;

    public RunConfiguration(String licensePlate, RunAxisSelection axisSelection) {
        this(licensePlate, axisSelection, ChartScaleSettings.defaults());
    }

    public RunConfiguration(String licensePlate, RunAxisSelection axisSelection, ChartScaleSettings scaleSettings) {
        this(licensePlate, null, null, null, axisSelection, scaleSettings);
    }

    public RunConfiguration(String licensePlate, String customerName, String customerPhone, String notes,
                            RunAxisSelection axisSelection, ChartScaleSettings scaleSettings) {
        this.licensePlate = licensePlate == null ? "" : licensePlate.trim();
        this.customerName = customerName == null ? "" : customerName.trim();
        this.customerPhone = customerPhone == null ? "" : customerPhone.trim();
        this.notes = notes == null ? "" : notes.trim();
        this.axisSelection = axisSelection == null ? RunAxisSelection.defaults() : axisSelection;
        this.scaleSettings = scaleSettings == null ? ChartScaleSettings.defaults() : scaleSettings;
    }

    public static RunConfiguration defaults(String licensePlate) {
        return new RunConfiguration(licensePlate, RunAxisSelection.defaults());
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

    public String getNotes() {
        return notes;
    }

    public RunAxisSelection getAxisSelection() {
        return axisSelection;
    }

    public ChartScaleSettings getScaleSettings() {
        return scaleSettings;
    }

    public RunConfiguration withLicensePlate(String nextPlate) {
        return new RunConfiguration(nextPlate, customerName, customerPhone, notes, axisSelection, scaleSettings);
    }
}
