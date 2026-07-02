package com.dyno.presenter;

import java.util.ArrayList;
import java.util.List;

/**
 * Nearest-point lookup for the chart hover cursor: given the chart's series
 * and an x value, returns one interpolated reading per series.
 *
 * Series points are assumed x-sorted (live data is appended in x order and
 * overlay series are built from x-sorted frames). X values outside a series'
 * range clamp to its end points; empty series are skipped.
 */
public final class ChartCursorLocator {

    public static final class Reading {
        private final String seriesLabel;
        private final String colorHex;
        private final double y;

        Reading(String seriesLabel, String colorHex, double y) {
            this.seriesLabel = seriesLabel;
            this.colorHex = colorHex;
            this.y = y;
        }

        public String getSeriesLabel() {
            return seriesLabel;
        }

        public String getColorHex() {
            return colorHex;
        }

        public double getY() {
            return y;
        }
    }

    private ChartCursorLocator() {
    }

    public static List<Reading> readingsAt(List<ChartSeriesModel> series, double x) {
        List<Reading> readings = new ArrayList<Reading>();
        if (series == null) {
            return readings;
        }
        for (ChartSeriesModel model : series) {
            if (model == null || model.getPoints() == null || model.getPoints().isEmpty()) {
                continue;
            }
            readings.add(new Reading(model.getLabel(), model.getColorHex(), yAt(model.getPoints(), x)));
        }
        return readings;
    }

    static double yAt(List<ChartPlotPoint> points, double x) {
        int size = points.size();
        if (x <= points.get(0).getX()) {
            return points.get(0).getY();
        }
        if (x >= points.get(size - 1).getX()) {
            return points.get(size - 1).getY();
        }
        // Binary search for the first point with px >= x.
        int lo = 0;
        int hi = size - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (points.get(mid).getX() < x) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        ChartPlotPoint upper = points.get(lo);
        ChartPlotPoint lower = points.get(lo - 1);
        double dx = upper.getX() - lower.getX();
        if (dx <= 0) {
            return upper.getY();
        }
        double t = (x - lower.getX()) / dx;
        return lower.getY() + t * (upper.getY() - lower.getY());
    }
}
