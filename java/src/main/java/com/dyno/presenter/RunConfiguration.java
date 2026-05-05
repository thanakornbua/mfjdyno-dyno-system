package com.dyno.presenter;

public final class RunConfiguration {
    private final String licensePlate;
    private final RunAxisSelection axisSelection;

    public RunConfiguration(String licensePlate, RunAxisSelection axisSelection) {
        this.licensePlate = licensePlate == null ? "" : licensePlate.trim();
        this.axisSelection = axisSelection == null ? RunAxisSelection.defaults() : axisSelection;
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

    public RunConfiguration withLicensePlate(String nextPlate) {
        return new RunConfiguration(nextPlate, axisSelection);
    }
}
