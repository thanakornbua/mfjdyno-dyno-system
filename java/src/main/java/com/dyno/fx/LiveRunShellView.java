package com.dyno.fx;

import com.dyno.health.OperatorStatusModel;
import com.dyno.presenter.LiveDynoChartModel;
import com.dyno.presenter.OperatorViewModel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.List;

public final class LiveRunShellView extends BorderPane {
    public interface ControlActions {
        void onRunModeRequested();

        void onStartRequested();

        void onStopRequested();

        void onPrintRequested();

        void onCompareRequested();

        void onScaleRequested();

        void onCalibrationRequested();

        void onAuditLogRequested();

        void onOverlayRunsRequested();

        void onClearOverlaysRequested();
    }

    public enum LayoutTier {
        COMPACT,
        NORMAL,
        LARGE
    }

    private final HeaderBarView headerBar;
    private final HBox mainBody;
    private final VBox runModePane;
    private final VBox leftRail;
    private final HBox chartDomain;
    private final VBox chartArea;
    private final VBox chartSidebar;
    private final HBox bottomBar;
    private OperatorViewModel latestModel;
    private RunControlUiState latestControlState;
    private LiveDynoChartModel latestChartModel;
    private OperatorStatusModel latestOperatorStatus;
    private boolean runModeActive;

    private final LiveDynoChartView dynoChartView = new LiveDynoChartView();

    // Left rail — continuous telemetry only.
    private final MetricTileView rpmTile = new MetricTileView();
    private final MetricTileView powerTile = new MetricTileView();
    private final MetricTileView torqueTile = new MetricTileView();
    private final GaugeCardView o2Gauge = new GaugeCardView();
    private final GaugeCardView tempGauge = new GaugeCardView();
    private final SecondaryMetricView speedView = new SecondaryMetricView();
    private final SecondaryMetricView pressureView = new SecondaryMetricView();
    private final SecondaryMetricView humidityView = new SecondaryMetricView();
    private final SecondaryMetricView saeCfView = new SecondaryMetricView();
    private final SecondaryMetricView faultCountView = new SecondaryMetricView();

    private HBox powerTorqueRow;
    private HBox o2TempRow;
    private HBox speedPressureRow;
    private HBox humidityCorrectionRow;
    private final Label telemetryTitle = sectionTitle(UiText.text("CONTINUOUS TELEMETRY"));
    private final Label engineTitle = subSectionTitle(UiText.text("ENGINE"));
    private final Label fuelEnvTitle = subSectionTitle(UiText.text("FUEL / ENV"));
    private final Label chartContextTitle = sectionTitle(UiText.text("CHART CONTEXT"));

    // Chart region.
    private final Label chartTitle = new Label(UiText.text("LIVE DYNO CHART"));
    private final Label chartRunLabel = new Label(UiText.text("RUN NOT CONFIGURED"));
    private final Label chartCaption = new Label(UiText.text("Power (HP) and Torque (Nm) vs Engine RPM"));
    private final Label chartSummary = new Label(UiText.text("Configured axes will appear here."));
    private final Label chartNote = new Label(UiText.text("Configure and start a run to draw the dyno chart."));
    private final Label peakTitle = new Label(UiText.text("PEAK VALUES"));
    private final Label peakPowerLabel = new Label(UiText.text("Peak power: —"));
    private final Label peakTorqueLabel = new Label(UiText.text("Peak torque: —"));
    private final Label axisTitle = new Label(UiText.text("SELECTED AXES"));
    private final Label runConfigSummary = new Label(UiText.text("X: Engine RPM | Y1: Power | Y2: Torque | Y3: AFR"));
    private final javafx.scene.control.Button overlayPickerButton =
        new javafx.scene.control.Button(UiText.text("OVERLAY RUNS"));
    private final javafx.scene.control.Button clearOverlaysButton =
        new javafx.scene.control.Button(UiText.text("CLEAR OVERLAYS"));
    private final Label overlayIndicator = new Label();

    // Bottom summary band.
    private final Label currentRunTitle = new Label(UiText.text("CURRENT RUN"));
    private final Label currentRunPrimary = new Label(UiText.text("RUN NOT CONFIGURED"));
    private final Label currentRunSecondary = new Label(UiText.text("Plate: —"));
    private final Label compareSummaryTitle = new Label(UiText.text("COMPARE SUMMARY"));
    private final Label compareSummaryPrimary = new Label(UiText.text("No comparison loaded"));
    private final Label compareSummarySecondary = new Label(
        UiText.text("Previous-run comparison and timestamps will land here in a later step.")
    );
    private final Label operatorStatusTitle = new Label(UiText.text("OPERATOR STATUS"));
    private final Label operatorStatusPrimary = new Label(UiText.text("Enter license plate to configure a run."));
    private final Label operatorStatusSecondary = new Label(UiText.text("Connection: DISCONNECTED | State: IDLE"));

