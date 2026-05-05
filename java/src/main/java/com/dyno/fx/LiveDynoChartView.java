package com.dyno.fx;

import com.dyno.presenter.ChartPlotPoint;
import com.dyno.presenter.ChartSeriesModel;
import com.dyno.presenter.LiveDynoChartModel;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LiveDynoChartView extends StackPane {
    private static final int DEFAULT_RPM_MAX = 8000;
    private static final int DEFAULT_VALUE_MAX = 400;
    private static final int FLUSH_LIMIT = 96;

    private final NumberAxis xAxis = new NumberAxis(0, DEFAULT_RPM_MAX, 1000);
    private final NumberAxis valueAxis = new NumberAxis(0, DEFAULT_VALUE_MAX, 50);
    private final LineChart<Number, Number> chart = new LineChart<Number, Number>(xAxis, valueAxis);
    private final Timeline flushTimer;

    private final Map<String, XYChart.Series<Number, Number>> seriesById =
        new LinkedHashMap<String, XYChart.Series<Number, Number>>();
    private final Map<String, ChartSeriesModel> seriesModelById =
        new LinkedHashMap<String, ChartSeriesModel>();
    private final Map<String, Integer> enqueuedPointsBySeries = new LinkedHashMap<String, Integer>();
    private final Map<String, List<XYChart.Data<Number, Number>>> pendingPointsBySeries =
        new LinkedHashMap<String, List<XYChart.Data<Number, Number>>>();
    private String seriesSignature = "";
    private String currentXAxisLabel = "Engine RPM";

    private long datasetToken = Long.MIN_VALUE;
    private double maxRpm = DEFAULT_RPM_MAX;
    private double maxValue = DEFAULT_VALUE_MAX;

    public LiveDynoChartView() {
        xAxis.setLabel("Engine RPM");
        xAxis.setAutoRanging(false);
        xAxis.setForceZeroInRange(false);

        valueAxis.setLabel("Power / Torque");
        valueAxis.setAutoRanging(false);
        valueAxis.setForceZeroInRange(true);

        chart.setAnimated(false);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(true);
        chart.setHorizontalGridLinesVisible(true);
        chart.setVerticalGridLinesVisible(true);
        chart.setAlternativeColumnFillVisible(false);
        chart.setAlternativeRowFillVisible(false);
        chart.setTitle(null);
        chart.setStyle(
            "-fx-background-color: transparent;" +
                "-fx-padding: 0;" +
                "-fx-legend-side: top;"
        );
        chart.setMinHeight(360);
        chart.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        chart.setData(FXCollections.observableArrayList());

        getChildren().add(chart);

        flushTimer = new Timeline(new KeyFrame(Duration.millis(120), event -> flushPendingPoints()));
        flushTimer.setCycleCount(Animation.INDEFINITE);
        flushTimer.play();
    }

    public void render(LiveDynoChartModel model) {
        if (model == null) {
            return;
        }

        String nextSignature = buildSeriesSignature(model.getSeries());
        if (model.getDatasetToken() != datasetToken || !nextSignature.equals(seriesSignature)) {
            resetDataset(model.getDatasetToken());
        }

        seriesSignature = nextSignature;
        currentXAxisLabel = model.getXAxisLabel();
        xAxis.setLabel(model.getXAxisLabel());
        valueAxis.setLabel(model.getYAxisLabel());
        syncSeries(model.getSeries());
        updateAxes();

        for (int seriesIndex = 0; seriesIndex < model.getSeries().size(); seriesIndex++) {
            ChartSeriesModel seriesModel = model.getSeries().get(seriesIndex);
            int enqueued = enqueuedPointsBySeries.containsKey(seriesModel.getId())
                ? enqueuedPointsBySeries.get(seriesModel.getId()).intValue()
                : 0;
            List<ChartPlotPoint> points = seriesModel.getPoints();
            List<XYChart.Data<Number, Number>> pending = pendingPointsBySeries.get(seriesModel.getId());
            for (int pointIndex = enqueued; pointIndex < points.size(); pointIndex++) {
                ChartPlotPoint point = points.get(pointIndex);
                pending.add(new XYChart.Data<Number, Number>(
                    Double.valueOf(point.getX()),
                    Double.valueOf(point.getY())
                ));
                maxRpm = Math.max(maxRpm, point.getX());
                maxValue = Math.max(maxValue, point.getY());
            }
            enqueuedPointsBySeries.put(seriesModel.getId(), Integer.valueOf(points.size()));
        }
    }

    private void resetDataset(long nextToken) {
        datasetToken = nextToken;
        seriesSignature = "";
        chart.setData(FXCollections.observableArrayList());
        seriesById.clear();
        seriesModelById.clear();
        enqueuedPointsBySeries.clear();
        pendingPointsBySeries.clear();
        maxRpm = DEFAULT_RPM_MAX;
        maxValue = DEFAULT_VALUE_MAX;
        xAxis.setLowerBound(0);
        valueAxis.setLowerBound(0);
        valueAxis.setUpperBound(DEFAULT_VALUE_MAX);
        valueAxis.setTickUnit(50);
    }

    private void flushPendingPoints() {
        if (pendingPointsBySeries.isEmpty()) {
            return;
        }

        boolean flushed = false;
        for (Map.Entry<String, List<XYChart.Data<Number, Number>>> entry : pendingPointsBySeries.entrySet()) {
            List<XYChart.Data<Number, Number>> pending = entry.getValue();
            if (pending.isEmpty()) {
                continue;
            }

            int batchSize = Math.min(FLUSH_LIMIT, pending.size());
            List<XYChart.Data<Number, Number>> batch =
                new ArrayList<XYChart.Data<Number, Number>>(pending.subList(0, batchSize));
            pending.subList(0, batchSize).clear();
            XYChart.Series<Number, Number> series = seriesById.get(entry.getKey());
            if (series != null) {
                series.getData().addAll(batch);
                flushed = true;
            }
        }

        if (flushed) {
            updateAxes();
            applySeriesStyle();
        }
    }

    private void updateAxes() {
        double defaultXStep = defaultXStepForLabel(currentXAxisLabel);
        double defaultXMax = defaultXMaxForLabel(currentXAxisLabel);
        double roundedRpmMax = roundUp(maxRpm, defaultXStep, defaultXMax);
        double roundedValueMax = roundUp(maxValue, 25.0d, DEFAULT_VALUE_MAX);
        xAxis.setUpperBound(roundedRpmMax);
        xAxis.setTickUnit(Math.max(defaultXStep, roundedRpmMax / 8.0d));
        valueAxis.setUpperBound(roundedValueMax);
        valueAxis.setTickUnit(Math.max(25.0d, roundedValueMax / 8.0d));
    }

    private double roundUp(double value, double step, double minimum) {
        double rounded = Math.ceil(Math.max(value, minimum) / step) * step;
        return Math.max(rounded, minimum);
    }

    private void applySeriesStyle() {
        for (Map.Entry<String, XYChart.Series<Number, Number>> entry : seriesById.entrySet()) {
            XYChart.Series<Number, Number> series = entry.getValue();
            if (series.getNode() == null) {
                continue;
            }
            ChartSeriesModel model = seriesModelById.get(entry.getKey());
            if (model == null) {
                continue;
            }
            series.getNode().setStyle(
                "-fx-stroke: " + model.getColorHex() + ";" +
                    "-fx-stroke-width: 2.4px;"
            );
        }
    }

    private void syncSeries(List<ChartSeriesModel> seriesModels) {
        ObservableList<XYChart.Series<Number, Number>> chartSeries = FXCollections.observableArrayList();
        seriesModelById.clear();
        for (int index = 0; index < seriesModels.size(); index++) {
            ChartSeriesModel model = seriesModels.get(index);
            XYChart.Series<Number, Number> series = seriesById.get(model.getId());
            if (series == null) {
                series = new XYChart.Series<Number, Number>();
                seriesById.put(model.getId(), series);
                enqueuedPointsBySeries.put(model.getId(), Integer.valueOf(0));
                pendingPointsBySeries.put(model.getId(), new ArrayList<XYChart.Data<Number, Number>>());
            }
            seriesModelById.put(model.getId(), model);
            series.setName(model.getLabel());
            chartSeries.add(series);
        }
        chart.setData(chartSeries);
    }

    private String buildSeriesSignature(List<ChartSeriesModel> seriesModels) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < seriesModels.size(); index++) {
            ChartSeriesModel model = seriesModels.get(index);
            if (index > 0) {
                builder.append('|');
            }
            builder.append(model.getId()).append(':').append(model.getLabel());
        }
        return builder.toString();
    }

    private double defaultXStepForLabel(String label) {
        if (label != null && label.startsWith("Time")) {
            return 1.0d;
        }
        if (label != null && label.startsWith("Speed")) {
            return 10.0d;
        }
        return 500.0d;
    }

    private double defaultXMaxForLabel(String label) {
        if (label != null && label.startsWith("Time")) {
            return 10.0d;
        }
        if (label != null && label.startsWith("Speed")) {
            return 200.0d;
        }
        return DEFAULT_RPM_MAX;
    }
}
