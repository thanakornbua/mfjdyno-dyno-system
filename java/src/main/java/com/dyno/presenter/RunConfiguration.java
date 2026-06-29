package com.dyno.presenter;

public final class RunConfiguration {
    private final String licensePlate;
    private final RunAxisSelection axisSelection;
    private final ChartScaleSettings scaleSettings;

    public RunConfiguration(String licensePlate, RunAxisSelection axisSelection) {
        this(licensePlate, axisSelection, ChartScaleSettings.defaults());
    }

    public RunConfiguration(String licensePlate, RunAxisSelection axisSelection, ChartScaleSettings scaleSettings) {
        this.licensePlate = licensePlate == null ? "" : licensePlate.trim();
        this.axisSelection = axisSelection == null ? RunAxisSelection.defaults() : axisSelection;
        this.scaleSettings = scaleSettings == null ? ChartScaleSettings.defaults() : scaleSettings;
    }

    public static RunConfiguration defaults(String licensePlate) {
        return new RunConfiguration(licensePlate, RunAxisSelection.defaults());
    }

    public String getLicensePlate() {
        return licensePlate;
    }

    public RunAxisSelection getAxisSelection() {
        return axisSelection;
    }

    public ChartScaleSettings getScaleSettings() {
        return scaleSettings;
    }

    public RunConfiguration withLicensePlate(String nextPlate) {
        return new RunConfiguration(nextPlate, axisSelection, scaleSettings);
    }
}
