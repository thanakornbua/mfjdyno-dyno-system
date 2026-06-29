package com.dyno.fx;

import com.dyno.control.RunControlResponse;
import com.dyno.presenter.OperatorViewModel;
import com.dyno.presenter.ChartScaleSettings;
import com.dyno.presenter.RunAxisSelection;
import com.dyno.presenter.RunConfiguration;

final class RunControlUiState {
    private boolean configured;
    private boolean started;
    private boolean busy;
    private String runLabel = "RUN NOT CONFIGURED";
    private String licensePlate = "—";
    private String statusMessage = "Enter license plate to configure a run.";
    private OperatorViewModel.Tone statusTone = OperatorViewModel.Tone.UNAVAILABLE;
    private RunConfiguration configuredRunConfiguration;
    private RunConfiguration draftRunConfiguration = RunConfiguration.defaults("");

    boolean isConfigured() {
        return configured;
    }

    boolean isStarted() {
        return started;
    }

    boolean isBusy() {
        return busy;
    }

    String runLabel() {
        return runLabel;
    }

    String licensePlate() {
        return licensePlate;
    }

    String statusMessage() {
        return statusMessage;
    }

    OperatorViewModel.Tone statusTone() {
        return statusTone;
    }

    RunConfiguration preferredRunConfiguration() {
        return configuredRunConfiguration != null ? configuredRunConfiguration : draftRunConfiguration;
    }

    RunAxisSelection chartAxisSelection() {
        return preferredRunConfiguration().getAxisSelection();
    }

    String axisSummaryText() {
        return preferredRunConfiguration().getAxisSelection().summaryText();
    }

    void updateScaleSettings(ChartScaleSettings scaleSettings) {
        RunConfiguration preferred = preferredRunConfiguration();
        RunConfiguration next = new RunConfiguration(
            preferred.getLicensePlate(),
            preferred.getAxisSelection(),
            scaleSettings
        );
        if (configuredRunConfiguration != null) {
            configuredRunConfiguration = next;
        }
        draftRunConfiguration = next;
    }

    void setBusy(String message) {
        busy = true;
        statusMessage = message;
        statusTone = OperatorViewModel.Tone.CAUTION;
    }

    void clearBusy() {
        busy = false;
    }

    void applyResponse(RunControlResponse response, RunConfiguration requestedConfiguration) {
        configured = response.isConfigured();
        started = response.isStarted();
        statusMessage = safeText(response.getMessage(), "Run control updated.");
        statusTone = response.isSuccess() ? OperatorViewModel.Tone.NORMAL : OperatorViewModel.Tone.ALERT;

        RunConfiguration base = requestedConfiguration != null
            ? requestedConfiguration
            : preferredRunConfiguration();

        if (configured) {
            runLabel = safeText(response.getRunLabel(), "RUN NOT CONFIGURED");
            licensePlate = safeText(response.getLicensePlate(), "—");
            configuredRunConfiguration = base.withLicensePlate(licensePlate);
            draftRunConfiguration = configuredRunConfiguration;
        } else {
            runLabel = "RUN NOT CONFIGURED";
            licensePlate = "—";
            configuredRunConfiguration = null;
            draftRunConfiguration = base;
        }
    }

    void applyNetworkError(String message) {
        statusMessage = safeText(message, "Run control request failed.");
        statusTone = OperatorViewModel.Tone.ALERT;
    }

    void showOperatorMessage(String message, OperatorViewModel.Tone tone) {
        statusMessage = safeText(message, "Operator message");
        statusTone = tone == null ? OperatorViewModel.Tone.NORMAL : tone;
    }

    String preferredPlateInput() {
        String preferredPlate = preferredRunConfiguration().getLicensePlate();
        if (preferredPlate != null && !preferredPlate.trim().isEmpty()) {
            return preferredPlate;
        }
        return "—".equals(licensePlate) ? "" : licensePlate;
    }

    private String safeText(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim();
    }
}
