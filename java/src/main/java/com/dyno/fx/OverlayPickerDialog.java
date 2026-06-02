package com.dyno.fx;

import com.dyno.history.HistoryApiClient;
import com.dyno.history.RunHistorySummaryDto;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class OverlayPickerDialog extends Dialog<List<Long>> {
    private static final int MAX_OVERLAYS = 7;
    private static final DecimalFormat ONE_DECIMAL = new DecimalFormat("0.0");

    private final HistoryApiClient historyApiClient;
    private final Executor executor;

    @SuppressWarnings("unchecked")
    private final TableView<RunHistorySummaryDto> runTable = new TableView<>();
    private final Label counterLabel = new Label("0 / " + MAX_OVERLAYS + " selected");
    private final Label statusLabel = new Label();
    private Button applyButton;

    private boolean guardingSelection = false;

    private OverlayPickerDialog(Stage owner, HistoryApiClient historyApiClient, Executor executor) {
        this.historyApiClient = historyApiClient;
        this.executor = executor;

        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        setTitle(UiText.text("Select Overlay Runs"));
        setResizable(true);

        runTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        buildColumns();

        ButtonType applyType = new ButtonType(UiText.text("APPLY"), ButtonBar.ButtonData.OK_DONE);

        DialogPane pane = getDialogPane();
        pane.getButtonTypes().addAll(applyType, ButtonType.CANCEL);
        pane.setContent(buildContent());
        pane.setPrefWidth(600);

        applyButton = (Button) pane.lookupButton(applyType);
        applyButton.setDisable(true);

        runTable.getSelectionModel().getSelectedItems().addListener(
            (javafx.collections.ListChangeListener<RunHistorySummaryDto>) c -> {
                if (guardingSelection) return;
                int count = runTable.getSelectionModel().getSelectedItems().size();
                if (count > MAX_OVERLAYS) {
                    guardingSelection = true;
                    List<RunHistorySummaryDto> toDeselect = new ArrayList<RunHistorySummaryDto>();
                    while (c.next()) {
                        if (c.wasAdded()) {
                            toDeselect.addAll(c.getAddedSubList());
                        }
                    }
                    for (RunHistorySummaryDto item : toDeselect) {
                        int idx = runTable.getItems().indexOf(item);
                        if (idx >= 0) runTable.getSelectionModel().clearSelection(idx);
                    }
                    guardingSelection = false;
                }
                updateSelectionState();
            }
        );

        setResultConverter(bt -> {
            if (!applyType.equals(bt)) return null;
            List<Long> ids = new ArrayList<Long>();
            for (RunHistorySummaryDto r : runTable.getSelectionModel().getSelectedItems()) {
                if (r.getRunId() != null) ids.add(r.getRunId());
            }
            return ids;
        });

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

        TableColumn<RunHistorySummaryDto, String> hpCol = new TableColumn<>(UiText.text("Peak HP"));
        hpCol.setCellValueFactory(data -> {
            Double v = data.getValue().getPeakPowerHp();
            return new SimpleStringProperty(v == null ? "—" : ONE_DECIMAL.format(v.doubleValue()));
        });
        hpCol.setPrefWidth(90);

        TableColumn<RunHistorySummaryDto, String> nmCol = new TableColumn<>(UiText.text("Peak Nm"));
        nmCol.setCellValueFactory(data -> {
            Double v = data.getValue().getPeakTorqueNm();
            return new SimpleStringProperty(v == null ? "—" : ONE_DECIMAL.format(v.doubleValue()));
        });
        nmCol.setPrefWidth(90);

        runTable.getColumns().addAll(idCol, dateCol, hpCol, nmCol);
        runTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        runTable.setPrefHeight(280);
    }

    private VBox buildContent() {
        Label title = new Label(UiText.text("Select Overlay Runs"));
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        Label hint = new Label(UiText.text("Select up to 7 completed runs to overlay behind the live chart."));
        hint.setStyle("-fx-font-size: 12px; -fx-text-fill: #5577AA;");

        counterLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #EAB308;");

        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #5577AA;");
        statusLabel.setWrapText(true);

        VBox box = new VBox(8, title, hint, runTable, counterLabel, statusLabel);
        box.setPadding(new Insets(14));
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }

    private void updateSelectionState() {
        int count = runTable.getSelectionModel().getSelectedItems().size();
        counterLabel.setText(count + " / " + MAX_OVERLAYS + " " + UiText.text("selected"));
        if (applyButton != null) {
            applyButton.setDisable(count < 1);
        }
    }

    private void loadRuns() {
        statusLabel.setText(UiText.text("Loading runs..."));
        CompletableFuture.supplyAsync(() -> {
            try {
                List<RunHistorySummaryDto> all = historyApiClient.listRuns();
                List<RunHistorySummaryDto> completed = new ArrayList<RunHistorySummaryDto>();
                if (all != null) {
                    for (RunHistorySummaryDto r : all) {
                        if (r.getEndedAtMs() != null) completed.add(r);
                    }
                }
                return completed;
            } catch (Exception e) {
                return null;
            }
        }, executor).thenAccept(runs -> Platform.runLater(() -> {
            if (runs != null) {
                runTable.getItems().setAll(runs);
                statusLabel.setText("");
            } else {
                statusLabel.setText(UiText.text("Failed to load run list."));
            }
        }));
    }

    public static List<Long> show(Stage owner, HistoryApiClient historyApiClient, Executor executor) {
        OverlayPickerDialog dialog = new OverlayPickerDialog(owner, historyApiClient, executor);
        Optional<List<Long>> result = dialog.showAndWait();
        return result.orElse(null);
    }
}
