package com.dyno.presenter;

import com.dyno.model.FrameMessage;
import com.dyno.state.ConnectionPhase;
import com.dyno.state.LiveTelemetrySnapshot;
import com.dyno.state.LiveTelemetryState;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class LiveDynoChartPresenter {
    private static final DecimalFormat ONE_DECIMAL = new DecimalFormat("0.0");

    private final PropertyChangeSupport changes = new PropertyChangeSupport(this);

    private LiveTelemetrySnapshot latestSnapshot;
    private LiveDynoChartModel viewModel;

    private boolean runConfigured;
    private boolean runStarted;
    private String runLabel = "RUN NOT CONFIGURED";
    private String datasetRunLabel = "RUN NOT CONFIGURED";
    private long datasetToken;
    private RunAxisSelection currentAxisSelection = RunAxisSelection.defaults();
    private RunAxisSelection datasetAxisSelection = RunAxisSelection.defaults();
    private boolean collectionOpen;
    private boolean recordingSeen;
    private boolean disconnectedDuringRun;
    private Double datasetTimeOriginTs;
    private Double lastPlottedX;
    private List<SeriesState> seriesStates = Collections.emptyList();

    public LiveDynoChartPresenter(LiveTelemetryState liveTelemetryState) {
        this.latestSnapshot = liveTelemetryState.getSnapshot();
        this.viewModel = buildViewModel();
        liveTelemetryState.addListener(event -> handleSnapshot((LiveTelemetrySnapshot) event.getNewValue()));
    }

    public synchronized LiveDynoChartModel getViewModel() {
        return viewModel;
    }

    public void addListener(PropertyChangeListener listener) {
        changes.addPropertyChangeListener("chartModel", listener);
    }

    public void updateRunControl(
        boolean configured,
        boolean started,
        String nextRunLabel,
        RunAxisSelection axisSelection
    ) {
        synchronized (this) {
            boolean nextValidStartedRun = configured && started;
            String normalizedRunLabel = normalizeRunLabel(configured, nextRunLabel);
            currentAxisSelection = axisSelection == null ? RunAxisSelection.defaults() : axisSelection;
            boolean newStartedRun = nextValidStartedRun
                && (!isValidStartedRun() || !Objects.equals(runLabel, normalizedRunLabel));

            runConfigured = configured;
            runStarted = started;
            runLabel = normalizedRunLabel;

            if (newStartedRun) {
                beginNewRun();
            } else if (!nextValidStartedRun) {
                collectionOpen = false;
            }

            applySnapshot(latestSnapshot);
            publish(buildViewModel());
        }
    }

    private void handleSnapshot(LiveTelemetrySnapshot snapshot) {
        synchronized (this) {
            latestSnapshot = snapshot;
            applySnapshot(snapshot);
            publish(buildViewModel());
        }
    }

    private void applySnapshot(LiveTelemetrySnapshot snapshot) {
        if (!isValidStartedRun()) {
            collectionOpen = false;
            disconnectedDuringRun = false;
            return;
        }

        ConnectionPhase phase = snapshot.getConnectionPhase();
        if (phase != ConnectionPhase.CONNECTED) {
            collectionOpen = false;
            disconnectedDuringRun = true;
            return;
        }

        if (!collectionOpen) {
            return;
        }

        FrameMessage frame = snapshot.getFrame();
        if (!isRecording(frame)) {
            if (recordingSeen) {
                collectionOpen = false;
            }
            return;
        }

        recordingSeen = true;
        disconnectedDuringRun = false;
        appendPoint(frame);
    }

    private void beginNewRun() {
        datasetToken += 1L;
        datasetRunLabel = runLabel;
        datasetAxisSelection = currentAxisSelection;
        collectionOpen = true;
        recordingSeen = false;
        disconnectedDuringRun = false;
        datasetTimeOriginTs = null;
        lastPlottedX = null;
        seriesStates = newSeriesState(datasetAxisSelection);
    }

    private void appendPoint(FrameMessage frame) {
        if (frame == null) {
            return;
        }

        if (datasetTimeOriginTs == null && frame.getTs() != null) {
            datasetTimeOriginTs = frame.getTs();
        }

        Double xValue = datasetAxisSelection.getXAxis().xValue(frame, datasetTimeOriginTs);
        if (xValue == null) {
            return;
        }

        if (lastPlottedX != null && xValue.doubleValue() < lastPlottedX.doubleValue()) {
            return;
        }

        boolean appended = false;
        for (int index = 0; index < seriesStates.size(); index++) {
            SeriesState state = seriesStates.get(index);
            Double yValue = state.axis.yValue(frame);
            if (!state.axis.shouldPlot(yValue)) {
                continue;
            }
            state.points.add(new ChartPlotPoint(xValue.doubleValue(), yValue.doubleValue()));
            state.maxValue = state.maxValue == null
                ? yValue
                : Double.valueOf(Math.max(state.maxValue.doubleValue(), yValue.doubleValue()));
            appended = true;
        }

        if (appended) {
            lastPlottedX = xValue;
        }
    }

    private LiveDynoChartModel buildViewModel() {
        RunAxisSelection displayedAxes = displayedAxisSelection();
        List<ChartSeriesModel> chartSeries = chartSeries(displayedAxes);
        return new LiveDynoChartModel(
            datasetToken,
            displayRunLabel(),
            displayedAxes.captionText(),
            displayedAxes.summaryText(),
            buildSummaryText(chartSeries),
            displayedAxes.getXAxis().axisLabel(),
            "Selected Metrics",
            chartSeries,
            statusText(),
            collectionOpen && isRecording(latestSnapshot.getFrame()) && latestSnapshot.getConnectionPhase() == ConnectionPhase.CONNECTED
        );
    }

    private String displayRunLabel() {
        if (datasetToken == 0L && runConfigured && !runStarted) {
            return runLabel;
        }
        return datasetRunLabel;
    }

    private String statusText() {
        if (!runConfigured) {
            return "Configure and start a run to draw the dyno chart.";
        }
        if (!runStarted) {
            return "Run configured. Start the run to begin chart collection.";
        }
        if (disconnectedDuringRun) {
            if (!hasPlottedPoints()) {
                return "Backend disconnected before valid chart data was captured.";
            }
            return "Backend disconnected. Current chart is frozen.";
        }
        if (collectionOpen && !recordingSeen) {
            return "Run started. Waiting for recording to begin.";
        }
        if (collectionOpen) {
            return "Collecting recording points.";
        }
        if (hasPlottedPoints()) {
            return "Run complete. Chart frozen until the next run starts.";
        }
        return "Run finished without any plottable dyno chart samples.";
    }

    private boolean isValidStartedRun() {
        return runConfigured && runStarted;
    }

    private boolean isRecording(FrameMessage frame) {
        return frame != null && frame.getState() != null && "RECORDING".equals(frame.getState().trim().toUpperCase());
    }

    private String normalizeRunLabel(boolean configured, String nextRunLabel) {
        if (!configured) {
            return "RUN NOT CONFIGURED";
        }
        if (nextRunLabel == null || nextRunLabel.trim().isEmpty()) {
            return runLabel;
        }
        return nextRunLabel.trim();
    }

    private RunAxisSelection displayedAxisSelection() {
        if (datasetToken == 0L && (!runStarted || !hasPlottedPoints())) {
            return currentAxisSelection;
        }
        return datasetAxisSelection;
    }

    private List<ChartSeriesModel> chartSeries(RunAxisSelection axes) {
        ArrayList<ChartSeriesModel> series = new ArrayList<ChartSeriesModel>(3);
        List<SeriesState> displayStates = datasetToken == 0L && !runStarted
            ? newSeriesState(axes)
            : seriesStates;

        for (int index = 0; index < displayStates.size(); index++) {
            SeriesState state = displayStates.get(index);
            series.add(new ChartSeriesModel(
                state.axis.name(),
                state.axis.legendLabel(),
                state.axis.getColorHex(),
                state.points
            ));
        }
        return Collections.unmodifiableList(series);
    }

    private List<SeriesState> newSeriesState(RunAxisSelection axes) {
        ArrayList<SeriesState> states = new ArrayList<SeriesState>(3);
        List<RunChartAxis> yAxes = axes.yAxes();
        for (int index = 0; index < yAxes.size(); index++) {
            states.add(new SeriesState(yAxes.get(index)));
        }
        return states;
    }

    private boolean hasPlottedPoints() {
        for (int index = 0; index < seriesStates.size(); index++) {
            if (!seriesStates.get(index).points.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private String buildSummaryText(List<ChartSeriesModel> chartSeries) {
        ArrayList<String> parts = new ArrayList<String>(chartSeries.size());
        for (int index = 0; index < seriesStates.size(); index++) {
            SeriesState state = seriesStates.get(index);
            if (state.maxValue == null) {
                continue;
            }
            parts.add(
                "Max " + state.axis.getLabel() + " "
                    + ONE_DECIMAL.format(state.maxValue.doubleValue())
                    + " " + state.axis.getUnit()
            );
        }
        if (parts.isEmpty()) {
            return chartSeries.isEmpty() ? "No chart axes selected." : displayedAxisSelection().summaryText();
        }
        return join(parts, "  •  ");
    }

    private String join(List<String> parts, String delimiter) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < parts.size(); index++) {
            if (index > 0) {
                builder.append(delimiter);
            }
            builder.append(parts.get(index));
        }
        return builder.toString();
    }

    private void publish(LiveDynoChartModel next) {
        LiveDynoChartModel previous = this.viewModel;
        this.viewModel = next;
        changes.firePropertyChange("chartModel", previous, next);
    }

    private static final class SeriesState {
        private final RunChartAxis axis;
        private final List<ChartPlotPoint> points = new ArrayList<ChartPlotPoint>();
        private Double maxValue;

        private SeriesState(RunChartAxis axis) {
            this.axis = axis;
        }
    }
}