    private final Label runModeTitle = sectionTitle(UiText.text("RUN MODE"));
    private final DialGaugeView runModeRpmDial =
        new DialGaugeView("RPM", "x1000", 0, 8000, 1000, 340);
    private final DialGaugeView runModeSpeedDial =
        new DialGaugeView("SPEED", "km/h", 0, 240, 30, 260);
    private final DialGaugeView runModePowerDial =
        new DialGaugeView("POWER", "HP", 0, 400, 50, 260);
    private final DialGaugeView runModeTorqueDial =
        new DialGaugeView("TORQUE", "Nm", 0, 500, 50, 260);
    private final Label runModeRunId = runModeMetricValue("—");
    private final Label runModeStateBadge = new Label("—");
    private final Label runModeAfrValue = runModeMetricValue("—");

    public LiveRunShellView() {
        this(new ControlActions() {
            @Override
            public void onRunModeRequested() {
            }

            @Override
            public void onStartRequested() {
            }

            @Override
            public void onStopRequested() {
            }

            @Override
            public void onPrintRequested() {
            }

            @Override
            public void onCompareRequested() {
            }

            @Override
            public void onScaleRequested() {
            }

            @Override
            public void onCalibrationRequested() {
            }

            @Override
            public void onAuditLogRequested() {
            }

            @Override
            public void onOverlayRunsRequested() {
            }

            @Override
            public void onClearOverlaysRequested() {
            }
        });
    }

    public LiveRunShellView(ControlActions controlActions) {
        setStyle("-fx-background-color: " + FxTheme.toCss(FxTheme.APP_BACKGROUND) + ";");

        headerBar = new HeaderBarView(
            new Runnable() {
                @Override
                public void run() {
                    controlActions.onRunModeRequested();
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    controlActions.onStartRequested();
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    controlActions.onStopRequested();
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    controlActions.onPrintRequested();
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    controlActions.onCompareRequested();
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    controlActions.onScaleRequested();
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    controlActions.onCalibrationRequested();
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    controlActions.onAuditLogRequested();
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    toggleLanguage();
                }
            }
        );
        overlayPickerButton.setOnAction(e -> controlActions.onOverlayRunsRequested());
        clearOverlaysButton.setOnAction(e -> controlActions.onClearOverlaysRequested());
        overlayPickerButton.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
        clearOverlaysButton.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
        overlayIndicator.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #EAB308;");
        clearOverlaysButton.setVisible(false);
        overlayIndicator.setVisible(false);

        leftRail = buildLeftRail();
        chartArea = buildChartArea();
        chartSidebar = buildChartSidebar();
        chartDomain = buildChartDomain();
        mainBody = buildMainBody();
        runModePane = buildRunModePane();
        bottomBar = buildBottomBar();

        setTop(headerBar);
        runModeActive = false;
        applyRunModeLayout();

        applyOperatorStatusTone(OperatorViewModel.Tone.UNAVAILABLE);
        applyLayoutTier(LayoutTier.NORMAL);
    }

