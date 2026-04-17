package com.dyno.operator.config.viewmodel;

import com.dyno.operator.config.client.Esp32DaqConfigClient;
import com.dyno.operator.config.model.EngineEdgeMode;
import com.dyno.operator.config.model.Esp32DaqConfigDto;
import com.dyno.operator.config.model.Esp32DaqConfigResponseDto;
import com.dyno.operator.config.model.Esp32DaqConfigUpdateRequestDto;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ListProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleListProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;

public final class Esp32DaqConfigViewModel {
    private final Esp32DaqConfigClient client;
    private final Esp32DaqConfigValidator validator;

    private final StringProperty enginePulsePin = new SimpleStringProperty("");
    private final StringProperty enginePulsesPerRev = new SimpleStringProperty("");
    private final ObjectProperty<EngineEdgeMode> engineEdgeMode = new SimpleObjectProperty<>(EngineEdgeMode.RISING);
    private final StringProperty encoderPin = new SimpleStringProperty("");
    private final StringProperty encoderPpr = new SimpleStringProperty("");
    private final StringProperty canRxPin = new SimpleStringProperty("");
    private final StringProperty canTxPin = new SimpleStringProperty("");
    private final StringProperty canBitrate = new SimpleStringProperty("");
    private final StringProperty uartTxPin = new SimpleStringProperty("");
    private final StringProperty uartRxPin = new SimpleStringProperty("");
    private final StringProperty uartBaud = new SimpleStringProperty("");
    private final StringProperty telemetryRateHz = new SimpleStringProperty("");

    private final BooleanProperty loading = new SimpleBooleanProperty(false);
    private final BooleanProperty submitting = new SimpleBooleanProperty(false);
    private final ReadOnlyBooleanWrapper dirty = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyBooleanWrapper canSubmit = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyBooleanWrapper hasCurrentConfig = new ReadOnlyBooleanWrapper(false);
    private final ObjectProperty<Esp32DaqConfigDto> currentConfig = new SimpleObjectProperty<>();
    private final ObjectProperty<ApplyStatus> applyStatus = new SimpleObjectProperty<>(ApplyStatus.IDLE);
    private final StringProperty statusMessage = new SimpleStringProperty("No configuration loaded.");
    private final ListProperty<String> validationErrors =
        new SimpleListProperty<>(FXCollections.observableArrayList());
    private final ListProperty<String> validationWarnings =
        new SimpleListProperty<>(FXCollections.observableArrayList());

    private boolean suppressDirtyTracking;

    public Esp32DaqConfigViewModel(Esp32DaqConfigClient client) {
        this(client, new Esp32DaqConfigValidator());
    }

    public Esp32DaqConfigViewModel(Esp32DaqConfigClient client, Esp32DaqConfigValidator validator) {
        this.client = Objects.requireNonNull(client, "client");
        this.validator = Objects.requireNonNull(validator, "validator");
        currentConfig.addListener((obs, oldValue, newValue) -> hasCurrentConfig.set(newValue != null));
        attachDirtyTracking();
        refreshSubmitState();
    }

    public CompletableFuture<Void> loadCurrentConfig() {
        setBusyState(true, false, ApplyStatus.LOADING, "Loading ESP32 DAQ config...");
        return client.loadCurrentConfig()
            .thenAccept(response -> runOnFxThread(() -> applyLoadedResponse(response)))
            .exceptionally(error -> {
                runOnFxThread(() -> {
                    loading.set(false);
                    applyStatus.set(ApplyStatus.ERROR);
                    statusMessage.set(userFriendlyMessage("Failed to load ESP32 DAQ config", error));
                    validationErrors.setAll(List.of(statusMessage.get()));
                    refreshSubmitState();
                });
                return null;
            });
    }

    public Esp32DaqConfigValidator.ValidationResult validateBeforeSubmit() {
        Esp32DaqConfigValidator.ValidationResult result = validator.validateDraft(snapshotDraft());
        applyValidation(result);
        refreshSubmitState();
        return result;
    }

