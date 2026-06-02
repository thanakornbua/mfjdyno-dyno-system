package com.dyno.calibration;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

public final class AuditLogView extends VBox {
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss")
        .withLocale(Locale.US)
        .withZone(ZoneId.systemDefault());

    public AuditLogView(CalibrationApiClient apiClient, Executor executor) {
        setSpacing(10);
        setPadding(new Insets(12));

        TableView<AuditRecordDto> table = new TableView<AuditRecordDto>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("No audit records found."));

        TableColumn<AuditRecordDto, String> tsCol = new TableColumn<AuditRecordDto, String>("Timestamp");
        tsCol.setCellValueFactory(c -> new SimpleStringProperty(formatTimestamp(c.getValue().getOccurredAt())));

        TableColumn<AuditRecordDto, String> eventCol = new TableColumn<AuditRecordDto, String>("Event");
        eventCol.setCellValueFactory(c -> new SimpleStringProperty(
            c.getValue().getEvent() == null ? "—" : c.getValue().getEvent()
        ));

        TableColumn<AuditRecordDto, String> profileCol = new TableColumn<AuditRecordDto, String>("Profile ID");
        profileCol.setCellValueFactory(c -> {
            Long id = c.getValue().getCalibrationProfileId();
            return new SimpleStringProperty(id == null ? "—" : String.valueOf(id.longValue()));
        });

        TableColumn<AuditRecordDto, String> paramsCol = new TableColumn<AuditRecordDto, String>("Params Snapshot");
        paramsCol.setCellValueFactory(c -> {
            Object snapshot = c.getValue().getParamsSnapshot();
            return new SimpleStringProperty(snapshot == null ? "—" : snapshot.toString());
        });
        paramsCol.setCellFactory(col -> new TableCell<AuditRecordDto, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                String truncated = item.length() > 80 ? item.substring(0, 80) + "..." : item;
                setText(truncated);
                if (item.length() > 80) {
                    Tooltip tip = new Tooltip(item);
                    tip.setWrapText(true);
                    tip.setMaxWidth(500);
                    setTooltip(tip);
                } else {
                    setTooltip(null);
                }
            }
        });

        table.getColumns().setAll(java.util.Arrays.asList(tsCol, eventCol, profileCol, paramsCol));
        VBox.setVgrow(table, Priority.ALWAYS);

        Label statusLabel = new Label("Loading...");

        Button refreshBtn = new Button("Refresh");
        refreshBtn.setOnAction(e -> loadData(apiClient, executor, table, statusLabel));

        getChildren().addAll(refreshBtn, table, statusLabel);
        loadData(apiClient, executor, table, statusLabel);
    }

    private static String formatTimestamp(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "—";
        }
        try {
            return TS_FORMAT.format(Instant.parse(raw));
        } catch (Exception ignored) {
            return raw;
        }
    }

    private void loadData(
        CalibrationApiClient apiClient,
        Executor executor,
        TableView<AuditRecordDto> table,
        Label statusLabel
    ) {
        statusLabel.setText("Loading...");
        CompletableFuture
            .supplyAsync(() -> {
                try {
                    return apiClient.listAuditRecords();
                } catch (IOException | InterruptedException e) {
                    throw new CompletionException(e);
                }
            }, executor)
            .thenAccept(records -> Platform.runLater(() -> {
                table.setItems(FXCollections.observableArrayList(records));
                statusLabel.setText(records.size() + " record(s)");
            }))
            .exceptionally(err -> {
                Throwable cause = err.getCause() != null ? err.getCause() : err;
                Platform.runLater(() -> statusLabel.setText("Failed to load: " + cause.getMessage()));
                return null;
            });
    }
}
