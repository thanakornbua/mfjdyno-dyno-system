package com.dyno.presenter;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ChartCursorLocatorTest {

    private static ChartSeriesModel series(String label, double[][] xy) {
        List<ChartPlotPoint> points = new ArrayList<ChartPlotPoint>();
        for (double[] p : xy) {
            points.add(new ChartPlotPoint(p[0], p[1]));
        }
        return new ChartSeriesModel("id-" + label, label, "#00FFE5", points);
    }

    @Test
    public void exactHitReturnsPointValue() {
        ChartSeriesModel s = series("Power", new double[][]{{1000, 10}, {2000, 40}, {3000, 90}});
        assertEquals(40.0, ChartCursorLocator.readingsAt(Collections.singletonList(s), 2000).get(0).getY(), 1e-9);
    }

    @Test
    public void interpolatesBetweenPoints() {
        ChartSeriesModel s = series("Power", new double[][]{{1000, 10}, {2000, 40}});
        assertEquals(25.0, ChartCursorLocator.readingsAt(Collections.singletonList(s), 1500).get(0).getY(), 1e-9);
    }

    @Test
    public void clampsOutsideRange() {
        ChartSeriesModel s = series("Torque", new double[][]{{1000, 10}, {2000, 40}});
        List<ChartSeriesModel> list = Collections.singletonList(s);
        assertEquals(10.0, ChartCursorLocator.readingsAt(list, 500).get(0).getY(), 1e-9);
        assertEquals(40.0, ChartCursorLocator.readingsAt(list, 9000).get(0).getY(), 1e-9);
    }

    @Test
    public void skipsEmptySeriesAndKeepsLabelsAndColors() {
        ChartSeriesModel empty = series("Empty", new double[][]{});
        ChartSeriesModel full = series("ABC 123-2 Power", new double[][]{{1000, 10}});
        List<ChartCursorLocator.Reading> readings =
            ChartCursorLocator.readingsAt(Arrays.asList(empty, full), 1000);
        assertEquals(1, readings.size());
        assertEquals("ABC 123-2 Power", readings.get(0).getSeriesLabel());
        assertEquals("#00FFE5", readings.get(0).getColorHex());
    }

    @Test
    public void nullSeriesListYieldsNoReadings() {
        assertTrue(ChartCursorLocator.readingsAt(null, 1000).isEmpty());
    }
}
