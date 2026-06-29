package com.dyno.presenter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LiveDynoChartModel {
    private final long datasetToken;
    private final String runLabel;
    private final String chartCaption;
    private final String axisSummaryText;
    private final String summaryText;
    private final String xAxisLabel;
    private final String yAxisLabel;
    private final List<ChartSeriesModel> series;
    private final String statusText;
    private final boolean collecting;
    private final int overlayRunCount;
    private final List<ChartSeriesModel> overlaySeries;
    private final ChartScaleSettings scaleSettings;

    public LiveDynoChartModel(
        long datasetToken,
        String runLabel,
        String chartCaption,
        String axisSummaryText,
        String summaryText,
        String xAxisLabel,
        String yAxisLabel,
        List<ChartSeriesModel> series,
        String statusText,
        boolean collecting
    ) {
        this(datasetToken, runLabel, chartCaption, axisSummaryText, summaryText,
            xAxisLabel, yAxisLabel, series, statusText, collecting, 0,
            Collections.<ChartSeriesModel>emptyList(), ChartScaleSettings.defaults());
    }

    public LiveDynoChartModel(
        long datasetToken,
        String runLabel,
        String chartCaption,
        String axisSummaryText,
        String summaryText,
        String xAxisLabel,
        String yAxisLabel,
        List<ChartSeriesModel> series,
        String statusText,
        boolean collecting,
        int overlayRunCount,
        List<ChartSeriesModel> overlaySeries
    ) {
        this(datasetToken, runLabel, chartCaption, axisSummaryText, summaryText,
            xAxisLabel, yAxisLabel, series, statusText, collecting, overlayRunCount,
            overlaySeries, ChartScaleSettings.defaults());
    }

    public LiveDynoChartModel(
        long datasetToken,
        String runLabel,
        String chartCaption,
        String axisSummaryText,
        String summaryText,
        String xAxisLabel,
        String yAxisLabel,
        List<ChartSeriesModel> series,
        String statusText,
        boolean collecting,
        int overlayRunCount,
        List<ChartSeriesModel> overlaySeries,
        ChartScaleSettings scaleSettings
    ) {
        this.datasetToken = datasetToken;
        this.runLabel = runLabel;
        this.chartCaption = chartCaption;
        this.axisSummaryText = axisSummaryText;
        this.summaryText = summaryText;
        this.xAxisLabel = xAxisLabel;
        this.yAxisLabel = yAxisLabel;
        this.series = Collections.unmodifiableList(new ArrayList<ChartSeriesModel>(series));
        this.statusText = statusText;
        this.collecting = collecting;
        this.overlayRunCount = overlayRunCount;
        this.overlaySeries = Collections.unmodifiableList(new ArrayList<ChartSeriesModel>(overlaySeries));
        this.scaleSettings = scaleSettings == null ? ChartScaleSettings.defaults() : scaleSettings;
    }

    public long getDatasetToken() {
        return datasetToken;
    }

    public String getRunLabel() {
        return runLabel;
    }

    public String getChartCaption() {
        return chartCaption;
    }

    public String getAxisSummaryText() {
        return axisSummaryText;
    }

    public String getSummaryText() {
        return summaryText;
    }

    public String getXAxisLabel() {
        return xAxisLabel;
    }

    public String getYAxisLabel() {
        return yAxisLabel;
    }

    public List<ChartSeriesModel> getSeries() {
        return series;
    }

    public String getStatusText() {
        return statusText;
    }

    public boolean isCollecting() {
        return collecting;
    }

    public int getOverlayRunCount() {
        return overlayRunCount;
    }

    public List<ChartSeriesModel> getOverlaySeries() {
        return overlaySeries;
    }

    public ChartScaleSettings getScaleSettings() {
        return scaleSettings;
    }

    public boolean hasPlottedData() {
        for (int index = 0; index < series.size(); index++) {
            if (!series.get(index).getPoints().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
