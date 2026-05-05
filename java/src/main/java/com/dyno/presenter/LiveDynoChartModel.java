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

    public boolean hasPlottedData() {
        for (int index = 0; index < series.size(); index++) {
            if (!series.get(index).getPoints().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
