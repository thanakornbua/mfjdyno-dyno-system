package com.dyno.presenter;

public final class ChartPlotPoint {
    private final double x;
    private final double y;

    public ChartPlotPoint(double x, double y) {
        this.x = x;
        this.y = y;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }
}
