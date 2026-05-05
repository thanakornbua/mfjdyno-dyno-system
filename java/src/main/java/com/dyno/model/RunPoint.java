package com.dyno.model;

public final class RunPoint {
    private final double engineRpm;
    private final double powerHp;
    private final double torqueNm;

    public RunPoint(double engineRpm, double powerHp, double torqueNm) {
        this.engineRpm = engineRpm;
        this.powerHp = powerHp;
        this.torqueNm = torqueNm;
    }

    public double getEngineRpm() {
        return engineRpm;
    }

    public double getPowerHp() {
        return powerHp;
    }

    public double getTorqueNm() {
        return torqueNm;
    }
}
