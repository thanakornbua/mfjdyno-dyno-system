package com.dyno.fx;

import com.dyno.calibration.CalibrationApiClient;
import com.dyno.calibration.CalibrationProfileDto;
import com.dyno.calibration.CalibrationResponseDto;
import com.dyno.control.RunConfigureRequest;
import com.dyno.control.RunControlApiClient;
import com.dyno.control.RunControlResponse;
import com.dyno.export.ExportFormat;
import com.dyno.export.ExportResult;
import com.dyno.export.ExportService;
import com.dyno.health.HealthApiClient;
import com.dyno.health.OperatorStatusMapper;
import com.dyno.health.OperatorStatusModel;
import com.dyno.history.CompareRunsResponseDto;
import com.dyno.history.HistoryApiClient;
import com.dyno.history.RunHistoryDetailDto;
import com.dyno.history.RunHistoryFrameDto;
import com.dyno.history.RunHistoryFrameSeriesDto;
import com.dyno.history.RunHistorySummaryDto;
import com.dyno.presenter.CompareDisplayMapper;
import com.dyno.presenter.CompareDisplayState;
import com.dyno.presenter.LiveDynoChartModel;
import com.dyno.presenter.LiveDynoChartPresenter;
import com.dyno.presenter.OperatorViewModel;
import com.dyno.presenter.RunConfiguration;
import com.dyno.presenter.TelemetryPresenter;
import com.dyno.state.LiveTelemetryState;
import com.dyno.ws.DynoWebSocketClient;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class OperatorConsoleStage {
    private final RunControlApiClient runControlApiClient = RunControlApiClient.fromEnvironment();
    private final HistoryApiClient historyApiClient = HistoryApiClient.fromEnvironment();
    private final CalibrationApiClient calibrationApiClient = CalibrationApiClient.fromEnvironment();
    private final HealthApiClient healthApiClient = HealthApiClient.fromEnvironment();
    private final RunControlUiState runControlState = new RunControlUiState();
    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dyno-ui-run-control");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService statusExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "dyno-ui-health");
            thread.setDaemon(true);
            return thread;
        }
    });

    private Stage stage;
    private DynoWebSocketClient webSocketClient;
    private LiveDynoChartPresenter chartPresenter;
    private OperatorViewModel latestTelemetryModel;
    private LiveDynoChartModel latestChartModel;
    private OperatorStatusModel latestOperatorStatus = OperatorStatusMapper.initial();
    private CompareDisplayState compareDisplayState;
    private CompareRunsResponseDto lastCompareResponse;
    private LiveRunShellView root;

    public void show(Stage stage) {
        this.stage = stage;
        UiLaunchConfig launchConfig = UiLaunchConfig.fromEnvironment();
        LiveTelemetryState telemetryState = new LiveTelemetryState();
        webSocketClient = new DynoWebSocketClient(telemetryState);
        TelemetryPresenter telemetryPresenter = new TelemetryPresenter(telemetryState);
        chartPresenter = new LiveDynoChartPresenter(telemetryState);

        root = new LiveRunShellView(new LiveRunShellView.ControlActions() {
            @Override
            public void onRunModeRequested() {
                handleRunModeRequested();
            }

            @Override
            public void onStartRequested() {
                handleStartRequested();
            }

            @Override
            public void onStopRequested() {
                submitControlRequest("Sending STOP request...", new ControlRequest() {
                    @Override
                    public RunControlResponse call() throws Exception {
                        return runControlApiClient.stop();
                    }
                }, null);
            }

            @Override
            public void onPrintRequested() {
                handleExportRequested();
            }

            @Override
            public void onCompareRequested() {
                handleCompareRequested();
            }

            @Override
            public void onCalibrationRequested() {
                handleCalibrationRequested();
            }
        });

        Scene scene = new Scene(root, 1440, 860);
        scene.setFill(FxTheme.APP_BACKGROUND);

        latestTelemetryModel = telemetryPresenter.getViewModel();
        latestChartModel = chartPresenter.getViewModel();
        root.render(
            latestTelemetryModel,
            latestOperatorStatus,
            runControlState,
            activeChartModel(),
            activeComparePrimary(),
            activeCompareSecondary()
        );
        root.applyLayoutForSize(scene.getWidth(), scene.getHeight());

        stage.setTitle("Dyno Operator Console");
        stage.setMinWidth(1024);
        stage.setMinHeight(680);
        stage.setScene(scene);

        scene.widthProperty().addListener((obs, oldValue, newValue) -> root.applyLayoutForSize(newValue.doubleValue(), scene.getHeight()));
        scene.heightProperty().addListener((obs, oldValue, newValue) -> root.applyLayoutForSize(scene.getWidth(), newValue.doubleValue()));

        installKeyboardShortcuts(stage, scene);
        installWindowChromeFullscreenBridge(stage, launchConfig);
        applyStartupMode(stage, launchConfig.startupMode());

        telemetryPresenter.addListener(event -> {
            if (event.getNewValue() instanceof OperatorViewModel) {
                OperatorViewModel model = (OperatorViewModel) event.getNewValue();
                Platform.runLater(() -> {
                    latestTelemetryModel = model;
                    renderRoot();
                });
            }
        });

        chartPresenter.addListener(event -> {
            if (event.getNewValue() instanceof LiveDynoChartModel) {
                LiveDynoChartModel model = (LiveDynoChartModel) event.getNewValue();
                Platform.runLater(() -> {
                    latestChartModel = model;
                    renderRoot();
                });
            }
        });

        stage.setOnCloseRequest(event -> {
            webSocketClient.stop();
            controlExecutor.shutdownNow();
            statusExecutor.shutdownNow();
        });

        stage.show();
        webSocketClient.start();
        startHealthPolling();
    }

    private void handleRunModeRequested() {
        Optional<RunConfiguration> configuration = RunConfigureDialog.show(
            stage,
            runControlState.preferredRunConfiguration()
        );
        if (!configuration.isPresent()) {
            return;
        }
        final RunConfiguration runConfiguration = configuration.get();
        submitControlRequest("Configuring run...", new ControlRequest() {
            @Override
            public RunControlResponse call() throws Exception {
                return runControlApiClient.configure(new RunConfigureRequest(
                    runConfiguration.getLicensePlate(),
                    null,
                    null
                ));
            }
        }, runConfiguration);
    }

    private void handleStartRequested() {
        Optional<RunConfiguration> configuration = RunConfigureDialog.show(
            stage,
            runControlState.preferredRunConfiguration()
        );
        if (!configuration.isPresent()) {
            return;
        }
        final RunConfiguration runConfiguration = configuration.get();
        submitControlRequest("Starting run...", new ControlRequest() {
            @Override
            public RunControlResponse call() throws Exception {
                RunControlResponse cfg = runControlApiClient.configure(
                    new RunConfigureRequest(runConfiguration.getLicensePlate(), null, null));
                if (!cfg.isSuccess()) {
                    return cfg;
                }
                return runControlApiClient.start();
            }
        }, runConfiguration);
    }

    private void submitControlRequest(
        String pendingMessage,
        ControlRequest request,
        RunConfiguration requestedConfiguration
    ) {
        runControlState.setBusy(pendingMessage);
        renderRoot();

        CompletableFuture
            .supplyAsync(() -> invokeRequest(request), controlExecutor)
            .thenAccept(response -> Platform.runLater(() -> {
                runControlState.clearBusy();
                if (response.getStatusCode() == 0) {
                    runControlState.applyNetworkError(response.getMessage());
                } else {
                    runControlState.applyResponse(response, requestedConfiguration);
                }
                chartPresenter.updateRunControl(
                    runControlState.isConfigured(),
                    runControlState.isStarted(),
                    runControlState.runLabel(),
                    runControlState.chartAxisSelection()
                );
                renderRoot();
            }));
    }

    private void renderRoot() {
        root.render(
            latestTelemetryModel,
            latestOperatorStatus,
            runControlState,
            activeChartModel(),
            activeComparePrimary(),
            activeCompareSecondary()
        );
    }

    private void startHealthPolling() {
        statusExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                OperatorStatusModel status = fetchOperatorStatus();
                Platform.runLater(() -> {
                    latestOperatorStatus = status;
                    renderRoot();
                });
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    private void handleCompareRequested() {
        runControlState.setBusy("Loading stored runs...");
        renderRoot();

        CompletableFuture
            .supplyAsync(() -> fetchStoredRuns(), controlExecutor)
            .thenAccept(result -> Platform.runLater(() -> {
                runControlState.clearBusy();
                if (result.error != null) {
                    runControlState.applyNetworkError(result.error);
                    renderRoot();
                    return;
                }

                CompareSelectView.Result selection = CompareSelectView.show(
                    stage,
                    historyApiClient,
                    result.runs,
                    compareDisplayState != null
                );
                if (selection == null) {
                    renderRoot();
                    return;
                }
                if (selection.isClearRequested()) {
                    compareDisplayState = null;
                    runControlState.showOperatorMessage(
                        "Returned to live chart mode.",
                        OperatorViewModel.Tone.NORMAL
                    );
                    renderRoot();
                    return;
                }

                List<Long> runIds = selection.getSelectedRunIds();
                if (runIds == null || runIds.isEmpty()) {
                    runControlState.showOperatorMessage(
                        "Select 1 to 4 stored runs to compare.",
                        OperatorViewModel.Tone.CAUTION
                    );
                    renderRoot();
                    return;
                }
                loadCompareData(runIds);
            }));
    }

    private void handleCalibrationRequested() {
        runControlState.setBusy(UiText.text("Loading calibration profiles..."));
        renderRoot();

        CompletableFuture
            .supplyAsync(() -> fetchCalibrationData(), controlExecutor)
            .thenAccept(result -> Platform.runLater(() -> {
                runControlState.clearBusy();
                if (result.error != null) {
                    runControlState.applyNetworkError(result.error);
                    renderRoot();
                    return;
                }

                CalibrationDialog.Result dialogResult = CalibrationDialog.show(
                    stage,
                    calibrationApiClient,
                    controlExecutor,
                    result.activeCalibration,
                    result.profiles
                );
                if (dialogResult != null && dialogResult.getStatusMessage() != null) {
                    runControlState.showOperatorMessage(
                        dialogResult.getStatusMessage(),
                        dialogResult.getTone()
                    );
                }
                renderRoot();
            }));
    }

    private void loadCompareData(List<Long> runIds) {
        runControlState.setBusy("Loading comparison data...");
        renderRoot();

        CompletableFuture
            .supplyAsync(() -> fetchCompareData(runIds), controlExecutor)
            .thenAccept(result -> Platform.runLater(() -> {
                runControlState.clearBusy();
                if (result.error != null) {
                    runControlState.applyNetworkError(result.error);
                    renderRoot();
                    return;
                }

                lastCompareResponse = result.response;
                compareDisplayState = CompareDisplayMapper.map(result.response);
                CompareDataView.show(stage, result.response);
                runControlState.showOperatorMessage(
                    "Loaded comparison for " + runIds.size() + " stored run" + (runIds.size() == 1 ? "" : "s") + ".",
                    OperatorViewModel.Tone.NORMAL
                );
                renderRoot();
            }));
    }

    private RunListResult fetchStoredRuns() {
        try {
            return RunListResult.success(historyApiClient.listRuns());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return RunListResult.failure("History request interrupted.");
        } catch (Exception error) {
            return RunListResult.failure("History request failed: " + rootMessage(error));
        }
    }

    private CalibrationResult fetchCalibrationData() {
        try {
            return CalibrationResult.success(
                calibrationApiClient.getActiveCalibration(),
                calibrationApiClient.listCalibrationProfiles()
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return CalibrationResult.failure("Calibration request interrupted.");
        } catch (Exception error) {
            return CalibrationResult.failure("Calibration request failed: " + rootMessage(error));
        }
    }

    private OperatorStatusModel fetchOperatorStatus() {
        try {
            return OperatorStatusMapper.fromHealth(healthApiClient.getStartupHealth());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return OperatorStatusMapper.unavailable();
        } catch (Exception error) {
            return OperatorStatusMapper.unavailable();
        }
    }

    private CompareResult fetchCompareData(List<Long> runIds) {
        try {
            return CompareResult.success(historyApiClient.compareRuns(runIds));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return CompareResult.failure("Compare request interrupted.");
        } catch (Exception error) {
            return CompareResult.failure("Compare request failed: " + rootMessage(error));
        }
    }

    private LiveDynoChartModel activeChartModel() {
        return compareDisplayState != null ? compareDisplayState.getChartModel() : latestChartModel;
    }

    private String activeComparePrimary() {
        return compareDisplayState == null ? null : compareDisplayState.getSummaryPrimary();
    }

    private String activeCompareSecondary() {
        return compareDisplayState == null ? null : compareDisplayState.getSummarySecondary();
    }

    private void handleExportRequested() {
        if (compareDisplayState != null && lastCompareResponse != null) {
            handleExportCompare();
        } else {
            handleExportSingleRun();
        }
    }

    private void handleExportCompare() {
        String context = "Compare: " + compareDisplayState.getSummaryPrimary();
        ExportDialog.Result config = ExportDialog.show(stage, context);
        if (config == null) {
            return;
        }
        WritableImage snapshot = null;
        if (config.getFormats().contains(ExportFormat.PNG) || config.getFormats().contains(ExportFormat.PDF)) {
            snapshot = root.captureChartSnapshot();
        }
        final WritableImage finalSnapshot = snapshot;
        final ExportDialog.Result finalConfig = config;
        final CompareRunsResponseDto response = lastCompareResponse;
        runControlState.setBusy(UiText.text("Exporting comparison..."));
        renderRoot();
        CompletableFuture.supplyAsync(
            () -> ExportService.exportCompare(response, finalSnapshot, finalConfig.getFormats(), finalConfig.getOutputDir()),
            controlExecutor
        ).thenAccept(result -> Platform.runLater(() -> {
            runControlState.clearBusy();
            notifyExportResult(result, finalConfig.getOutputDir());
            renderRoot();
        }));
    }

    private void handleExportSingleRun() {
        runControlState.setBusy(UiText.text("Loading stored runs for export..."));
        renderRoot();
        CompletableFuture.supplyAsync(() -> fetchStoredRuns(), controlExecutor)
            .thenAccept(listResult -> Platform.runLater(() -> {
                runControlState.clearBusy();
                if (listResult.error != null) {
                    runControlState.applyNetworkError(listResult.error);
                    renderRoot();
                    return;
                }
                RunHistorySummaryDto picked = ExportRunPickerView.show(stage, listResult.runs);
                if (picked == null || picked.getRunId() == null) {
                    renderRoot();
                    return;
                }
                long runId = picked.getRunId().longValue();
                ExportDialog.Result config = ExportDialog.show(stage, String.format("RUN-%05d", runId));
                if (config == null) {
                    renderRoot();
                    return;
                }
                WritableImage snapshot = null;
                if (config.getFormats().contains(ExportFormat.PNG) || config.getFormats().contains(ExportFormat.PDF)) {
                    snapshot = root.captureChartSnapshot();
                }
                final WritableImage finalSnapshot = snapshot;
                final ExportDialog.Result finalConfig = config;
                final long finalRunId = runId;
                runControlState.setBusy(UiText.text("Exporting run data..."));
                renderRoot();
                CompletableFuture.supplyAsync(() -> {
                    try {
                        RunHistoryDetailDto detail = historyApiClient.getRun(finalRunId);
                        RunHistoryFrameSeriesDto series = historyApiClient.getRunFrames(finalRunId);
                        List<RunHistoryFrameDto> frames = series != null && series.getFrames() != null
                            ? series.getFrames() : Collections.emptyList();
                        return ExportService.exportSingleRun(detail, frames, finalSnapshot,
                            finalConfig.getFormats(), finalConfig.getOutputDir());
                    } catch (Exception e) {
                        return new ExportResult(
                            Collections.<java.nio.file.Path>emptyList(),
                            java.util.Arrays.asList("Export failed: " + e.getMessage())
                        );
                    }
                }, controlExecutor)
                .thenAccept(result -> Platform.runLater(() -> {
                    runControlState.clearBusy();
                    notifyExportResult(result, finalConfig.getOutputDir());
                    renderRoot();
                }));
            }));
    }

    private void notifyExportResult(ExportResult result, java.nio.file.Path outputDir) {
        if (result.hasErrors()) {
            runControlState.showOperatorMessage(
                "Export errors: " + String.join("; ", result.getErrors()),
                OperatorViewModel.Tone.ALERT
            );
        } else {
            String dirName = outputDir != null ? outputDir.getFileName().toString() : "output";
            runControlState.showOperatorMessage(
                "Exported " + result.getExportedFiles().size() + " file(s) to " + dirName,
                OperatorViewModel.Tone.NORMAL
            );
        }
    }

    private RunControlResponse invokeRequest(ControlRequest request) {
        try {
            return request.call();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return RunControlResponse.failure(0, "Run control request interrupted.");
        } catch (Exception error) {
            return RunControlResponse.failure(0, "Run control request failed: " + rootMessage(error));
        }
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return current.getClass().getSimpleName();
        }
        return message;
    }

    private static void installKeyboardShortcuts(Stage stage, Scene scene) {
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.F11) {
                stage.setFullScreen(!stage.isFullScreen());
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE && stage.isFullScreen()) {
                stage.setFullScreen(false);
                event.consume();
            }
        });
    }

    private static void applyStartupMode(Stage stage, UiLaunchConfig.StartupMode mode) {
        stage.setFullScreenExitHint("");
        switch (mode) {
            case WINDOWED:
                stage.setMaximized(false);
                stage.setFullScreen(false);
                break;
            case FULLSCREEN:
                stage.setMaximized(false);
                stage.setFullScreen(true);
                break;
            case MAXIMIZED:
            default:
                stage.setFullScreen(false);
                stage.setMaximized(true);
                break;
        }
    }

    private static void installWindowChromeFullscreenBridge(Stage stage, UiLaunchConfig launchConfig) {
        if (!launchConfig.maximizeToFullscreen()) {
            return;
        }

        final boolean[] allowBridge = {false};
        final boolean[] internalTransition = {false};

        stage.maximizedProperty().addListener((obs, wasMaximized, isMaximized) -> {
            if (!allowBridge[0] || internalTransition[0] || !isMaximized || stage.isFullScreen()) {
                return;
            }
            internalTransition[0] = true;
            try {
                stage.setMaximized(false);
                stage.setFullScreen(true);
            } finally {
                internalTransition[0] = false;
            }
        });

        stage.setOnShown(event -> allowBridge[0] = true);
    }

    private interface ControlRequest {
        RunControlResponse call() throws Exception;
    }

    private static final class RunListResult {
        private final List<RunHistorySummaryDto> runs;
        private final String error;

        private RunListResult(List<RunHistorySummaryDto> runs, String error) {
            this.runs = runs;
            this.error = error;
        }

        private static RunListResult success(List<RunHistorySummaryDto> runs) {
            return new RunListResult(runs, null);
        }

        private static RunListResult failure(String error) {
            return new RunListResult(null, error);
        }
    }

    private static final class CalibrationResult {
        private final CalibrationResponseDto activeCalibration;
        private final List<CalibrationProfileDto> profiles;
        private final String error;

        private CalibrationResult(CalibrationResponseDto activeCalibration, List<CalibrationProfileDto> profiles, String error) {
            this.activeCalibration = activeCalibration;
            this.profiles = profiles;
            this.error = error;
        }

        private static CalibrationResult success(
            CalibrationResponseDto activeCalibration,
            List<CalibrationProfileDto> profiles
        ) {
            return new CalibrationResult(activeCalibration, profiles, null);
        }

        private static CalibrationResult failure(String error) {
            return new CalibrationResult(null, Collections.<CalibrationProfileDto>emptyList(), error);
        }
    }

    private static final class CompareResult {
        private final CompareRunsResponseDto response;
        private final String error;

        private CompareResult(CompareRunsResponseDto response, String error) {
            this.response = response;
            this.error = error;
        }

        private static CompareResult success(CompareRunsResponseDto response) {
            return new CompareResult(response, null);
        }

        private static CompareResult failure(String error) {
            return new CompareResult(null, error);
        }
    }
}
