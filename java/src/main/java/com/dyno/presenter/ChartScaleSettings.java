package com.dyno.presenter;

public final class ChartScaleSettings {
    private final double rpmMax;
    private final double rpmInterval;
    private final double afrMax;
    private final double afrInterval;
    private final double speedMax;
    private final double speedInterval;
    private final double valueMax;
    private final double valueInterval;

    public ChartScaleSettings(
        double rpmMax,
        double rpmInterval,
        double afrMax,
        double afrInterval,
        double speedMax,
        double speedInterval,
        double valueMax,
        double valueInterval
    ) {
        this.rpmMax = positiveOr(rpmMax, 8000.0d);
        this.rpmInterval = positiveOr(rpmInterval, 1000.0d);
        this.afrMax = positiveOr(afrMax, 20.0d);
        this.afrInterval = positiveOr(afrInterval, 1.0d);
        this.speedMax = positiveOr(speedMax, 200.0d);
        this.speedInterval = positiveOr(speedInterval, 20.0d);
        this.valueMax = positiveOr(valueMax, 400.0d);
        this.valueInterval = positiveOr(valueInterval, 50.0d);
    }

    public static ChartScaleSettings defaults() {
        return new ChartScaleSettings(
            8000.0d, 1000.0d,
            20.0d, 1.0d,
            200.0d, 20.0d,
            400.0d, 50.0d
        );
    }

    public double getRpmMax() {
        return rpmMax;
    }

    public double getRpmInterval() {
        return rpmInterval;
    }

    public double getAfrMax() {
        return afrMax;
    }

    public double getAfrInterval() {
        return afrInterval;
    }

    public double getSpeedMax() {
        return speedMax;
    }

    public double getSpeedInterval() {
        return speedInterval;
    }

    public double getValueMax() {
        return valueMax;
    }

    public double getValueInterval() {
        return valueInterval;
    }

    public double xMaxForLabel(String label, double observedMax) {
        if (label != null && label.startsWith("Speed")) {
            return roundUp(observedMax, speedInterval, speedMax);
        }
        if (label != null && label.startsWith("Time")) {
            return roundUp(observedMax, 1.0d, 10.0d);
        }
        return roundUp(observedMax, rpmInterval, rpmMax);
    }

    public double xIntervalForLabel(String label) {
        if (label != null && label.startsWith("Speed")) {
            return speedInterval;
        }
        if (label != null && label.startsWith("Time")) {
            return 1.0d;
        }
        return rpmInterval;
    }

    public double yMaxForLabel(String label, double observedMax) {
        if (label != null && label.contains("AFR")) {
            return roundUp(observedMax, afrInterval, afrMax);
        }
        if (label != null && label.contains("Speed")) {
            return roundUp(observedMax, speedInterval, speedMax);
        }
        if (label != null && label.contains("RPM")) {
            return roundUp(observedMax, rpmInterval, rpmMax);
        }
        return roundUp(observedMax, valueInterval, valueMax);
    }

    public double yIntervalForLabel(String label) {
        if (label != null && label.contains("AFR")) {
            return afrInterval;
        }
        if (label != null && label.contains("Speed")) {
            return speedInterval;
        }
        if (label != null && label.contains("RPM")) {
            return rpmInterval;
        }
        return valueInterval;
    }

    public String summaryText() {
        return "RPM " + fmt(rpmInterval) + "/" + fmt(rpmMax)
            + " | AFR " + fmt(afrInterval) + "/" + fmt(afrMax)
            + " | Speed " + fmt(speedInterval) + "/" + fmt(speedMax)
            + " | Grid " + fmt(valueInterval) + "/" + fmt(valueMax);
    }

    private static double roundUp(double value, double step, double minimum) {
        double safeStep = positiveOr(step, 1.0d);
        double safeMinimum = positiveOr(minimum, safeStep);
        double rounded = Math.ceil(Math.max(value, safeMinimum) / safeStep) * safeStep;
        return Math.max(rounded, safeMinimum);
    }

    private static double positiveOr(double value, double fallback) {
        return Double.isFinite(value) && value > 0.0d ? value : fallback;
    }

    private static String fmt(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001d) {
            return Integer.toString((int) Math.rint(value));
        }
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
