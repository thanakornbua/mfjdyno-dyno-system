package com.dyno.fx;

import com.dyno.history.HistoryApiClient;
import com.dyno.history.RunHistorySummaryDto;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class CompareSelectView extends Dialog<CompareSelectView.Result> {
    private final HistoryApiClient client;
    private final ObservableList<RunHistorySummaryDto> rows;
    private final FilteredList<RunHistorySummaryDto> filteredRows;
    private final TableView<RunHistorySummaryDto> table = new TableView<RunHistorySummaryDto>();
    private final Label statusLabel = new Label("");

    private final ButtonType applyType = new ButtonType(UiText.text("COMPARE"), ButtonBar.ButtonData.OK_DONE);
    private final ButtonType clearType = new ButtonType(UiText.text("LIVE VIEW"), ButtonBar.ButtonData.LEFT);

    public CompareSelectView(
        Stage owner,
        HistoryApiClient client,
        List<RunHistorySummaryDto> runs,
        boolean compareActive
    ) {
        this.client = client;
        this.rows = FXCollections.observableArrayList(runs);
        this.filteredRows = new FilteredList<>(rows, p -> true);

        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        setTitle(UiText.text("COMPARE"));

        DialogPane pane = getDialogPane();
        pane.getButtonTypes().addAll(applyType);
        if (compareActive) {
            pane.getButtonTypes().add(clearType);
        }
        pane.getButtonTypes().add(ButtonType.CANCEL);
        pane.setContent(buildContent());

        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setItems(filteredRows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        installColumns();
        installButtons();

        setResultConverter(button -> {
            if (button == clearType) {
                return Result.clearRequested();
            }
            if (button == applyType) {
                List<RunHistorySummaryDto> selected = new ArrayList<RunHistorySummaryDto>(
                    table.getSelectionModel().getSelectedItems()
                );
                if (selected.size() < 2 || selected.size() > 4) {
                    return null;
                }
                ArrayList<Long> runIds = new ArrayList<Long>(selected.size());
                for (int index = 0; index < selected.size(); index++) {
                    runIds.add(selected.get(index).getRunId());
                }
                return Result.selected(runIds);
            }
            return null;
        });
    }

    public static Result show(
        Stage owner,
        HistoryApiClient client,
        List<RunHistorySummaryDto> runs,
        boolean compareActive
    ) {
        CompareSelectView dialog = new CompareSelectView(owner, client, runs, compareActive);
        Optional<Result> result = dialog.showAndWait();
        return result.orElse(null);
    }

    private Node buildContent() {
        Label title = new Label(UiText.text("SELECT RUNS TO COMPARE"));
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        Label note = new Label(UiText.text("Select 2 to 4 stored runs. Use Delete to remove one stored run."));
        note.setWrapText(true);

        TextField searchField = new TextField();
        searchField.setPromptText(UiText.text("Search by ID, date, vehicle, plate, HP, torque..."));
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            String lower = newVal == null ? "" : newVal.trim().toLowerCase();
            if (lower.isEmpty()) {
                filteredRows.setPredicate(p -> true);
            } else {
                filteredRows.setPredicate(run -> matchesSearch(run, lower));
            }
        });

        HBox actionRow = new HBox(8);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        Button deleteButton = new Button(UiText.text("DELETE"));
        deleteButton.setOnAction(event -> handleDelete());
        actionRow.getChildren().add(deleteButton);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        actionRow.getChildren().add(spacer);
        actionRow.getChildren().add(statusLabel);

        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-text-fill: " + FxTheme.toCss(FxTheme.TEXT_MUTED) + ";");

        table.setMinWidth(900);
        table.setMinHeight(320);

        VBox box = new VBox(10, title, note, searchField, actionRow, table);
        box.setPadding(new Insets(12));
        return box;
    }

    private static boolean matchesSearch(RunHistorySummaryDto run, String lower) {
        if (run.getRunId() != null && run.getRunId().toString().contains(lower)) return true;
        if (run.getDate() != null && run.getDate().toLowerCase().contains(lower)) return true;
        if (run.getVehicleName() != null && run.getVehicleName().toLowerCase().contains(lower)) return true;
        if (run.getLicensePlate() != null && run.getLicensePlate().toLowerCase().contains(lower)) return true;
        if (run.getPeakPowerHp() != null && String.format("%.1f", run.getPeakPowerHp()).contains(lower)) return true;
        if (run.getPeakTorqueNm() != null && String.format("%.1f", run.getPeakTorqueNm()).contains(lower)) return true;
        return false;
    }

    private void installColumns() {
        TableColumn<RunHistorySummaryDto, Long> runIdColumn = new TableColumn<RunHistorySummaryDto, Long>(UiText.text("RUN ID"));
        runIdColumn.setCellValueFactory(new PropertyValueFactory<RunHistorySummaryDto, Long>("runId"));

        TableColumn<RunHistorySummaryDto, String> dateColumn = new TableColumn<RunHistorySummaryDto, String>(UiText.text("DATE"));
        dateColumn.setCellValueFactory(new PropertyValueFactory<RunHistorySummaryDto, String>("date"));

        TableColumn<RunHistorySummaryDto, String> vehicleColumn = new TableColumn<RunHistorySummaryDto, String>(UiText.text("VEHICLE"));
        vehicleColumn.setCellValueFactory(cellData -> {
            String v = cellData.getValue().getVehicleName();
            return new javafx.beans.property.SimpleStringProperty(v != null ? v : "");
        });

        TableColumn<RunHistorySummaryDto, String> plateColumn = new TableColumn<RunHistorySummaryDto, String>(UiText.text("PLATE"));
        plateColumn.setCellValueFactory(cellData -> {
            String p = cellData.getValue().getLicensePlate();
            return new javafx.beans.property.SimpleStringProperty(p != null ? p : "");
        });

        TableColumn<RunHistorySummaryDto, String> sourceColumn = new TableColumn<RunHistorySummaryDto, String>(UiText.text("SOURCE"));
        sourceColumn.setCellValueFactory(new PropertyValueFactory<RunHistorySummaryDto, String>("sourceMode"));

        TableColumn<RunHistorySummaryDto, String> correctionColumn = new TableColumn<RunHistorySummaryDto, String>(UiText.text("CORRECTION"));
        correctionColumn.setCellValueFactory(new PropertyValueFactory<RunHistorySummaryDto, String>("correctionMode"));

        TableColumn<RunHistorySummaryDto, Double> powerColumn = new TableColumn<RunHistorySummaryDto, Double>(UiText.text("PEAK HP"));
        powerColumn.setCellValueFactory(new PropertyValueFactory<RunHistorySummaryDto, Double>("peakPowerHp"));

        TableColumn<RunHistorySummaryDto, Double> torqueColumn = new TableColumn<RunHistorySummaryDto, Double>(UiText.text("PEAK NM"));
        torqueColumn.setCellValueFactory(new PropertyValueFactory<RunHistorySummaryDto, Double>("peakTorqueNm"));

        table.getColumns().setAll(runIdColumn, dateColumn, vehicleColumn, plateColumn, sourceColumn, correctionColumn, powerColumn, torqueColumn);
    }

    private void installButtons() {
        Node applyButton = getDialogPane().lookupButton(applyType);
        if (applyButton instanceof Button) {
            Button button = (Button) applyButton;
            button.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                int count = table.getSelectionModel().getSelectedItems().size();
                if (count < 2 || count > 4) {
                    statusLabel.setText(UiText.text("Select between 2 and 4 stored runs."));
                    event.consume();
                }
            });
        }
    }

    private void handleDelete() {
        RunHistorySummaryDto selected = table.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getRunId() == null) {
            statusLabel.setText(UiText.text("Select one stored run to delete."));
            return;
        }

        try {
            boolean deleted = client.deleteRun(selected.getRunId().longValue());
            if (deleted) {
                rows.remove(selected);
                statusLabel.setText(UiText.text("Deleted stored run ") + selected.getRunId());
            } else {
                statusLabel.setText(UiText.text("Stored run could not be deleted."));
            }
        } catch (IOException error) {
            statusLabel.setText(UiText.text("Delete failed: ") + error.getMessage());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            statusLabel.setText(UiText.text("Delete interrupted."));
        }
    }

    public static final class Result {
        private final boolean clearRequested;
        private final List<Long> selectedRunIds;

        private Result(boolean clearRequested, List<Long> selectedRunIds) {
            this.clearRequested = clearRequested;
            this.selectedRunIds = selectedRunIds;
        }

        public static Result selected(List<Long> selectedRunIds) {
            return new Result(false, selectedRunIds);
        }

        public static Result clearRequested() {
            return new Result(true, null);
        }

        public boolean isClearRequested() {
            return clearRequested;
        }

        public List<Long> getSelectedRunIds() {
            return selectedRunIds;
        }
    }
}
