package com.dyno.presenter;

import com.dyno.model.FrameMessage;
import com.dyno.model.RunPoint;
import com.dyno.state.ConnectionPhase;
import com.dyno.state.LiveTelemetrySnapshot;
import com.dyno.state.LiveTelemetryState;

import javax.swing.SwingUtilities;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TelemetryPresenter {
    private static final DecimalFormat ONE_DECIMAL = new DecimalFormat("0.0");
    private static final DecimalFormat TWO_DECIMALS = new DecimalFormat("0.00");
    private static final DecimalFormat WHOLE_NUMBER = new DecimalFormat("0");
    private static final double STOICH_AFR = 14.7d;

    private final LiveTelemetryState liveTelemetryState;
    private final PropertyChangeSupport changes = new PropertyChangeSupport(this);
    private final GaugeThresholdProfile lambdaProfile = GaugeThresholdProfile.lambdaProfile();
    private final GaugeThresholdProfile tempProfile = GaugeThresholdProfile.ambientTempProfile();
    private final RunIdentityState runIdentityState = new RunIdentityState();

    private OperatorViewModel viewModel;
    private String currentState = "IDLE";
    private boolean recording;
    private double peakPowerHp;
    private double peakTorqueNm;
    private List<RunPoint> chartPoints = Collections.emptyList();

    public TelemetryPresenter(LiveTelemetryState liveTelemetryState) {
        this.liveTelemetryState = liveTelemetryState;
        this.viewModel = buildViewModel(liveTelemetryState.getSnapshot());
        liveTelemetryState.addListener(event -> {
            LiveTelemetrySnapshot snapshot = (LiveTelemetrySnapshot) event.getNewValue();
            handleSnapshot(snapshot);
        });
    }

    public synchronized OperatorViewModel getViewModel() {
        return viewModel;
    }

    public void addListener(PropertyChangeListener listener) {
        changes.addPropertyChangeListener("viewModel", listener);
    }

    public synchronized RunIdentityState.PreparedRun previewRun(String plateInput) {
        return runIdentityState.preview(plateInput);
    }

    public synchronized RunIdentityState.PreparedRun prepareRun(String plateInput) {
        RunIdentityState.PreparedRun prepared = runIdentityState.prepare(plateInput);
        publish(buildViewModel(liveTelemetryState.getSnapshot()));
        return prepared;
    }

    public synchronized boolean hasPreparedRun() {
        return runIdentityState.hasPreparedRun();
    }

    public synchronized RunIdentityState.PreparedRun getPreparedRun() {
        return runIdentityState.getPreparedRun();
    }

    public synchronized String getLastUsedPlate() {
        return runIdentityState.getLastUsedPlate();
    }

    public synchronized RunIdentityState.PreparedRun commitPreparedRun() {
        RunIdentityState.PreparedRun committed = runIdentityState.commitPreparedRun();
        publish(buildViewModel(liveTelemetryState.getSnapshot()));
        return committed;
    }

    private void handleSnapshot(LiveTelemetrySnapshot snapshot) {
        synchronized (this) {
            String nextState = normalizeState(snapshot.getFrame());
            boolean nextRecording = "RECORDING".equals(nextState);
            boolean enteringRecording = !recording && nextRecording;

            if (enteringRecording) {
                chartPoints = Collections.emptyList();
                peakPowerHp = 0.0d;
                peakTorqueNm = 0.0d;
            }

            if (nextRecording) {
                appendChartPoint(snapshot.getFrame());
            }

            recording = nextRecording;
            currentState = nextState;
            publish(buildViewModel(snapshot));
        }
    }

    private void appendChartPoint(FrameMessage frame) {
        if (frame == null || frame.getEngineRpm() == null) {
            return;
        }

        Double selectedPower = pickPower(frame);
        Double selectedTorque = pickTorque(frame);
        if (selectedPower == null || selectedTorque == null) {
            return;
        }
        if (selectedPower.doubleValue() < 0.0d || selectedTorque.doubleValue() < 0.0d) {
            return;
        }

        double engineRpm = frame.getEngineRpm().doubleValue();
        if (!chartPoints.isEmpty()) {
            double lastEngineRpm = chartPoints.get(chartPoints.size() - 1).getEngineRpm();
            if (engineRpm < lastEngineRpm) {
                return;
            }
        }

        ArrayList<RunPoint> updated = new ArrayList<RunPoint>(chartPoints.size() + 1);
        updated.addAll(chartPoints);
        updated.add(new RunPoint(engineRpm, selectedPower.doubleValue(), selectedTorque.doubleValue()));
        chartPoints = Collections.unmodifiableList(updated);
        peakPowerHp = Math.max(peakPowerHp, selectedPower.doubleValue());
        peakTorqueNm = Math.max(peakTorqueNm, selectedTorque.doubleValue());
    }

    private OperatorViewModel buildViewModel(LiveTelemetrySnapshot snapshot) {
        FrameMessage frame = snapshot.getFrame();
        ConnectionPhase connectionPhase = snapshot.getConnectionPhase();
        String state = normalizeState(frame);
        Double powerHp = pickPower(frame);
        Double torqueNm = pickTorque(frame);
        Double afr = frame != null ? frame.getAfr() : null;
        Double lambda = frame != null && frame.getLambda() != null
            ? frame.getLambda()
            : (afr != null ? Double.valueOf(afr.doubleValue() / STOICH_AFR) : null);
        Double temp = frame != null ? frame.getTemp() : null;
        Double rpm = frame != null ? frame.getEngineRpm() : null;

        OperatorViewModel.GaugeModel lambdaGauge = buildLambdaGauge(lambda);
        OperatorViewModel.GaugeModel tempGauge = buildTempGauge(temp);
        OperatorViewModel.GaugeModel o2Gauge = new OperatorViewModel.GaugeModel(
            "O2",
            "—",
            "",
            "NOT CONFIGURED",
            OperatorViewModel.Tone.UNAVAILABLE
        );

        OperatorViewModel.MetricTileModel powerTile = new OperatorViewModel.MetricTileModel(
            "Power",
            formatNumber(powerHp, ONE_DECIMAL),
            "HP",
            "PEAK " + formatNumber(peakPowerHp > 0.0d ? Double.valueOf(peakPowerHp) : null, ONE_DECIMAL) + " HP",
            toneForPrimaryMetric(powerHp)
        );
        OperatorViewModel.MetricTileModel torqueTile = new OperatorViewModel.MetricTileModel(
            "Torque",
            formatNumber(torqueNm, ONE_DECIMAL),
            "Nm",
            "PEAK " + formatNumber(peakTorqueNm > 0.0d ? Double.valueOf(peakTorqueNm) : null, ONE_DECIMAL) + " Nm",
            toneForPrimaryMetric(torqueNm)
        );
        OperatorViewModel.MetricTileModel rpmTile = new OperatorViewModel.MetricTileModel(
            "RPM",
            formatNumber(rpm, WHOLE_NUMBER),
            "RPM",
            "",
            toneForPrimaryMetric(rpm)
        );
        OperatorViewModel.MetricTileModel afrTile = new OperatorViewModel.MetricTileModel(
            "AFR",
            formatNumber(afr, TWO_DECIMALS),
            "AFR",
            "LAMBDA " + formatNumber(lambda, TWO_DECIMALS),
            toneForGauge(lambdaGauge.getTone())
        );

        List<OperatorViewModel.SecondaryMetricModel> secondaryMetrics = new ArrayList<OperatorViewModel.SecondaryMetricModel>();
        secondaryMetrics.add(buildSecondaryMetric("Roller RPM", frame != null ? frame.getRpm() : null, "RPM", WHOLE_NUMBER));
        secondaryMetrics.add(buildSecondaryMetric("Speed", frame != null ? frame.getSpeedKmh() : null, "km/h", ONE_DECIMAL));
        secondaryMetrics.add(buildSecondaryMetric("Pressure", frame != null ? frame.getPressureHpa() : null, "hPa", ONE_DECIMAL));
        secondaryMetrics.add(buildSecondaryMetric("Humidity", frame != null ? frame.getHumidityPct() : null, "%", ONE_DECIMAL));
        secondaryMetrics.add(buildSecondaryMetric("SAE CF", frame != null ? frame.getSaeCf() : null, "", TWO_DECIMALS));
        secondaryMetrics.add(new OperatorViewModel.SecondaryMetricModel(
            "Fault Count",
            frame != null && frame.getFaultCount() != null ? Integer.toString(frame.getFaultCount().intValue()) : "—",
            "",
            "FAULT".equals(state) ? OperatorViewModel.Tone.FAULT : OperatorViewModel.Tone.NORMAL
        ));

        return new OperatorViewModel(
            connectionLabel(connectionPhase),
            state,
            runIdentityState.getDisplayRunLabel(),
            runIdentityState.getCurrentPlate(),
            currentChartCaption(frame),
            formatNumber(peakPowerHp > 0.0d ? Double.valueOf(peakPowerHp) : null, ONE_DECIMAL) + " HP",
            formatNumber(peakTorqueNm > 0.0d ? Double.valueOf(peakTorqueNm) : null, ONE_DECIMAL) + " Nm",
            recording,
            connectionPhase == ConnectionPhase.CONNECTED && "IDLE".equals(state),
            connectionPhase == ConnectionPhase.CONNECTED && ("ARMED".equals(state) || "RECORDING".equals(state)),
            !"RECORDING".equals(state),
            true,
            buildBanner(snapshot, state),
            lambdaGauge,
            tempGauge,
            o2Gauge,
            powerTile,
            torqueTile,
            rpmTile,
            afrTile,
            secondaryMetrics,
            chartPoints
        ).withDialValues(new OperatorViewModel.DialValues(
            rpm,
            frame != null ? frame.getSpeedKmh() : null,
            powerHp,
            torqueNm,
            afr
        ));
    }

    private OperatorViewModel.SecondaryMetricModel buildSecondaryMetric(
        String label,
        Double value,
        String unit,
        DecimalFormat format
    ) {
        return new OperatorViewModel.SecondaryMetricModel(
            label,
            formatNumber(value, format),
            unit,
            value != null ? OperatorViewModel.Tone.NORMAL : OperatorViewModel.Tone.UNAVAILABLE
        );
    }

    private OperatorViewModel.GaugeModel buildLambdaGauge(Double lambda) {
        if (lambda == null) {
            return unavailableGauge("AFR (lambda)");
        }
        GaugeThresholdProfile.Assessment assessment = lambdaProfile.evaluate(lambda.doubleValue());
        return new OperatorViewModel.GaugeModel(
            "AFR (lambda)",
            formatNumber(lambda, TWO_DECIMALS),
            "\u03bb",
            "",
            assessment.getTone()
        );
    }

    private OperatorViewModel.GaugeModel buildTempGauge(Double temp) {
        if (temp == null) {
            return unavailableGauge("Celsius");
        }
        GaugeThresholdProfile.Assessment assessment = tempProfile.evaluate(temp.doubleValue());
        return new OperatorViewModel.GaugeModel(
            "Celsius",
            formatNumber(temp, ONE_DECIMAL),
            "\u00b0C",
            "",
            assessment.getTone()
        );
    }

    private OperatorViewModel.GaugeModel unavailableGauge(String label) {
        return new OperatorViewModel.GaugeModel(label, "—", "", "UNAVAILABLE", OperatorViewModel.Tone.UNAVAILABLE);
    }

    private OperatorViewModel.BannerModel buildBanner(LiveTelemetrySnapshot snapshot, String state) {
        ConnectionPhase phase = snapshot.getConnectionPhase();
        String message = snapshot.getConnectionMessage();
        if ("FAULT".equals(state)) {
            return new OperatorViewModel.BannerModel("FAULT", "Sensor fault or invalid telemetry detected", OperatorViewModel.Tone.FAULT);
        }
        if (phase == ConnectionPhase.CONNECTED) {
            if ("RECORDING".equals(state)) {
                return new OperatorViewModel.BannerModel("LIVE TELEMETRY", "Live telemetry active (not saved)", OperatorViewModel.Tone.CAUTION);
            }
            if ("ARMED".equals(state)) {
                return new OperatorViewModel.BannerModel("ARMED", "Waiting for run thresholds", OperatorViewModel.Tone.CAUTION);
            }
            return new OperatorViewModel.BannerModel("CONNECTED", "Ready for operator input", OperatorViewModel.Tone.NORMAL);
        }
        if (phase == ConnectionPhase.CONNECTING || phase == ConnectionPhase.AUTHENTICATING) {
            return new OperatorViewModel.BannerModel("CONNECTING", safeText(message, "Connecting to dyno backend"), OperatorViewModel.Tone.CAUTION);
        }
        if (phase == ConnectionPhase.RECONNECT_WAIT) {
            return new OperatorViewModel.BannerModel("RECONNECTING", safeText(message, "Reconnecting to dyno backend"), OperatorViewModel.Tone.CAUTION);
        }
        return new OperatorViewModel.BannerModel("DISCONNECTED", safeText(message, "Disconnected from dyno backend"), OperatorViewModel.Tone.ALERT);
    }

    private String currentChartCaption(FrameMessage frame) {
        Double currentPower = pickPower(frame);
        Double currentTorque = pickTorque(frame);
        return "Peak " + formatNumber(peakPowerHp > 0.0d ? Double.valueOf(peakPowerHp) : currentPower, ONE_DECIMAL)
            + " HP / "
            + formatNumber(peakTorqueNm > 0.0d ? Double.valueOf(peakTorqueNm) : currentTorque, ONE_DECIMAL)
            + " Nm";
    }

    private Double pickPower(FrameMessage frame) {
        if (frame == null) {
            return null;
        }
        return frame.getPowerCorrHp() != null ? frame.getPowerCorrHp() : frame.getPowerHp();
    }

    private Double pickTorque(FrameMessage frame) {
        if (frame == null) {
            return null;
        }
        return frame.getTorqueCorrNm() != null ? frame.getTorqueCorrNm() : frame.getTorqueNm();
    }

    private OperatorViewModel.Tone toneForPrimaryMetric(Double value) {
        return value != null ? OperatorViewModel.Tone.ACCENT : OperatorViewModel.Tone.UNAVAILABLE;
    }

    private OperatorViewModel.Tone toneForGauge(OperatorViewModel.Tone tone) {
        return tone == null ? OperatorViewModel.Tone.UNAVAILABLE : tone;
    }

    private String normalizeState(FrameMessage frame) {
        if (frame == null || frame.getState() == null || frame.getState().trim().isEmpty()) {
            return currentState;
        }
        return frame.getState().trim().toUpperCase();
    }

    private String connectionLabel(ConnectionPhase phase) {
        if (phase == ConnectionPhase.CONNECTED) {
            return "CONNECTED";
        }
        if (phase == ConnectionPhase.CONNECTING || phase == ConnectionPhase.AUTHENTICATING) {
            return "CONNECTING";
        }
        if (phase == ConnectionPhase.RECONNECT_WAIT) {
            return "RECONNECTING";
        }
        return "DISCONNECTED";
    }

    private String formatNumber(Double value, DecimalFormat format) {
        if (value == null) {
            return "—";
        }
        return format.format(value.doubleValue());
    }

    private String safeText(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value;
    }

    private void publish(OperatorViewModel next) {
        OperatorViewModel previous = this.viewModel;
        this.viewModel = next;
        SwingUtilities.invokeLater(() -> changes.firePropertyChange("viewModel", previous, next));
    }
}
