package com.dyno.fx;

import com.dyno.presenter.RunAxisSelection;
import com.dyno.presenter.RunChartAxis;
import com.dyno.presenter.RunConfiguration;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class RunConfigureDialog {
    private RunConfigureDialog() {
    }

    static Optional<RunConfiguration> show(Window owner, RunConfiguration initialConfiguration) {
        RunConfiguration seed = initialConfiguration == null
            ? RunConfiguration.defaults("")
            : initialConfiguration;
        RunAxisSelection initialAxes = seed.getAxisSelection();

        Dialog<RunConfiguration> dialog = new Dialog<RunConfiguration>();
        dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Configure Run");
        dialog.setHeaderText("Configure run and chart axes");

        TextField plateInput = new TextField(seed.getLicensePlate());
        plateInput.setPromptText("License plate");

        ComboBox<RunChartAxis> xAxisInput = new ComboBox<RunChartAxis>(
            FXCollections.observableArrayList(xAxisOptions()));
        xAxisInput.getSelectionModel().select(initialAxes.getXAxis());

        ComboBox<RunChartAxis> y1Input = new ComboBox<RunChartAxis>(
            FXCollections.observableArrayList(yAxisOptions()));
        y1Input.getSelectionModel().select(initialAxes.getY1Axis());

        ComboBox<RunChartAxis> y2Input = new ComboBox<RunChartAxis>(
            FXCollections.observableArrayList(yAxisOptions()));
        y2Input.getSelectionModel().select(initialAxes.getY2Axis());

        ComboBox<RunChartAxis> y3Input = new ComboBox<RunChartAxis>(
            FXCollections.observableArrayList(yAxisOptions()));
        y3Input.getSelectionModel().select(initialAxes.getY3Axis());

        Label validation = new Label();
        validation.setTextFill(Color.web("#FF6B6B"));

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.addRow(0, new Label("License plate (required):"), plateInput);
        grid.addRow(1, new Label("X axis:"), xAxisInput);
        grid.addRow(2, new Label("Y1 axis:"), y1Input);
        grid.addRow(3, new Label("Y2 axis:"), y2Input);
        grid.addRow(4, new Label("Y3 axis:"), y3Input);

        VBox content = new VBox(10, grid, validation);
        content.setPadding(new Insets(6, 0, 0, 0));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

        Node okButton = dialog.getDialogPane().lookupButton(ButtonType.OK);
        Runnable refreshValidation = new Runnable() {
            @Override
            public void run() {
                String message = validationMessage(
                    plateInput.getText(),
                    y1Input.getValue(),
                    y2Input.getValue(),
                    y3Input.getValue()
                );
                validation.setText(message == null ? "" : message);
                okButton.setDisable(message != null);
            }
        };

        plateInput.textProperty().addListener((obs, oldValue, newValue) -> refreshValidation.run());
        xAxisInput.valueProperty().addListener((obs, oldValue, newValue) -> refreshValidation.run());
        y1Input.valueProperty().addListener((obs, oldValue, newValue) -> refreshValidation.run());
        y2Input.valueProperty().addListener((obs, oldValue, newValue) -> refreshValidation.run());
        y3Input.valueProperty().addListener((obs, oldValue, newValue) -> refreshValidation.run());
        refreshValidation.run();

        dialog.setResultConverter(button -> {
            if (button == ButtonType.OK) {
                return new RunConfiguration(
                    plateInput.getText().trim(),
                    new RunAxisSelection(
                        xAxisInput.getValue(),
                        y1Input.getValue(),
                        y2Input.getValue(),
                        y3Input.getValue()
                    )
                );
            }
            return null;
        });
        return dialog.showAndWait();
    }

    private static String validationMessage(
        String plateText,
        RunChartAxis y1,
        RunChartAxis y2,
        RunChartAxis y3
    ) {
        if (plateText == null || plateText.trim().isEmpty()) {
            return "License plate is required.";
        }
        if (y1 == null || y2 == null || y3 == null) {
            return "Select Y1, Y2, and Y3 axes.";
        }
        if (y1 == y2 || y1 == y3 || y2 == y3) {
            return "Y axes must be distinct.";
        }
        return null;
    }

    private static List<RunChartAxis> xAxisOptions() {
        ArrayList<RunChartAxis> axes = new ArrayList<RunChartAxis>();
        for (RunChartAxis axis : RunChartAxis.values()) {
            if (axis.supportsXAxis()) {
                axes.add(axis);
            }
        }
        return axes;
    }

    private static List<RunChartAxis> yAxisOptions() {
        ArrayList<RunChartAxis> axes = new ArrayList<RunChartAxis>();
        for (RunChartAxis axis : RunChartAxis.values()) {
            if (axis.supportsYAxis()) {
                axes.add(axis);
            }
        }
        return axes;
    }
}