    public void render(
        OperatorViewModel model,
        OperatorStatusModel operatorStatus,
        RunControlUiState controlState,
        LiveDynoChartModel chartModel,
        String comparePrimaryText,
        String compareSecondaryText
    ) {
        latestModel = model;
        latestOperatorStatus = operatorStatus;
        latestControlState = controlState;
        latestChartModel = chartModel;

        String machineState = model.getStateText();
        OperatorViewModel.BannerModel mergedBanner = computeBanner(model, operatorStatus, controlState);
        String runBadgeText = computeRunBadgeText(controlState, machineState);
        OperatorViewModel.Tone runBadgeTone = computeRunBadgeTone(controlState, machineState);
        refreshStaticText();

        boolean runModeEnabled = !controlState.isBusy();
        boolean startEnabled = !controlState.isBusy() && !controlState.isStarted();
        boolean stopEnabled = !controlState.isBusy() && controlState.isStarted();
        boolean printEnabled = !controlState.isBusy();
        boolean compareEnabled = !controlState.isBusy();
        boolean calibrationEnabled = !controlState.isBusy();

        headerBar.render(
            model,
            operatorStatus,
            controlState.runLabel(),
            controlState.licensePlate(),
            runBadgeText,
            runBadgeTone,
            mergedBanner,
            runModeEnabled,
            startEnabled,
            stopEnabled,
            printEnabled,
            compareEnabled,
            calibrationEnabled
        );

        rpmTile.render(model.getRpmTile());
        powerTile.render(model.getPowerTile());
        torqueTile.render(model.getTorqueTile());
        o2Gauge.render(model.getO2Gauge());
        tempGauge.render(model.getTempGauge());

        // Secondary metrics by fixed index from TelemetryPresenter:
        // 0 = Roller RPM  (not displayed)
        // 1 = Speed
        // 2 = Pressure
        // 3 = Humidity
        // 4 = SAE CF
        // 5 = Fault Count
        List<OperatorViewModel.SecondaryMetricModel> sec = model.getSecondaryMetrics();
        renderSecondary(speedView, sec, 1);
        renderSecondary(pressureView, sec, 2);
        renderSecondary(humidityView, sec, 3);
        renderSecondary(saeCfView, sec, 4);
        renderSecondary(faultCountView, sec, 5);

        OperatorViewModel.DialValues dials = model.getDialValues();
        runModeRpmDial.update(dials != null ? dials.getEngineRpm() : null);
        runModeSpeedDial.update(dials != null ? dials.getSpeedKmh() : null);
        runModePowerDial.update(dials != null ? dials.getPowerHp() : null);
        runModeTorqueDial.update(dials != null ? dials.getTorqueNm() : null);
        runModeAfrValue.setText(UiText.text("AFR ")
            + (dials != null && dials.getAfr() != null
                ? String.format(java.util.Locale.US, "%.2f", dials.getAfr().doubleValue()) : "—"));
        runModeRunId.setText(controlState.runLabel());
        renderRunStateBadge(machineState, controlState);

        chartRunLabel.setText(UiText.text(chartModel.getRunLabel()));
        int overlayCount = chartModel.getOverlayRunCount();
        if (overlayCount > 0) {
            overlayIndicator.setText(overlayCount + " " + UiText.text("runs overlaid"));
            overlayIndicator.setVisible(true);
            clearOverlaysButton.setVisible(true);
        } else {
            overlayIndicator.setVisible(false);
            clearOverlaysButton.setVisible(false);
        }
        chartCaption.setText(UiText.text(chartModel.getChartCaption()));
        chartSummary.setText(UiText.text(chartModel.getSummaryText()));
        chartNote.setText(UiText.text(chartModel.getStatusText()));
        runConfigSummary.setText(UiText.text(chartModel.getAxisSummaryText()));
        peakPowerLabel.setText(UiText.text("Peak power: ") + model.getPeakPowerText());
        peakTorqueLabel.setText(UiText.text("Peak torque: ") + model.getPeakTorqueText());
        dynoChartView.render(chartModel);

        currentRunPrimary.setText(UiText.text(controlState.runLabel()));
        currentRunSecondary.setText(
            UiText.text("Plate: ") + controlState.licensePlate() + " | " + UiText.text(chartModel.getSummaryText())
        );
        compareSummaryPrimary.setText(UiText.text(safeText(comparePrimaryText, "No comparison loaded")));
        compareSummarySecondary.setText(UiText.text(safeText(
            compareSecondaryText,
            "Previous-run comparison and timestamps will attach beneath " + chartModel.getRunLabel() + "."
        )));
        renderOperatorStatus(model, operatorStatus, controlState, machineState);
    }

    private void renderSecondary(
        SecondaryMetricView view,
        List<OperatorViewModel.SecondaryMetricModel> list,
        int index
    ) {
        if (index < list.size()) {
            view.render(list.get(index));
        } else {
            view.render(new OperatorViewModel.SecondaryMetricModel("—", "—", "", OperatorViewModel.Tone.UNAVAILABLE));
        }
    }

    private OperatorViewModel.BannerModel computeBanner(
        OperatorViewModel model,
        OperatorStatusModel operatorStatus,
        RunControlUiState controlState
    ) {
        return resolveBanner(model, operatorStatus, controlState);
    }

    public static OperatorViewModel.BannerModel resolveBanner(
        OperatorViewModel model,
        OperatorStatusModel operatorStatus,
        RunControlUiState controlState
    ) {
        String machineState = model.getStateText();
        String connectionText = model.getConnectionText();

        if ("FAULT".equals(machineState)) {
            return model.getBanner();
        }
        if (!"CONNECTED".equals(connectionText)) {
            return model.getBanner();
        }

        if (operatorStatus != null && operatorStatus.getOverallState() == OperatorStatusModel.OverallState.UNAVAILABLE) {
            return new OperatorViewModel.BannerModel(
                "BACKEND UNAVAILABLE",
                operatorStatus.getSecondaryMessage(),
                OperatorViewModel.Tone.ALERT
            );
        }
        if (
            operatorStatus != null
                && operatorStatus.getOverallState() == OperatorStatusModel.OverallState.DEGRADED
                && !"FAULT".equals(machineState)
        ) {
            return new OperatorViewModel.BannerModel(
                "BACKEND DEGRADED",
                operatorStatus.getPrimaryMessage(),
                OperatorViewModel.Tone.CAUTION
            );
        }

        if (controlState.statusTone() == OperatorViewModel.Tone.ALERT) {
            return new OperatorViewModel.BannerModel(
                "OPERATOR MESSAGE",
                controlState.statusMessage(),
                OperatorViewModel.Tone.ALERT
            );
        }
        if (!controlState.isConfigured()) {
            if ("RECORDING".equals(machineState)) {
                return new OperatorViewModel.BannerModel(
                    "LIVE TELEMETRY",
                    "Live telemetry active (not saved)",
                    OperatorViewModel.Tone.CAUTION
                );
            }
            return new OperatorViewModel.BannerModel(
                "RUN NOT CONFIGURED",
                "Enter license plate to configure a run",
                OperatorViewModel.Tone.UNAVAILABLE
            );
        }
        if (!controlState.isStarted()) {
            return new OperatorViewModel.BannerModel(
                "RUN READY",
                "Ready to start run",
                OperatorViewModel.Tone.NORMAL
            );
        }
        if ("RECORDING".equals(machineState)) {
            return new OperatorViewModel.BannerModel(
                "RECORDING ACTIVE",
                "Recording active — " + controlState.runLabel(),
                OperatorViewModel.Tone.ACCENT
            );
        }
        if ("ARMED".equals(machineState)) {
            return new OperatorViewModel.BannerModel(
                "PAUSED",
                "Paused below recording threshold",
                OperatorViewModel.Tone.CAUTION
            );
        }
        return new OperatorViewModel.BannerModel(
            "RUN ACTIVE",
            "Run started — waiting for movement",
            OperatorViewModel.Tone.NORMAL
        );
    }

