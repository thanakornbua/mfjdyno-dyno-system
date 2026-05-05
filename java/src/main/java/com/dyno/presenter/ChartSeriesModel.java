package com.dyno.presenter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ChartSeriesModel {
    private final String id;
    private final String label;
    private final String colorHex;
    private final List<ChartPlotPoint> points;

    public ChartSeriesModel(String id, String label, String colorHex, List<ChartPlotPoint> points) {
        this.id = id;
        this.label = label;
        this.colorHex = colorHex;
        this.points = Collections.unmodifiableList(new ArrayList<ChartPlotPoint>(points));
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public String getColorHex() {
        return colorHex;
    }

    public List<ChartPlotPoint> getPoints() {
        return points;
    }
}