    public CompletableFuture<Boolean> submitChanges() {
        Esp32DaqConfigValidator.ValidationResult result = validateBeforeSubmit();
        if (!result.valid()) {
            applyStatus.set(ApplyStatus.VALIDATION_ERROR);
            statusMessage.set("ESP32 DAQ config has validation errors.");
            return CompletableFuture.completedFuture(false);
        }

        Esp32DaqConfigUpdateRequestDto request = result.parsedConfig()
            .orElseThrow(() -> new IllegalStateException("Validated config draft did not produce a request payload."));

        setBusyState(false, true, ApplyStatus.SUBMITTING, "Submitting ESP32 DAQ config...");
        return client.submitConfig(request)
            .thenApply(response -> {
                runOnFxThread(() -> applySubmittedResponse(response));
                return true;
            })
            .exceptionally(error -> {
                runOnFxThread(() -> {
                    submitting.set(false);
                    applyStatus.set(ApplyStatus.ERROR);
                    statusMessage.set(userFriendlyMessage("Failed to apply ESP32 DAQ config", error));
                    validationErrors.setAll(List.of(statusMessage.get()));
                    refreshSubmitState();
                });
                return false;
            });
    }

    public void populateDraft(Esp32DaqConfigDto config) {
        Objects.requireNonNull(config, "config");
        suppressDirtyTracking = true;
        try {
            enginePulsePin.set(Integer.toString(config.enginePulsePin()));
            enginePulsesPerRev.set(Double.toString(config.enginePulsesPerRev()));
            engineEdgeMode.set(config.engineEdgeMode());
            encoderPin.set(Integer.toString(config.encoderPin()));
            encoderPpr.set(Integer.toString(config.encoderPpr()));
            canRxPin.set(Integer.toString(config.canRxPin()));
            canTxPin.set(Integer.toString(config.canTxPin()));
            canBitrate.set(Integer.toString(config.canBitrate()));
            uartTxPin.set(Integer.toString(config.uartTxPin()));
            uartRxPin.set(Integer.toString(config.uartRxPin()));
            uartBaud.set(Integer.toString(config.uartBaud()));
            telemetryRateHz.set(Integer.toString(config.telemetryRateHz()));
        } finally {
            suppressDirtyTracking = false;
        }
        dirty.set(false);
        refreshSubmitState();
    }

    public void resetToCurrentConfig() {
        Esp32DaqConfigDto config = currentConfig.get();
        if (config == null) {
            validationErrors.setAll(List.of("No current device config is loaded."));
            applyStatus.set(ApplyStatus.ERROR);
            statusMessage.set("Cannot reset because no current config has been loaded.");
            refreshSubmitState();
            return;
        }

        populateDraft(config);
        validationErrors.clear();
        validationWarnings.clear();
        applyStatus.set(ApplyStatus.READY);
        statusMessage.set("Draft reset to the current device config.");
        refreshSubmitState();
    }

    public StringProperty enginePulsePinProperty() {
        return enginePulsePin;
    }

    public StringProperty enginePulsesPerRevProperty() {
        return enginePulsesPerRev;
    }

    public ObjectProperty<EngineEdgeMode> engineEdgeModeProperty() {
        return engineEdgeMode;
    }

    public StringProperty encoderPinProperty() {
        return encoderPin;
    }

    public StringProperty encoderPprProperty() {
        return encoderPpr;
    }

    public StringProperty canRxPinProperty() {
        return canRxPin;
    }

    public StringProperty canTxPinProperty() {
        return canTxPin;
    }

    public StringProperty canBitrateProperty() {
        return canBitrate;
    }

    public StringProperty uartTxPinProperty() {
        return uartTxPin;
    }

    public StringProperty uartRxPinProperty() {
        return uartRxPin;
    }

    public StringProperty uartBaudProperty() {
        return uartBaud;
    }

    public StringProperty telemetryRateHzProperty() {
        return telemetryRateHz;
    }

    public BooleanProperty loadingProperty() {
        return loading;
    }

    public BooleanProperty submittingProperty() {
        return submitting;
    }

    public ReadOnlyBooleanProperty dirtyProperty() {
        return dirty.getReadOnlyProperty();
    }

    public ReadOnlyBooleanProperty canSubmitProperty() {
        return canSubmit.getReadOnlyProperty();
    }

    public ReadOnlyBooleanProperty hasCurrentConfigProperty() {
        return hasCurrentConfig.getReadOnlyProperty();
    }

    public ObjectProperty<Esp32DaqConfigDto> currentConfigProperty() {
        return currentConfig;
    }

    public ObjectProperty<ApplyStatus> applyStatusProperty() {
        return applyStatus;
    }

    public StringProperty statusMessageProperty() {
        return statusMessage;
    }

    public ListProperty<String> validationErrorsProperty() {
        return validationErrors;
    }

    public ListProperty<String> validationWarningsProperty() {
        return validationWarnings;
    }

