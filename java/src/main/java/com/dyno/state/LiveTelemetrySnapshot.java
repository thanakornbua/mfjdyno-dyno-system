package com.dyno.state;

import com.dyno.model.FrameMessage;
import com.dyno.model.RunPoint;

import java.util.List;

public final class LiveTelemetrySnapshot {
    private final ConnectionPhase connectionPhase;
    private final String connectionMessage;
    private final FrameMessage frame;
    private final boolean recording;
    private final List<RunPoint> runPoints;

    public LiveTelemetrySnapshot(
        ConnectionPhase connectionPhase,
        String connectionMessage,
        FrameMessage frame,
        boolean recording,
        List<RunPoint> runPoints
    ) {
        this.connectionPhase = connectionPhase;
        this.connectionMessage = connectionMessage;
        this.frame = frame;
        this.recording = recording;
        this.runPoints = runPoints;
    }

    public ConnectionPhase getConnectionPhase() {
        return connectionPhase;
    }

    public String getConnectionMessage() {
        return connectionMessage;
    }

    public FrameMessage getFrame() {
        return frame;
    }

    public boolean isRecording() {
        return recording;
    }

    public List<RunPoint> getRunPoints() {
        return runPoints;
    }
}
