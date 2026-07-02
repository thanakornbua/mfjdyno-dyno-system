package com.dyno.fx;

import com.dyno.history.RunHistorySummaryDto;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
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
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.List;
import java.util.Optional;

/**
 * Modal run-list dialog for single-run export selection.
 * Shows the stored run list with single-selection mode.
 * Returns the chosen RunHistorySummaryDto, or null if cancelled.
 */
public final class ExportRunPickerView extends Dialog<RunHistorySummaryDto> {
    private final TableView<RunHistorySummaryDto> table = new TableView<RunHistorySummaryDto>();
    private final Label statusLabel = new Label("");

    private final ButtonType pickType =
        new ButtonType(UiText.text("SELECT FOR EXPORT"), ButtonBar.ButtonData.OK_DONE);

    public ExportRunPickerView(Stage owner, List<RunHistorySummaryDto> runs) {
        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        setTitle(UiText.text("Select Run to Export"));

        ObservableList<RunHistorySummaryDto> rows = FXCollections.observableArrayList(runs);
        table.setItems(rows);
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        installColumns();

        DialogPane pane = getDialogPane();
        pane.getButtonTypes().addAll(pickType, ButtonType.CANCEL);
        pane.setContent(buildContent());

        // Enforce a selection before allowing EXPORT
        Node pickButton = pane.lookupButton(pickType);
        if (pickButton != null) {
            pickButton.setDisable(true);
            table.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> pickButton.setDisable(newVal == null)
            );
        }

        setResultConverter(button -> {
            if (button == pickType) {
                return table.getSelectionModel().getSelectedItem();
            }
            return null;
        });
    }

    public static RunHistorySummaryDto show(Stage owner, List<RunHistorySummaryDto> runs) {
        ExportRunPickerView dialog = new ExportRunPickerView(owner, runs);
        Optional<RunHistorySummaryDto> result = dialog.showAndWait();
        return result.orElse(null);
    }

    private VBox buildContent() {
        Label title = new Label(UiText.text("SELECT RUN TO EXPORT"));
        title.setStyle("-fx-font-size: 17px; -fx-font-weight: bold;");

        Label note = new Label(UiText.text("Select one stored run to export as PDF, PNG, CSV, or JSON."));
        note.setWrapText(true);

        table.setMinWidth(720);
        table.setMinHeight(300);

        VBox box = new VBox(FxTheme.GAP_M, title, note, statusLabel, table);
        box.setPadding(FxTheme.PAD_DIALOG);
        return box;
    }

    private void installColumns() {
        TableColumn<RunHistorySummaryDto, String> idCol =
            new TableColumn<RunHistorySummaryDto, String>(UiText.text("RUN ID"));
        idCol.setCellValueFactory(cellData -> new javafx.beans.property.SimpleStringProperty(
            com.dyno.presenter.RunLabels.displayId(cellData.getValue())));

        TableColumn<RunHistorySummaryDto, String> customerCol =
            new TableColumn<RunHistorySummaryDto, String>(UiText.text("CUSTOMER"));
        customerCol.setCellValueFactory(cellData -> {
            String c = cellData.getValue().getCustomerName();
            return new javafx.beans.property.SimpleStringProperty(c != null ? c : "");
        });

        TableColumn<RunHistorySummaryDto, String> dateCol =
            new TableColumn<RunHistorySummaryDto, String>(UiText.text("DATE"));
        dateCol.setCellValueFactory(new PropertyValueFactory<RunHistorySummaryDto, String>("date"));

        TableColumn<RunHistorySummaryDto, String> srcCol =
            new TableColumn<RunHistorySummaryDto, String>(UiText.text("SOURCE"));
        srcCol.setCellValueFactory(new PropertyValueFactory<RunHistorySummaryDto, String>("sourceMode"));

        TableColumn<RunHistorySummaryDto, String> corrCol =
            new TableColumn<RunHistorySummaryDto, String>(UiText.text("CORRECTION"));
        corrCol.setCellValueFactory(new PropertyValueFactory<RunHistorySummaryDto, String>("correctionMode"));

        TableColumn<RunHistorySummaryDto, Double> pwrCol =
            new TableColumn<RunHistorySummaryDto, Double>(UiText.text("PEAK HP"));
        pwrCol.setCellValueFactory(new PropertyValueFactory<RunHistorySummaryDto, Double>("peakPowerHp"));

        TableColumn<RunHistorySummaryDto, Double> tqCol =
            new TableColumn<RunHistorySummaryDto, Double>(UiText.text("PEAK NM"));
        tqCol.setCellValueFactory(new PropertyValueFactory<RunHistorySummaryDto, Double>("peakTorqueNm"));

        table.getColumns().setAll(idCol, customerCol, dateCol, srcCol, corrCol, pwrCol, tqCol);
    }
}
