package com.dyno.fx;

import com.dyno.export.RunExporter;
import com.dyno.history.HistoryApiClient;
import com.dyno.history.RepeatabilityMetricDto;
import com.dyno.history.RepeatabilityReportDto;
import com.dyno.history.RunHistoryFrameSeriesDto;
import com.dyno.history.RunHistorySummaryDto;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class ExportDialog extends Dialog<Void> {

    private final HistoryApiClient historyApiClient;
    private final Executor executor;

    private final TableView<RunHistorySummaryDto> runTable = new TableView<>();
    private final Spinner<Integer> rpmStepSpinner = new Spinner<>(50, 500, 100, 50);
    private final Button exportPdfButton = new Button(UiText.text("EXPORT PDF"));
    private final Button exportCsvButton = new Button(UiText.text("EXPORT CSV"));
    private final Button repeatabilityButton = new Button(UiText.text("REPEATABILITY REPORT"));
    private final Label statusLabel = new Label();

    public ExportDialog(Stage owner, HistoryApiClient historyApiClient, Executor executor) {
        this.historyApiClient = historyApiClient;
        this.executor = executor;

        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        setTitle(UiText.text("Export Run Data"));
        setResizable(true);

        runTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        buildColumns();

        exportPdfButton.setDisable(true);
        exportCsvButton.setDisable(true);
        repeatabilityButton.setDisable(true);

        runTable.getSelectionModel().getSelectedItems().addListener(
            (javafx.collections.ListChangeListener<RunHistorySummaryDto>) c -> updateButtons());

        exportPdfButton.setOnAction(e -> handleExportPdf());
        exportCsvButton.setOnAction(e -> handleExportCsv());
        repeatabilityButton.setOnAction(e -> handleRepeatability());

        DialogPane pane = getDialogPane();
        pane.getButtonTypes().add(ButtonType.CLOSE);
        pane.setContent(buildContent());
        pane.setPrefWidth(680);

        setResultConverter(b -> null);

        loadRuns();
    }

    @SuppressWarnings("unchecked")
    private void buildColumns() {
        TableColumn<RunHistorySummaryDto, Long> idCol = new TableColumn<>(UiText.text("Run ID"));
        idCol.setCellValueFactory(new PropertyValueFactory<>("runId"));
        idCol.setPrefWidth(70);

        TableColumn<RunHistorySummaryDto, String> dateCol = new TableColumn<>(UiText.text("Date"));
        dateCol.setCellValueFactory(new PropertyValueFactory<>("date"));
        dateCol.setPrefWidth(160);

        TableColumn<RunHistorySummaryDto, Double> hpCol = new TableColumn<>(UiText.text("Peak HP"));
        hpCol.setCellValueFactory(new PropertyValueFactory<>("peakPowerHp"));
        hpCol.setPrefWidth(90);

        TableColumn<RunHistorySummaryDto, Double> nmCol = new TableColumn<>(UiText.text("Peak Nm"));
        nmCol.setCellValueFactory(new PropertyValueFactory<>("peakTorqueNm"));
        nmCol.setPrefWidth(90);

        TableColumn<RunHistorySummaryDto, String> modeCol = new TableColumn<>(UiText.text("Mode"));
        modeCol.setCellValueFactory(new PropertyValueFactory<>("sourceMode"));
        modeCol.setPrefWidth(100);

        runTable.getColumns().addAll(idCol, dateCol, hpCol, nmCol, modeCol);
        runTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        runTable.setPrefHeight(300);
    }

    private VBox buildContent() {
        Label title = new Label(UiText.text("Export Run Data"));
        title.setStyle("-fx-font-size: 17px; -fx-font-weight: bold;");

        Label hint = new Label(UiText.text("Select one or more runs to export."));
        hint.setStyle("-fx-font-size: 12px; -fx-text-fill: #5577AA;");

        Label rpmLabel = new Label(UiText.text("RPM Step:"));
        rpmStepSpinner.setPrefWidth(90);
        rpmStepSpinner.setEditable(true);

        HBox controls = new HBox(FxTheme.GAP_M, rpmLabel, rpmStepSpinner,
            exportPdfButton, exportCsvButton, repeatabilityButton);
        controls.setAlignment(Pos.CENTER_LEFT);

        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #5577AA;");
        statusLabel.setWrapText(true);

        VBox box = new VBox(FxTheme.GAP_M, title, hint, runTable, controls, statusLabel);
        box.setPadding(FxTheme.PAD_DIALOG);
        VBox.setVgrow(runTable, Priority.ALWAYS);
        return box;
    }

    private void updateButtons() {
        int count = runTable.getSelectionModel().getSelectedItems().size();
        exportPdfButton.setDisable(count < 1);
        exportCsvButton.setDisable(count < 1);
        repeatabilityButton.setDisable(count < 2);
    }

    private void disableAll(boolean disable) {
        exportPdfButton.setDisable(disable);
        exportCsvButton.setDisable(disable);
        repeatabilityButton.setDisable(disable);
    }

    private void setStatus(String msg) {
        statusLabel.setText(msg);
    }

    private void loadRuns() {
        setStatus(UiText.text("Loading runs..."));
        CompletableFuture.supplyAsync(() -> {
            try {
                return historyApiClient.listRuns();
            } catch (Exception e) {
                return null;
            }
        }, executor).thenAccept(runs -> Platform.runLater(() -> {
            if (runs != null) {
                runTable.getItems().setAll(runs);
                setStatus("");
            } else {
                setStatus(UiText.text("Failed to load run list."));
            }
        }));
    }

    private void handleExportPdf() {
        List<RunHistorySummaryDto> selected = new ArrayList<>(
            runTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) return;
        RunHistorySummaryDto run = selected.get(0);

        FileChooser chooser = new FileChooser();
        chooser.setTitle(UiText.text("Save PDF Report"));
        chooser.setInitialFileName(runFileName(run.getRunId()) + ".pdf");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("PDF Files", "*.pdf"));
        File file = chooser.showSaveDialog(getOwner());
        if (file == null) return;

        int step = rpmStepSpinner.getValue();
        setStatus(UiText.text("Exporting PDF..."));
        disableAll(true);

        CompletableFuture.supplyAsync(() -> {
            try {
                RunHistoryFrameSeriesDto series = historyApiClient.getRunFrames(run.getRunId());
                RunExporter.RunMeta meta = new RunExporter.RunMeta(
                    runFileName(run.getRunId()), null,
                    run.getDate(), run.getPeakPowerHp(), run.getPeakTorqueNm());
                RunExporter.exportPdf(series.getFrames(), meta, file, step);
                return (String) null;
            } catch (Exception e) {
                return e.getMessage();
            }
        }, executor).thenAccept(err -> Platform.runLater(() -> {
            disableAll(false);
            updateButtons();
            setStatus(err == null
                ? UiText.text("PDF saved: ") + file.getName()
                : UiText.text("Export failed: ") + err);
        }));
    }

    private void handleExportCsv() {
        List<RunHistorySummaryDto> selected = new ArrayList<>(
            runTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) return;
        RunHistorySummaryDto run = selected.get(0);

        FileChooser chooser = new FileChooser();
        chooser.setTitle(UiText.text("Save CSV"));
        chooser.setInitialFileName(runFileName(run.getRunId()) + ".csv");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("CSV Files", "*.csv"));
        File file = chooser.showSaveDialog(getOwner());
        if (file == null) return;

        int step = rpmStepSpinner.getValue();
        setStatus(UiText.text("Exporting CSV..."));
        disableAll(true);

        CompletableFuture.supplyAsync(() -> {
            try {
                RunHistoryFrameSeriesDto series = historyApiClient.getRunFrames(run.getRunId());
                RunExporter.exportCsv(series.getFrames(), file, step);
                return (String) null;
            } catch (Exception e) {
                return e.getMessage();
            }
        }, executor).thenAccept(err -> Platform.runLater(() -> {
            disableAll(false);
            updateButtons();
            setStatus(err == null
                ? UiText.text("CSV saved: ") + file.getName()
                : UiText.text("Export failed: ") + err);
        }));
    }

    private void handleRepeatability() {
        List<RunHistorySummaryDto> selected = new ArrayList<>(
            runTable.getSelectionModel().getSelectedItems());
        if (selected.size() < 2) return;

        List<Long> ids = new ArrayList<>();
        for (RunHistorySummaryDto r : selected) {
            ids.add(r.getRunId());
        }

        setStatus(UiText.text("Computing repeatability report..."));
        disableAll(true);

        final String[] errorHolder = {null};
        CompletableFuture.supplyAsync(() -> {
            try {
                return historyApiClient.getRepeatabilityReport(ids);
            } catch (Exception e) {
                errorHolder[0] = e.getMessage();
                return null;
            }
        }, executor).thenAccept(report -> Platform.runLater(() -> {
            disableAll(false);
            updateButtons();
            if (errorHolder[0] != null) {
                setStatus(UiText.text("Repeatability failed: ") + errorHolder[0]);
            } else if (report == null) {
                setStatus(UiText.text("No repeatability data returned."));
            } else {
                setStatus("");
                showRepeatabilityResult(report);
            }
        }));
    }

    private void showRepeatabilityResult(RepeatabilityReportDto report) {
        Dialog<Void> resultDialog = new Dialog<>();
        resultDialog.initOwner(getOwner());
        resultDialog.initModality(Modality.APPLICATION_MODAL);
        resultDialog.setTitle(UiText.text("Repeatability Report"));

        TableView<String[]> table = new TableView<>();
        table.setPrefWidth(520);
        table.setPrefHeight(160);

        String[] colNames = {
            UiText.text("Metric"),
            UiText.text("Min"),
            UiText.text("Max"),
            UiText.text("Mean"),
            UiText.text("Span %")
        };
        double[] colWidths = {160, 75, 75, 75, 75};
        for (int i = 0; i < colNames.length; i++) {
            final int ci = i;
            TableColumn<String[], String> tc = new TableColumn<>(colNames[i]);
            tc.setCellValueFactory(data ->
                new SimpleStringProperty(data.getValue()[ci]));
            tc.setPrefWidth(colWidths[i]);
            table.getColumns().add(tc);
        }

        DecimalFormat df = new DecimalFormat("0.00");
        table.getItems().add(metricRow(UiText.text("Peak HP"), report.getPeakHp(), df));
        table.getItems().add(metricRow(UiText.text("Peak Torque (Nm)"), report.getPeakTorqueNm(), df));
        if (report.getPeakSpeedKmh() != null) {
            table.getItems().add(metricRow(UiText.text("Peak Speed (km/h)"), report.getPeakSpeedKmh(), df));
        }
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        Label runsLabel = new Label(UiText.text("Runs: ") + (report.getRunIds() != null
            ? report.getRunIds().toString() : "—"));
        runsLabel.setStyle("-fx-font-weight: bold;");

        VBox content = new VBox(FxTheme.GAP_M, runsLabel, table);
        content.setPadding(FxTheme.PAD_DIALOG);

        resultDialog.getDialogPane().setContent(content);
        resultDialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        resultDialog.showAndWait();
    }

    private static String[] metricRow(String name, RepeatabilityMetricDto m, DecimalFormat df) {
        if (m == null) return new String[]{name, "—", "—", "—", "—"};
        return new String[]{
            name,
            df.format(m.getMin()),
            df.format(m.getMax()),
            df.format(m.getMean()),
            df.format(m.getSpanPercent()) + "%"
        };
    }

    private static String runFileName(Long runId) {
        return runId == null ? "run" : String.format("RUN-%05d", runId.longValue());
    }
}
