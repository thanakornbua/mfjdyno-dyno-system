package com.dyno.presenter;

public final class CompareDisplayState {
    private final LiveDynoChartModel chartModel;
    private final String summaryPrimary;
    private final String summarySecondary;

    public CompareDisplayState(
        LiveDynoChartModel chartModel,
        String summaryPrimary,
        String summarySecondary
    ) {
        this.chartModel = chartModel;
        this.summaryPrimary = summaryPrimary;
        this.summarySecondary = summarySecondary;
    }

    public LiveDynoChartModel getChartModel() {
        return chartModel;
    }

    public String getSummaryPrimary() {
        return summaryPrimary;
    }

    public String getSummarySecondary() {
        return summarySecondary;
    }
}