    private String computeRunBadgeText(RunControlUiState controlState, String machineState) {
        if (!controlState.isConfigured()) {
            return "RUN NOT CONFIGURED";
        }
        if (!controlState.isStarted()) {
            return "RUN READY";
        }
        if ("RECORDING".equals(machineState)) {
            return "RUN ACTIVE";
        }
        if ("ARMED".equals(machineState)) {
            return "PAUSED (below threshold)";
        }
        return "RUN STARTED";
    }

    private OperatorViewModel.Tone computeRunBadgeTone(RunControlUiState controlState, String machineState) {
        if (!controlState.isConfigured()) {
            return OperatorViewModel.Tone.UNAVAILABLE;
        }
        if (!controlState.isStarted()) {
            return OperatorViewModel.Tone.NORMAL;
        }
        if ("RECORDING".equals(machineState)) {
            return OperatorViewModel.Tone.ACCENT;
        }
        if ("ARMED".equals(machineState)) {
            return OperatorViewModel.Tone.CAUTION;
        }
        return OperatorViewModel.Tone.NORMAL;
    }

    /**
     * Takes a snapshot of the current chart view. Must be called on the FX Application Thread.
     * The returned WritableImage is safe to read from any thread once returned.
     */
    public WritableImage captureChartSnapshot() {
        return dynoChartView.snapshot(null, null);
    }

    public boolean isRunModeActive() {
        return runModeActive;
    }

    public void setRunModeActive(boolean active) {
        if (runModeActive == active) {
            return;
        }
        runModeActive = active;
        applyRunModeLayout();
    }

    private void applyRunModeLayout() {
        if (runModeActive) {
            setCenter(runModePane);
            setBottom(null);
        } else {
            setCenter(mainBody);
            setBottom(bottomBar);
        }
    }

    public void applyLayoutForSize(double width, double height) {
        applyLayoutTier(tierFor(width, height));
    }

    private void toggleLanguage() {
        UiText.toggleLanguage();
        if (latestModel != null && latestControlState != null && latestChartModel != null && latestOperatorStatus != null) {
            render(
                latestModel,
                latestOperatorStatus,
                latestControlState,
                latestChartModel,
                compareSummaryPrimary.getText(),
                compareSummarySecondary.getText()
            );
        }
    }

    private void renderOperatorStatus(
        OperatorViewModel model,
        OperatorStatusModel operatorStatus,
        RunControlUiState controlState,
        String machineState
    ) {
        String primaryText = controlState.statusMessage();
        OperatorViewModel.Tone primaryTone = controlState.statusTone();

        if (operatorStatus != null && !operatorStatus.isReady() && !controlState.isBusy()) {
            primaryText = operatorStatus.getPrimaryMessage();
            primaryTone = toneForOperatorStatus(operatorStatus);
        }

        operatorStatusPrimary.setText(UiText.text(primaryText));
        operatorStatusSecondary.setText(UiText.text(composeOperatorSecondary(model, operatorStatus, machineState)));
        applyOperatorStatusTone(primaryTone);
    }

    private String safeText(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value;
    }

    private String composeOperatorSecondary(
        OperatorViewModel model,
        OperatorStatusModel operatorStatus,
        String machineState
    ) {
        StringBuilder builder = new StringBuilder();
        if (operatorStatus != null && operatorStatus.getSecondaryMessage() != null && !operatorStatus.getSecondaryMessage().trim().isEmpty()) {
            builder.append(operatorStatus.getSecondaryMessage().trim());
        }
        appendStatusPart(builder, "Connection: " + model.getConnectionText());
        appendStatusPart(builder, "State: " + machineState);
        return builder.toString();
    }