    public BooleanBinding busyProperty() {
        return loading.or(submitting);
    }

    private void attachDirtyTracking() {
        enginePulsePin.addListener((obs, oldValue, newValue) -> onDraftChanged());
        enginePulsesPerRev.addListener((obs, oldValue, newValue) -> onDraftChanged());
        engineEdgeMode.addListener((obs, oldValue, newValue) -> onDraftChanged());
        encoderPin.addListener((obs, oldValue, newValue) -> onDraftChanged());
        encoderPpr.addListener((obs, oldValue, newValue) -> onDraftChanged());
        canRxPin.addListener((obs, oldValue, newValue) -> onDraftChanged());
        canTxPin.addListener((obs, oldValue, newValue) -> onDraftChanged());
        canBitrate.addListener((obs, oldValue, newValue) -> onDraftChanged());
        uartTxPin.addListener((obs, oldValue, newValue) -> onDraftChanged());
        uartRxPin.addListener((obs, oldValue, newValue) -> onDraftChanged());
        uartBaud.addListener((obs, oldValue, newValue) -> onDraftChanged());
        telemetryRateHz.addListener((obs, oldValue, newValue) -> onDraftChanged());
    }

    private void onDraftChanged() {
        if (suppressDirtyTracking) {
            return;
        }
        dirty.set(true);
        applyStatus.set(ApplyStatus.EDITING);
        refreshSubmitState();
    }

    private void applyLoadedResponse(Esp32DaqConfigResponseDto response) {
        loading.set(false);
        currentConfig.set(response.config());
        populateDraft(response.config());
        dirty.set(false);
        applyStatus.set(ApplyStatus.READY);
        statusMessage.set(nonBlank(response.statusMessage(), "ESP32 DAQ config loaded."));
        applyBackendValidation(response);
        refreshSubmitState();
    }

    private void applySubmittedResponse(Esp32DaqConfigResponseDto response) {
        submitting.set(false);
        currentConfig.set(response.config());
        populateDraft(response.config());
        dirty.set(false);
        applyStatus.set(response.applied() ? ApplyStatus.APPLIED : ApplyStatus.READY);
        statusMessage.set(nonBlank(response.statusMessage(), "ESP32 DAQ config applied."));
        applyBackendValidation(response);
        refreshSubmitState();
    }

    private void applyBackendValidation(Esp32DaqConfigResponseDto response) {
        validationErrors.setAll(response.validation() == null ? List.of() : response.validation().errors());
        validationWarnings.setAll(response.validation() == null ? List.of() : response.validation().warnings());
    }

    private void applyValidation(Esp32DaqConfigValidator.ValidationResult result) {
        validationErrors.setAll(result.errors().stream().map(Esp32DaqConfigValidator.ValidationIssue::message).toList());
        validationWarnings.setAll(result.warnings().stream().map(Esp32DaqConfigValidator.ValidationIssue::message).toList());
    }

    private void refreshSubmitState() {
        boolean notBusy = !loading.get() && !submitting.get();
        Esp32DaqConfigValidator.ValidationResult currentValidation = validator.validateDraft(snapshotDraft());
        canSubmit.set(notBusy && currentValidation.valid());
    }

    private Esp32DaqConfigValidator.DraftInput snapshotDraft() {
        return new Esp32DaqConfigValidator.DraftInput(
            enginePulsePin.get(),
            enginePulsesPerRev.get(),
            engineEdgeMode.get(),
            encoderPin.get(),
            encoderPpr.get(),
            canRxPin.get(),
            canTxPin.get(),
            canBitrate.get(),
            uartTxPin.get(),
            uartRxPin.get(),
            uartBaud.get(),
            telemetryRateHz.get()
        );
    }

    private void setBusyState(boolean loadingValue, boolean submittingValue, ApplyStatus status, String message) {
        runOnFxThread(() -> {
            loading.set(loadingValue);
            submitting.set(submittingValue);
            applyStatus.set(status);
            statusMessage.set(message);
            refreshSubmitState();
        });
    }

    private static void runOnFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    private static String userFriendlyMessage(String prefix, Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String detail = root.getMessage();
        if (detail == null || detail.isBlank()) {
            return prefix + ".";
        }
        return prefix + ": " + detail;
    }

    private static String nonBlank(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate;
    }

    public enum ApplyStatus {
        IDLE,
        LOADING,
        READY,
        EDITING,
        VALIDATION_ERROR,
        SUBMITTING,
        APPLIED,
        ERROR
    }
}
