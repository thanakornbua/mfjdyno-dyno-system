package com.dyno.state;

import com.dyno.model.FrameMessage;
import com.dyno.model.RunPoint;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LiveTelemetryState {
    private final PropertyChangeSupport changes = new PropertyChangeSupport(this);
    private LiveTelemetrySnapshot snapshot = new LiveTelemetrySnapshot(
        ConnectionPhase.DISCONNECTED,
        "Disconnected",
        null,
        false,
        Collections.<RunPoint>emptyList()
    );

    public synchronized LiveTelemetrySnapshot getSnapshot() {
        return snapshot;
    }

    public void addListener(PropertyChangeListener listener) {
        changes.addPropertyChangeListener("snapshot", listener);
    }

    public void updateConnection(ConnectionPhase phase, String message) {
        LiveTelemetrySnapshot current = getSnapshot();
        publish(new LiveTelemetrySnapshot(
            phase,
            message,
            current.getFrame(),
            phase == ConnectionPhase.CONNECTED && current.isRecording(),
            current.getRunPoints()
        ));
    }

    public void updateFrame(FrameMessage frame) {
        LiveTelemetrySnapshot current = getSnapshot();
        boolean enteringRecording = !current.isRecording() && isRecordingState(frame.getState());
        boolean nextRecording = isRecordingState(frame.getState());
        List<RunPoint> nextRunPoints = current.getRunPoints();

        if (enteringRecording) {
            nextRunPoints = Collections.<RunPoint>emptyList();
        }

        if (nextRecording && shouldAppendChartPoint(frame, nextRunPoints)) {
            ArrayList<RunPoint> updated = new ArrayList<RunPoint>(nextRunPoints.size() + 1);
            updated.addAll(nextRunPoints);
            updated.add(new RunPoint(
                frame.getEngineRpm().doubleValue(),
                frame.getPowerHp().doubleValue(),
                frame.getTorqueNm().doubleValue()
            ));
            nextRunPoints = Collections.unmodifiableList(updated);
        }

        publish(new LiveTelemetrySnapshot(
            current.getConnectionPhase(),
            current.getConnectionMessage(),
            frame,
            nextRecording,
            nextRunPoints
        ));
    }

    private boolean isRecordingState(String state) {
        return state != null && "RECORDING".equals(state.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private boolean hasChartPoint(FrameMessage frame) {
        return frame.getEngineRpm() != null
            && frame.getPowerHp() != null
            && frame.getTorqueNm() != null;
    }

    private boolean shouldAppendChartPoint(FrameMessage frame, List<RunPoint> runPoints) {
        if (!hasChartPoint(frame)) {
            return false;
        }

        if (frame.getPowerHp().doubleValue() < 0.0 || frame.getTorqueNm().doubleValue() < 0.0) {
            return false;
        }

        if (runPoints.isEmpty()) {
            return true;
        }

        double lastEngineRpm = runPoints.get(runPoints.size() - 1).getEngineRpm();
        return frame.getEngineRpm().doubleValue() >= lastEngineRpm;
    }

    private synchronized void publish(LiveTelemetrySnapshot next) {
        LiveTelemetrySnapshot previous = this.snapshot;
        this.snapshot = next;
        changes.firePropertyChange("snapshot", previous, next);
    }
}
