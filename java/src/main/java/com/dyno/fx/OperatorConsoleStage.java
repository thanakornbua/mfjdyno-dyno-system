package com.dyno.fx;

import com.dyno.calibration.CalibrationApiClient;
import com.dyno.calibration.CalibrationProfileDto;
import com.dyno.calibration.CalibrationResponseDto;
import com.dyno.control.RunConfigureRequest;
import com.dyno.control.RunControlApiClient;
import com.dyno.control.RunControlResponse;
import com.dyno.export.DynoPdfExporter;
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
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
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
        if (!root.isRunModeActive()) {
            root.setRunModeActive(true);
            runControlState.showOperatorMessage(
                "Run Mode enabled.",
                OperatorViewModel.Tone.NORMAL
            );
            renderRoot();
            return;
        }

        if ("RECORDING".equals(latestTelemetryModel.getStateText())) {
            runControlState.showOperatorMessage(
                "Cannot exit Run Mode while recording.",
                OperatorViewModel.Tone.CAUTION
            );
            renderRoot();
            return;
        }

        root.setRunModeActive(false);
        runControlState.showOperatorMessage(
            "Returned to dashboard mode.",
            OperatorViewModel.Tone.NORMAL
        );
        renderRoot();
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
                if (response.isSuccess() && response.isStarted()) {
                    root.setRunModeActive(true);
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

                List<RunHistorySummaryDto> completedRuns = completedRuns(result.runs);
                if (completedRuns.size() < 2) {
                    runControlState.showOperatorMessage(
                        "Need at least two completed runs to compare.",
                        OperatorViewModel.Tone.CAUTION
                    );
                    renderRoot();
                    return;
                }

                CompareSelectView.Result selection = CompareSelectView.show(
                    stage,
                    historyApiClient,
                    completedRuns,
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
                if (runIds == null || runIds.size() < 2) {
                    runControlState.showOperatorMessage(
                        "Need at least two completed runs to compare.",
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
        final CompareRunsResponseDto capturedCompare = lastCompareResponse;
        final LiveDynoChartModel capturedChart = latestChartModel;

        runControlState.setBusy("Preparing print export...");
        renderRoot();
        CompletableFuture
            .supplyAsync(() -> {
                if (capturedCompare != null) {
                    return printCompareExport(capturedCompare);
                } else if (capturedChart != null && capturedChart.hasPlottedData()) {
                    return printLiveSnapshot(capturedChart);
                } else {
                    return printLatestCompletedRun();
                }
            }, controlExecutor)
            .thenAccept(result -> Platform.runLater(() -> {
                runControlState.clearBusy();
                if (result.error != null) {
                    runControlState.showOperatorMessage(result.error, OperatorViewModel.Tone.ALERT);
                } else {
                    runControlState.showOperatorMessage(
                        "Exported report: " + result.outputFile.getFileName(),
                        OperatorViewModel.Tone.NORMAL
                    );
                }
                renderRoot();
            }));
    }

    private PrintResult printCompareExport(CompareRunsResponseDto compareResponse) {
        try {
            Path outputDir = Paths.get(System.getProperty("user.home"), "dyno_data", "exports");
            Files.createDirectories(outputDir);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path outputFile = outputDir.resolve("dyno-compare-" + timestamp + ".pdf");
            DynoPdfExporter.writeCompare(compareResponse, outputFile);
            return PrintResult.success(outputFile);
        } catch (Exception error) {
            return PrintResult.failure("Compare export failed: " + rootMessage(error));
        }
    }

    private PrintResult printLiveSnapshot(LiveDynoChartModel chartModel) {
        try {
            Path outputDir = Paths.get(System.getProperty("user.home"), "dyno_data", "exports");
            Files.createDirectories(outputDir);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path outputFile = outputDir.resolve("dyno-live-" + timestamp + ".pdf");
            DynoPdfExporter.writeLiveSnapshot(
                chartModel.getRunLabel(),
                chartModel.getChartCaption(),
                chartModel.getSeries(),
                outputFile
            );
            return PrintResult.success(outputFile);
        } catch (Exception error) {
            return PrintResult.failure("Live snapshot export failed: " + rootMessage(error));
        }
    }

    private PrintResult printLatestCompletedRun() {
        try {
            List<RunHistorySummaryDto> runs = historyApiClient.listRuns();
            List<RunHistorySummaryDto> completed = completedRuns(runs);
            if (completed.isEmpty()) {
                return PrintResult.failure("No live or completed run data available to print.");
            }

            RunHistorySummaryDto latestCompleted = completed.get(0);
            if (latestCompleted.getRunId() == null) {
                return PrintResult.failure("No live or completed run data available to print.");
            }

            long runId = latestCompleted.getRunId().longValue();
            RunHistoryDetailDto detail = historyApiClient.getRun(runId);
            RunHistoryFrameSeriesDto series = historyApiClient.getRunFrames(runId);
            List<RunHistoryFrameDto> frames = series != null && series.getFrames() != null
                ? series.getFrames() : Collections.emptyList();

            Path outputDir = Paths.get(System.getProperty("user.home"), "dyno_data", "exports");
            Files.createDirectories(outputDir);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path outputFile = outputDir.resolve("dyno-run-" + timestamp + ".pdf");
            DynoPdfExporter.writeSingleRun(detail, frames, outputFile);
            return PrintResult.success(outputFile);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return PrintResult.failure("Print export interrupted.");
        } catch (Exception error) {
            return PrintResult.failure("Print export failed: " + rootMessage(error));
        }
    }

    private List<RunHistorySummaryDto> completedRuns(List<RunHistorySummaryDto> runs) {
        if (runs == null || runs.isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<RunHistorySummaryDto> completed = new ArrayList<RunHistorySummaryDto>();
        for (int index = 0; index < runs.size(); index++) {
            RunHistorySummaryDto run = runs.get(index);
            if (run != null && run.getEndedAtMs() != null) {
                completed.add(run);
            }
        }
        return completed;
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

    private static final class PrintResult {
        private final Path outputFile;
        private final String error;

        private PrintResult(Path outputFile, String error) {
            this.outputFile = outputFile;
            this.error = error;
        }

        private static PrintResult success(Path outputFile) {
            return new PrintResult(outputFile, null);
        }

        private static PrintResult failure(String error) {
            return new PrintResult(null, error);
        }
    }
}
