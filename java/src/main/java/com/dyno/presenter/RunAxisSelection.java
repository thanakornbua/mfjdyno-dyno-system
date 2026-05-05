package com.dyno.presenter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RunAxisSelection {
    private final RunChartAxis xAxis;
    private final RunChartAxis y1Axis;
    private final RunChartAxis y2Axis;
    private final RunChartAxis y3Axis;

    public RunAxisSelection(
        RunChartAxis xAxis,
        RunChartAxis y1Axis,
        RunChartAxis y2Axis,
        RunChartAxis y3Axis
    ) {
        this.xAxis = xAxis == null ? RunChartAxis.ENGINE_RPM : xAxis;
        this.y1Axis = y1Axis == null ? RunChartAxis.POWER : y1Axis;
        this.y2Axis = y2Axis == null ? RunChartAxis.TORQUE : y2Axis;
        this.y3Axis = y3Axis == null ? RunChartAxis.AFR : y3Axis;
    }

    public static RunAxisSelection defaults() {
        return new RunAxisSelection(
            RunChartAxis.ENGINE_RPM,
            RunChartAxis.POWER,
            RunChartAxis.TORQUE,
            RunChartAxis.AFR
        );
    }

    public RunChartAxis getXAxis() {
        return xAxis;
    }

    public RunChartAxis getY1Axis() {
        return y1Axis;
    }

    public RunChartAxis getY2Axis() {
        return y2Axis;
    }

    public RunChartAxis getY3Axis() {
        return y3Axis;
    }

    public List<RunChartAxis> yAxes() {
        ArrayList<RunChartAxis> axes = new ArrayList<RunChartAxis>(3);
        axes.add(y1Axis);
        axes.add(y2Axis);
        axes.add(y3Axis);
        return Collections.unmodifiableList(axes);
    }

    public String captionText() {
        return xAxis.getLabel() + " vs " + y1Axis.getLabel() + " / " + y2Axis.getLabel() + " / " + y3Axis.getLabel();
    }

    public String summaryText() {
        return "X: " + xAxis.getLabel()
            + "  |  Y1: " + y1Axis.getLabel()
            + "  |  Y2: " + y2Axis.getLabel()
            + "  |  Y3: " + y3Axis.getLabel();
    }
}
