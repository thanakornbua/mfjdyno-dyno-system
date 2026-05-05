package com.dyno.health;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class OperatorStatusModel {
    public enum OverallState {
        READY,
        DEGRADED,
        UNAVAILABLE
    }

    private final OverallState overallState;
    private final boolean backendReachable;
    private final String sourceMode;
    private final boolean storageReady;
    private final boolean serialDegraded;
    private final boolean ambientDegraded;
    private final String primaryMessage;
    private final String secondaryMessage;
    private final String warningSummary;
    private final List<String> warnings;

    public OperatorStatusModel(
        OverallState overallState,
        boolean backendReachable,
        String sourceMode,
        boolean storageReady,
        boolean serialDegraded,
        boolean ambientDegraded,
        String primaryMessage,
        String secondaryMessage,
        String warningSummary,
        List<String> warnings
    ) {
        this.overallState = overallState;
        this.backendReachable = backendReachable;
        this.sourceMode = sourceMode;
        this.storageReady = storageReady;
        this.serialDegraded = serialDegraded;
        this.ambientDegraded = ambientDegraded;
        this.primaryMessage = primaryMessage;
        this.secondaryMessage = secondaryMessage;
        this.warningSummary = warningSummary;
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
    }

    public OverallState getOverallState() {
        return overallState;
    }

    public boolean isBackendReachable() {
        return backendReachable;
    }

    public String getSourceMode() {
        return sourceMode;
    }

    public boolean isStorageReady() {
        return storageReady;
    }

    public boolean isSerialDegraded() {
        return serialDegraded;
    }

    public boolean isAmbientDegraded() {
        return ambientDegraded;
    }

    public String getPrimaryMessage() {
        return primaryMessage;
    }

    public String getSecondaryMessage() {
        return secondaryMessage;
    }

    public String getWarningSummary() {
        return warningSummary;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public String getOverallLabel() {
        return overallState.name();
    }

    public boolean isReady() {
        return overallState == OverallState.READY;
    }
}