    private void appendStatusPart(StringBuilder builder, String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(" | ");
        }
        builder.append(text.trim());
    }

    private OperatorViewModel.Tone toneForOperatorStatus(OperatorStatusModel operatorStatus) {
        switch (operatorStatus.getOverallState()) {
            case READY:
                return OperatorViewModel.Tone.NORMAL;
            case DEGRADED:
                return OperatorViewModel.Tone.CAUTION;
            case UNAVAILABLE:
            default:
                return OperatorViewModel.Tone.ALERT;
        }
    }

    private void refreshStaticText() {
        telemetryTitle.setText(UiText.text("CONTINUOUS TELEMETRY"));
        runModeTitle.setText(UiText.text("RUN MODE"));
        engineTitle.setText(UiText.text("ENGINE"));
        fuelEnvTitle.setText(UiText.text("FUEL / ENV"));
        chartTitle.setText(UiText.text("LIVE DYNO CHART"));
        chartContextTitle.setText(UiText.text("CHART CONTEXT"));
        peakTitle.setText(UiText.text("PEAK VALUES"));
        axisTitle.setText(UiText.text("SELECTED AXES"));
        currentRunTitle.setText(UiText.text("CURRENT RUN"));
        compareSummaryTitle.setText(UiText.text("COMPARE SUMMARY"));
        operatorStatusTitle.setText(UiText.text("OPERATOR STATUS"));
    }

    private void applyLayoutTier(LayoutTier tier) {
        headerBar.applyLayoutTier(tier);
        switch (tier) {
            case COMPACT: {
                double gap = FxTheme.GAP_S;
                mainBody.setSpacing(gap);
                mainBody.setPadding(new Insets(FxTheme.GAP_S));
                chartDomain.setPadding(new Insets(FxTheme.GAP_S));
                leftRail.setMinWidth(300);
                leftRail.setPrefWidth(330);
                leftRail.setMaxWidth(370);
                chartSidebar.setMinWidth(200);
                chartSidebar.setPrefWidth(220);
                chartSidebar.setMaxWidth(250);
                chartDomain.setSpacing(gap);
                leftRail.setSpacing(gap);
                chartSidebar.setSpacing(gap);
                applyRowGap(gap);
                chartArea.setMinHeight(340);
                chartRunLabel.setFont(Font.font("Monospaced", FontWeight.BOLD, 25));
                chartSummary.setFont(Font.font("SansSerif", FontWeight.BOLD, 14));
                rpmTile.applySizing(44);
                powerTile.applySizing(38);
                torqueTile.applySizing(38);
                o2Gauge.applySizing(38);
                tempGauge.applySizing(38);
                speedView.applySizing(30);
                pressureView.applySizing(30);
                humidityView.applySizing(30);
                saeCfView.applySizing(30);
                faultCountView.applySizing(30);
                bottomBar.setSpacing(gap);
                bottomBar.setPadding(new Insets(FxTheme.GAP_XS, FxTheme.GAP_S, FxTheme.GAP_S, FxTheme.GAP_S));
                break;
            }
            case LARGE: {
                double gap = FxTheme.GAP_L;
                mainBody.setSpacing(gap);
                mainBody.setPadding(new Insets(FxTheme.GAP_L));
                chartDomain.setPadding(new Insets(FxTheme.GAP_L));
                leftRail.setMinWidth(340);
                leftRail.setPrefWidth(380);
                leftRail.setMaxWidth(460);
                chartSidebar.setMinWidth(280);
                chartSidebar.setPrefWidth(315);
                chartSidebar.setMaxWidth(360);
                chartDomain.setSpacing(gap);
                leftRail.setSpacing(gap);
                chartSidebar.setSpacing(gap);
                applyRowGap(gap);
                chartArea.setMinHeight(600);
                chartRunLabel.setFont(Font.font("Monospaced", FontWeight.BOLD, 34));
                chartSummary.setFont(Font.font("SansSerif", FontWeight.BOLD, 15));
                rpmTile.applySizing(38);
                powerTile.applySizing(35);
                torqueTile.applySizing(35);
                o2Gauge.applySizing(38);
                tempGauge.applySizing(38);
                speedView.applySizing(28);
                pressureView.applySizing(28);
                humidityView.applySizing(28);
                saeCfView.applySizing(28);
                faultCountView.applySizing(28);
                bottomBar.setSpacing(gap);
                bottomBar.setPadding(new Insets(FxTheme.GAP_M, FxTheme.GAP_L, FxTheme.GAP_L, FxTheme.GAP_L));
                break;
            }
            case NORMAL:
            default: {
                double gap = FxTheme.GAP_M;
                mainBody.setSpacing(gap);
                mainBody.setPadding(new Insets(FxTheme.GAP_M));
                chartDomain.setPadding(new Insets(FxTheme.GAP_M));
                leftRail.setMinWidth(300);
                leftRail.setPrefWidth(330);
                leftRail.setMaxWidth(390);
                chartSidebar.setMinWidth(230);
                chartSidebar.setPrefWidth(260);
                chartSidebar.setMaxWidth(300);
                chartDomain.setSpacing(gap);
                leftRail.setSpacing(gap);
                chartSidebar.setSpacing(gap);
                applyRowGap(gap);
                chartArea.setMinHeight(440);
                chartRunLabel.setFont(Font.font("Monospaced", FontWeight.BOLD, 26));
                chartSummary.setFont(Font.font("SansSerif", FontWeight.BOLD, 13));
                rpmTile.applySizing(32);
                powerTile.applySizing(27);
                torqueTile.applySizing(27);
                o2Gauge.applySizing(30);
                tempGauge.applySizing(30);
                speedView.applySizing(23);
                pressureView.applySizing(23);
                humidityView.applySizing(23);
                saeCfView.applySizing(23);
                faultCountView.applySizing(23);
                bottomBar.setSpacing(gap);
                bottomBar.setPadding(new Insets(FxTheme.GAP_S, FxTheme.GAP_M, FxTheme.GAP_M, FxTheme.GAP_M));
                break;
            }
        }
    }

    private void applyRowGap(double gap) {
        powerTorqueRow.setSpacing(gap);
        o2TempRow.setSpacing(gap);
        speedPressureRow.setSpacing(gap);
        humidityCorrectionRow.setSpacing(gap);
    }

    private static LayoutTier tierFor(double width, double height) {
        if (width < 1360 || height < 820) {
            return LayoutTier.COMPACT;
        }
        if (width >= 2300 || height >= 1280) {
            return LayoutTier.LARGE;
        }
        return LayoutTier.NORMAL;
    }

    private HBox buildMainBody() {
        HBox body = new HBox(FxTheme.GAP_L);
        body.setPadding(new Insets(FxTheme.GAP_L));
        HBox.setHgrow(chartDomain, Priority.ALWAYS);
        chartDomain.setMaxWidth(Double.MAX_VALUE);
        body.getChildren().addAll(leftRail, chartDomain);
        return body;
    }

    private VBox buildRunModePane() {
        VBox pane = new VBox(FxTheme.GAP_L);
        pane.setPadding(FxTheme.PAD_PAGE);
        pane.setAlignment(javafx.geometry.Pos.TOP_CENTER);
        pane.setStyle("-fx-background-color: " + FxTheme.toCss(FxTheme.APP_BACKGROUND) + ";");

        runModeRunId.setFont(Font.font("Monospaced", FontWeight.BOLD, 34));
        runModeAfrValue.setFont(Font.font("Monospaced", FontWeight.BOLD, 34));
        runModeStateBadge.setFont(Font.font("SansSerif", FontWeight.BOLD, 22));
        runModeStateBadge.setPadding(new Insets(FxTheme.GAP_XS, FxTheme.GAP_L, FxTheme.GAP_XS, FxTheme.GAP_L));
        renderRunStateBadge("IDLE", new RunControlUiState());

        HBox topStrip = new HBox(FxTheme.GAP_XL, runModeRunId, runModeStateBadge, runModeAfrValue);
        topStrip.setAlignment(javafx.geometry.Pos.CENTER);

        HBox primaryRow = new HBox(FxTheme.GAP_XL, runModeRpmDial, runModeSpeedDial);
        primaryRow.setAlignment(javafx.geometry.Pos.CENTER);
        HBox secondaryRow = new HBox(FxTheme.GAP_XL, runModePowerDial, runModeTorqueDial);
        secondaryRow.setAlignment(javafx.geometry.Pos.CENTER);

        pane.getChildren().addAll(runModeTitle, topStrip, primaryRow, secondaryRow);
        return pane;
    }

    /** RPM dial full-scale follows the operator-chosen chart scale. */
    public void setRunModeRpmMax(double rpmMax) {
        runModeRpmDial.setRange(0, rpmMax, rpmMax >= 8000 ? 1000 : 500);
    }

    private void renderRunStateBadge(String machineState, RunControlUiState controlState) {
        String state = machineState == null ? "—" : machineState;
        boolean paused = controlState != null && controlState.isStarted() && "ARMED".equals(state);
        runModeStateBadge.setText(UiText.text(paused ? "PAUSED (below threshold)" : state));
        OperatorViewModel.Tone tone;
        if (paused) {
            tone = OperatorViewModel.Tone.CAUTION;
        } else if ("RECORDING".equals(state)) {
            tone = OperatorViewModel.Tone.NORMAL;
        } else if ("STOPPING".equals(state) || "ARMED".equals(state)) {
            tone = OperatorViewModel.Tone.CAUTION;
        } else if ("FAULT".equals(state)) {
            tone = OperatorViewModel.Tone.FAULT;
        } else {
            tone = OperatorViewModel.Tone.UNAVAILABLE;
        }
        runModeStateBadge.setTextFill(FxTheme.toneColor(tone));
        runModeStateBadge.setStyle(
            "-fx-border-color: " + FxTheme.toCss(FxTheme.toneBorder(tone)) + ";"
            + "-fx-border-radius: 8; -fx-border-width: 2;");
    }

    private VBox buildLeftRail() {
        VBox rail = new VBox(FxTheme.GAP_M);
        powerTorqueRow = buildTileRow(powerTile, torqueTile);
        o2TempRow = buildTileRow(o2Gauge, tempGauge);
        speedPressureRow = buildTileRow(speedView, pressureView);
        humidityCorrectionRow = buildTileRow(humidityView, saeCfView);
        rpmTile.setMaxWidth(Double.MAX_VALUE);

        rail.getChildren().addAll(
            telemetryTitle,
            engineTitle,
            rpmTile,
            powerTorqueRow,
            fuelEnvTitle,
            o2TempRow,
            speedPressureRow,
            humidityCorrectionRow,
            faultCountView,
            verticalSpacer()
        );
        return rail;
    }

    private HBox buildChartDomain() {
        HBox domain = new HBox(FxTheme.GAP_M);
        domain.setPadding(FxTheme.PAD_CARD);
        domain.setStyle(FxTheme.cardStyle(FxTheme.SURFACE));
        HBox.setHgrow(chartArea, Priority.ALWAYS);
        chartArea.setMaxWidth(Double.MAX_VALUE);
        chartArea.setMinWidth(0);
        domain.getChildren().addAll(chartArea, chartSidebar);
        return domain;
    }

    private HBox buildTileRow(Region left, Region right) {
        HBox row = new HBox(FxTheme.GAP_M);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        left.setMaxWidth(Double.MAX_VALUE);
        right.setMaxWidth(Double.MAX_VALUE);
        row.getChildren().addAll(left, right);
        return row;
    }

    private VBox buildChartArea() {
        VBox chart = new VBox(FxTheme.GAP_S);
        chart.setFillWidth(true);

        chartTitle.setTextFill(FxTheme.TEXT_SUBTLE);
        chartTitle.setFont(Font.font("SansSerif", FontWeight.BOLD, 16));

        chartRunLabel.setTextFill(FxTheme.TEXT_PRIMARY);
        chartRunLabel.setFont(Font.font("Monospaced", FontWeight.BOLD, 28));
        chartNote.setTextFill(FxTheme.TEXT_MUTED);
        chartNote.setFont(Font.font("SansSerif", FontWeight.NORMAL, 16));
        chartNote.setWrapText(true);

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        HBox overlayButtonRow = new HBox(FxTheme.GAP_S, overlayPickerButton, overlayIndicator);
        overlayButtonRow.setAlignment(Pos.CENTER_LEFT);
        VBox overlayControls = new VBox(FxTheme.GAP_XS, overlayButtonRow, clearOverlaysButton);
        overlayControls.setAlignment(Pos.TOP_RIGHT);

        HBox headerRow = new HBox(FxTheme.GAP_M);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.getChildren().addAll(
            new VBox(FxTheme.GAP_XS, chartTitle, chartRunLabel),
            headerSpacer,
            overlayControls
        );

        VBox plotArea = new VBox(FxTheme.GAP_M);
        plotArea.setPadding(FxTheme.PAD_CARD);
        plotArea.setStyle(
            "-fx-background-color: " + FxTheme.toCss(FxTheme.SURFACE_ALT) + ";" +
                "-fx-background-radius: 10;" +
                "-fx-border-color: " + FxTheme.toCss(FxTheme.BORDER_STRONG) + ";" +
                "-fx-border-radius: 10;"
        );
        plotArea.setMinHeight(360);
        dynoChartView.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(dynoChartView, Priority.ALWAYS);
        VBox.setVgrow(plotArea, Priority.ALWAYS);
        plotArea.getChildren().addAll(dynoChartView, chartNote);
        chart.getChildren().addAll(headerRow, plotArea);
        return chart;
    }

    private VBox buildChartSidebar() {
        VBox rail = new VBox(FxTheme.GAP_M);
        styleSidebarTitle(peakTitle);
        styleSidebarTitle(axisTitle);

        styleSidebarPrimary(peakPowerLabel);
        styleSidebarPrimary(peakTorqueLabel);

        runConfigSummary.setWrapText(true);
        runConfigSummary.setTextFill(FxTheme.TEXT_PRIMARY);
        runConfigSummary.setFont(Font.font("SansSerif", FontWeight.BOLD, 15));
        Tooltip.install(
            runConfigSummary,
            new Tooltip(UiText.text("Run/chart configuration stays tied to run setup and chart context."))
        );

        rail.getChildren().addAll(
            chartContextTitle,
            infoCard(peakTitle, peakPowerLabel, peakTorqueLabel),
            infoCard(axisTitle, runConfigSummary),
            verticalSpacer()
        );
        return rail;
    }

    private HBox buildBottomBar() {
        HBox bottom = new HBox(FxTheme.GAP_M);
        bottom.setAlignment(Pos.CENTER_LEFT);
        bottom.setStyle(
            "-fx-background-color: " + FxTheme.toCss(FxTheme.APP_BACKGROUND) + ";" +
                "-fx-border-color: " + FxTheme.toCss(FxTheme.BORDER_STRONG) + ";" +
                "-fx-border-width: 1 0 0 0;"
        );

        VBox currentRunCard = summaryCard(currentRunTitle, currentRunPrimary, currentRunSecondary);
        VBox compareCard = summaryCard(compareSummaryTitle, compareSummaryPrimary, compareSummarySecondary);
        VBox statusCard = summaryCard(operatorStatusTitle, operatorStatusPrimary, operatorStatusSecondary);

        HBox.setHgrow(currentRunCard, Priority.ALWAYS);
        HBox.setHgrow(compareCard, Priority.ALWAYS);
        HBox.setHgrow(statusCard, Priority.ALWAYS);
        currentRunCard.setMaxWidth(Double.MAX_VALUE);
        compareCard.setMaxWidth(Double.MAX_VALUE);
        statusCard.setMaxWidth(Double.MAX_VALUE);

        bottom.getChildren().addAll(currentRunCard, compareCard, statusCard);
        return bottom;
    }

    private void applyOperatorStatusTone(OperatorViewModel.Tone tone) {
        operatorStatusPrimary.setTextFill(FxTheme.toneColor(tone));
    }

    private Label runModeMetricValue(String text) {
        Label label = new Label(UiText.text(text));
        label.setTextFill(FxTheme.TEXT_PRIMARY);
        label.setFont(Font.font("Monospaced", FontWeight.BOLD, 64));
        return label;
    }

    private VBox runModeMetricCard(String title, Label value) {
        Label heading = new Label(title);
        heading.setTextFill(FxTheme.TEXT_SUBTLE);
        heading.setFont(Font.font("SansSerif", FontWeight.BOLD, 20));
        VBox box = new VBox(FxTheme.GAP_S, heading, value);
        box.setPadding(FxTheme.PAD_CARD);
        box.setStyle(FxTheme.cardStyle(FxTheme.SURFACE));
        return box;
    }

    private Label sectionTitle(String text) {
        Label label = new Label(text);
        label.setTextFill(FxTheme.TEXT_SUBTLE);
        label.setFont(Font.font("SansSerif", FontWeight.BOLD, 15));
        return label;
    }

    private Label subSectionTitle(String text) {
        Label label = new Label(text);
        label.setTextFill(FxTheme.TEXT_MUTED);
        label.setFont(Font.font("SansSerif", FontWeight.BOLD, 14));
        return label;
    }

    private void styleSidebarTitle(Label label) {
        label.setTextFill(FxTheme.TEXT_SUBTLE);
        label.setFont(Font.font("SansSerif", FontWeight.BOLD, 15));
    }

    private void styleSidebarPrimary(Label label) {
        label.setTextFill(FxTheme.TEXT_PRIMARY);
        label.setFont(Font.font("SansSerif", FontWeight.BOLD, 18));
        label.setWrapText(true);
    }

    private VBox summaryCard(Label title, Label primary, Label secondary) {
        styleSummaryTitle(title);
        styleSummaryPrimary(primary);
        styleSummarySecondary(secondary);
        return infoCard(title, primary, secondary);
    }

    private void styleSummaryTitle(Label label) {
        label.setTextFill(FxTheme.TEXT_SUBTLE);
        label.setFont(Font.font("SansSerif", FontWeight.BOLD, 15));
    }

    private void styleSummaryPrimary(Label label) {
        label.setTextFill(FxTheme.TEXT_PRIMARY);
        label.setFont(Font.font("SansSerif", FontWeight.BOLD, 18));
        label.setWrapText(true);
    }

    private void styleSummarySecondary(Label label) {
        label.setTextFill(FxTheme.TEXT_MUTED);
        label.setFont(Font.font("SansSerif", FontWeight.NORMAL, 15));
        label.setWrapText(true);
    }

    private VBox infoCard(Node... nodes) {
        VBox card = new VBox(FxTheme.GAP_XS);
        card.setPadding(new Insets(4, 4, 4, 4));
        card.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        for (int index = 0; index < nodes.length; index++) {
            if (nodes[index] instanceof Region) {
                ((Region) nodes[index]).setMaxWidth(Double.MAX_VALUE);
            }
        }
        card.getChildren().addAll(nodes);
        return card;
    }

    private Region verticalSpacer() {
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        return spacer;
    }
}
