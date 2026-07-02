package com.dyno.fx;

import com.dyno.presenter.ChartCursorLocator;
import com.dyno.presenter.ChartPlotPoint;
import com.dyno.presenter.ChartScaleSettings;
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
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Line;
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
    private final Map<String, XYChart.Series<Number, Number>> overlaySeriesById =
        new LinkedHashMap<String, XYChart.Series<Number, Number>>();
    private final Map<String, ChartSeriesModel> overlaySeriesModelById =
        new LinkedHashMap<String, ChartSeriesModel>();
    private String seriesSignature = "";
    private String overlaySignature = "";
    private String currentXAxisLabel = "Engine RPM";

    // Cursor overlay (crosshair + per-series readout on hover)
    private final Pane cursorOverlay = new Pane();
    private final Line cursorLine = new Line();
    private final VBox cursorReadout = new VBox(2);
    private final javafx.scene.control.Label cursorXLabel = new javafx.scene.control.Label();
    private final List<javafx.scene.control.Label> cursorRows =
        new ArrayList<javafx.scene.control.Label>();
    private List<ChartSeriesModel> cursorSeries = new ArrayList<ChartSeriesModel>();

    private long datasetToken = Long.MIN_VALUE;
    private double maxRpm = DEFAULT_RPM_MAX;
    private double maxValue = DEFAULT_VALUE_MAX;
    private ChartScaleSettings scaleSettings = ChartScaleSettings.defaults();
    private String currentYAxisLabel = "Selected Metrics";

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
        installCursorOverlay();

        flushTimer = new Timeline(new KeyFrame(Duration.millis(120), event -> flushPendingPoints()));
        flushTimer.setCycleCount(Animation.INDEFINITE);
        flushTimer.play();
    }

    public void render(LiveDynoChartModel model) {
        if (model == null) {
            return;
        }

        String nextSignature = buildSeriesSignature(model.getSeries());
        String nextOverlaySignature = buildSeriesSignature(model.getOverlaySeries());
        boolean tokenChanged = model.getDatasetToken() != datasetToken;
        boolean liveChanged = tokenChanged || !nextSignature.equals(seriesSignature);
        boolean overlayChanged = !nextOverlaySignature.equals(overlaySignature);

        if (tokenChanged) {
            resetDataset(model.getDatasetToken());
        }

        if (liveChanged || overlayChanged) {
            seriesSignature = nextSignature;
            overlaySignature = nextOverlaySignature;
            syncAllSeries(model.getOverlaySeries(), model.getSeries());
        }

        // Cursor reads the freshest series every render (live points keep growing).
        List<ChartSeriesModel> forCursor =
            new ArrayList<ChartSeriesModel>(model.getSeries().size() + model.getOverlaySeries().size());
        forCursor.addAll(model.getSeries());
        forCursor.addAll(model.getOverlaySeries());
        cursorSeries = forCursor;

        currentXAxisLabel = model.getXAxisLabel();
        currentYAxisLabel = model.getYAxisLabel();
        scaleSettings = model.getScaleSettings();
        xAxis.setLabel(model.getXAxisLabel());
        valueAxis.setLabel(model.getYAxisLabel());

        for (int seriesIndex = 0; seriesIndex < model.getSeries().size(); seriesIndex++) {
            ChartSeriesModel seriesModel = model.getSeries().get(seriesIndex);
            int enqueued = enqueuedPointsBySeries.containsKey(seriesModel.getId())
                ? enqueuedPointsBySeries.get(seriesModel.getId()).intValue()
                : 0;
            List<ChartPlotPoint> points = seriesModel.getPoints();
            List<XYChart.Data<Number, Number>> pending = pendingPointsBySeries.get(seriesModel.getId());
            if (pending == null) continue;
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

        updateAxes();
    }

    private void resetDataset(long nextToken) {
        datasetToken = nextToken;
        seriesSignature = "";
        overlaySignature = "";
        chart.setData(FXCollections.observableArrayList());
        seriesById.clear();
        seriesModelById.clear();
        overlaySeriesById.clear();
        overlaySeriesModelById.clear();
        enqueuedPointsBySeries.clear();
        pendingPointsBySeries.clear();
        maxRpm = DEFAULT_RPM_MAX;
        maxValue = DEFAULT_VALUE_MAX;
        xAxis.setLowerBound(0);
        valueAxis.setLowerBound(0);
        valueAxis.setUpperBound(DEFAULT_VALUE_MAX);
        valueAxis.setTickUnit(50);
    }

    private void syncAllSeries(
        List<ChartSeriesModel> overlaySeries,
        List<ChartSeriesModel> liveSeries
    ) {
        overlaySeriesById.clear();
        overlaySeriesModelById.clear();

        ObservableList<XYChart.Series<Number, Number>> allSeries =
            FXCollections.observableArrayList();

        // Overlay series first — renders behind live data
        for (int i = 0; i < overlaySeries.size(); i++) {
            ChartSeriesModel model = overlaySeries.get(i);
            XYChart.Series<Number, Number> series = new XYChart.Series<Number, Number>();
            series.setName(model.getLabel());
            for (int pi = 0; pi < model.getPoints().size(); pi++) {
                ChartPlotPoint point = model.getPoints().get(pi);
                series.getData().add(new XYChart.Data<Number, Number>(
                    Double.valueOf(point.getX()), Double.valueOf(point.getY())));
                maxRpm = Math.max(maxRpm, point.getX());
                maxValue = Math.max(maxValue, point.getY());
            }
            overlaySeriesById.put(model.getId(), series);
            overlaySeriesModelById.put(model.getId(), model);
            allSeries.add(series);
        }

        // Live series — reuse existing objects to preserve accumulated points
        seriesModelById.clear();
        for (int i = 0; i < liveSeries.size(); i++) {
            ChartSeriesModel model = liveSeries.get(i);
            XYChart.Series<Number, Number> series = seriesById.get(model.getId());
            if (series == null) {
                series = new XYChart.Series<Number, Number>();
                seriesById.put(model.getId(), series);
                enqueuedPointsBySeries.put(model.getId(), Integer.valueOf(0));
                pendingPointsBySeries.put(model.getId(),
                    new ArrayList<XYChart.Data<Number, Number>>());
            }
            seriesModelById.put(model.getId(), model);
            series.setName(model.getLabel());
            allSeries.add(series);
        }

        seriesById.keySet().retainAll(seriesModelById.keySet());
        enqueuedPointsBySeries.keySet().retainAll(seriesModelById.keySet());
        pendingPointsBySeries.keySet().retainAll(seriesModelById.keySet());

        chart.setData(allSeries);
    }

    private void flushPendingPoints() {
        boolean flushed = false;

        if (!pendingPointsBySeries.isEmpty()) {
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
        }

        if (flushed) {
            updateAxes();
        }
        applySeriesStyle();
    }

    private void updateAxes() {
        ChartScaleSettings settings = scaleSettings == null ? ChartScaleSettings.defaults() : scaleSettings;
        String yScaleLabel = yScaleLabel();
        double roundedRpmMax = settings.xMaxForLabel(currentXAxisLabel, maxRpm);
        double roundedValueMax = settings.yMaxForLabel(yScaleLabel, maxValue);
        xAxis.setUpperBound(roundedRpmMax);
        xAxis.setTickUnit(settings.xIntervalForLabel(currentXAxisLabel));
        valueAxis.setUpperBound(roundedValueMax);
        valueAxis.setTickUnit(settings.yIntervalForLabel(yScaleLabel));
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
        for (Map.Entry<String, XYChart.Series<Number, Number>> entry : overlaySeriesById.entrySet()) {
            XYChart.Series<Number, Number> series = entry.getValue();
            if (series.getNode() == null) {
                continue;
            }
            ChartSeriesModel model = overlaySeriesModelById.get(entry.getKey());
            if (model == null) {
                continue;
            }
            series.getNode().setStyle(
                "-fx-stroke: " + model.getColorHex() + ";" +
                    "-fx-stroke-width: 1.6px;" +
                    "-fx-opacity: 0.6;"
            );
        }
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

    private String yScaleLabel() {
        if (currentYAxisLabel != null && !"Selected Metrics".equals(currentYAxisLabel)) {
            return currentYAxisLabel;
        }
        String detected = null;
        for (ChartSeriesModel model : seriesModelById.values()) {
            detected = commonMetric(detected, model.getLabel());
        }
        for (ChartSeriesModel model : overlaySeriesModelById.values()) {
            detected = commonMetric(detected, model.getLabel());
        }
        return detected == null ? currentYAxisLabel : detected;
    }

    private void installCursorOverlay() {
        cursorLine.setStroke(FxTheme.TEXT_SUBTLE);
        cursorLine.setStrokeWidth(1);
        cursorLine.getStrokeDashArray().addAll(4.0, 4.0);

        cursorXLabel.setTextFill(FxTheme.TEXT_PRIMARY);
        cursorXLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        cursorReadout.getChildren().add(cursorXLabel);
        cursorReadout.setPadding(new javafx.geometry.Insets(FxTheme.GAP_S));
        cursorReadout.setStyle(FxTheme.cardStyle(FxTheme.SURFACE_ALT));

        cursorOverlay.getChildren().addAll(cursorLine, cursorReadout);
        cursorOverlay.setMouseTransparent(true);
        cursorOverlay.setVisible(false);
        getChildren().add(cursorOverlay);

        chart.setOnMouseMoved(event -> updateCursor(event.getSceneX(), event.getSceneY()));
        chart.setOnMouseExited(event -> cursorOverlay.setVisible(false));
    }

    private void updateCursor(double sceneX, double sceneY) {
        javafx.scene.Node plot = chart.lookup(".chart-plot-background");
        if (plot == null || cursorSeries.isEmpty()) {
            cursorOverlay.setVisible(false);
            return;
        }
        javafx.geometry.Bounds plotScene = plot.localToScene(plot.getBoundsInLocal());
        if (sceneX < plotScene.getMinX() || sceneX > plotScene.getMaxX()
            || sceneY < plotScene.getMinY() || sceneY > plotScene.getMaxY()) {
            cursorOverlay.setVisible(false);
            return;
        }

        double axisLocalX = xAxis.sceneToLocal(sceneX, sceneY).getX();
        Number axisValue = xAxis.getValueForDisplay(axisLocalX);
        if (axisValue == null) {
            cursorOverlay.setVisible(false);
            return;
        }
        double x = Math.max(xAxis.getLowerBound(),
            Math.min(xAxis.getUpperBound(), axisValue.doubleValue()));

        List<ChartCursorLocator.Reading> readings = ChartCursorLocator.readingsAt(cursorSeries, x);
        if (readings.isEmpty()) {
            cursorOverlay.setVisible(false);
            return;
        }

        // Crosshair position in overlay coordinates.
        javafx.geometry.Point2D topLocal =
            cursorOverlay.sceneToLocal(sceneX, plotScene.getMinY());
        javafx.geometry.Point2D bottomLocal =
            cursorOverlay.sceneToLocal(sceneX, plotScene.getMaxY());
        cursorLine.setStartX(topLocal.getX());
        cursorLine.setStartY(topLocal.getY());
        cursorLine.setEndX(bottomLocal.getX());
        cursorLine.setEndY(bottomLocal.getY());

        // One reused label row per series.
        while (cursorRows.size() < readings.size()) {
            javafx.scene.control.Label row = new javafx.scene.control.Label();
            row.setStyle("-fx-font-size: 12px;");
            cursorRows.add(row);
            cursorReadout.getChildren().add(row);
        }
        while (cursorRows.size() > readings.size()) {
            javafx.scene.control.Label row = cursorRows.remove(cursorRows.size() - 1);
            cursorReadout.getChildren().remove(row);
        }
        cursorXLabel.setText(String.format(java.util.Locale.US, "%,.0f %s", x,
            currentXAxisLabel == null ? "" : currentXAxisLabel));
        for (int i = 0; i < readings.size(); i++) {
            ChartCursorLocator.Reading reading = readings.get(i);
            javafx.scene.control.Label row = cursorRows.get(i);
            row.setText(String.format(java.util.Locale.US, "%s  %,.1f", reading.getSeriesLabel(), reading.getY()));
            row.setTextFill(javafx.scene.paint.Color.web(reading.getColorHex()));
        }

        // Place readout right of the cursor; flip left near the right edge.
        cursorReadout.applyCss();
        cursorReadout.layout();
        double readoutWidth = Math.max(cursorReadout.getWidth(), cursorReadout.prefWidth(-1));
        double readoutX = topLocal.getX() + 12;
        double overlayRight = cursorOverlay.sceneToLocal(plotScene.getMaxX(), 0).getX();
        if (readoutX + readoutWidth > overlayRight) {
            readoutX = topLocal.getX() - readoutWidth - 12;
        }
        cursorReadout.setLayoutX(readoutX);
        cursorReadout.setLayoutY(topLocal.getY() + 12);
        cursorOverlay.setVisible(true);
    }

    private String commonMetric(String current, String nextLabel) {
        String next = null;
        if (nextLabel != null && nextLabel.contains("AFR")) next = "AFR";
        else if (nextLabel != null && nextLabel.contains("Speed")) next = "Speed";
        else if (nextLabel != null && nextLabel.contains("RPM")) next = "RPM";
        if (next == null) return current;
        if (current == null) return next;
        return current.equals(next) ? current : "Selected Metrics";
    }
}
