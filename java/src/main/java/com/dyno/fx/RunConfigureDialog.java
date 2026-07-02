package com.dyno.fx;

import com.dyno.presenter.ChartScaleSettings;
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
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
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
        ChartScaleSettings initialScale = seed.getScaleSettings();

        Dialog<RunConfiguration> dialog = new Dialog<RunConfiguration>();
        dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Configure Run");
        dialog.setHeaderText("Configure run and chart axes");

        TextField plateInput = new TextField(seed.getLicensePlate());
        plateInput.setPromptText("License plate");

        TextField customerNameInput = new TextField(seed.getCustomerName());
        customerNameInput.setPromptText("Customer name (optional)");

        TextField customerPhoneInput = new TextField(seed.getCustomerPhone());
        customerPhoneInput.setPromptText("Customer phone (optional)");

        TextField notesInput = new TextField(seed.getNotes());
        notesInput.setPromptText("Notes (optional)");

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

        Spinner<Double> rpmMaxInput = scaleSpinner(initialScale.getRpmMax(), 1000.0d, 12000.0d, 500.0d);
        Spinner<Double> rpmStepInput = scaleSpinner(initialScale.getRpmInterval(), 100.0d, 2000.0d, 100.0d);
        Spinner<Double> afrMaxInput = scaleSpinner(initialScale.getAfrMax(), 10.0d, 30.0d, 0.5d);
        Spinner<Double> afrStepInput = scaleSpinner(initialScale.getAfrInterval(), 0.1d, 5.0d, 0.1d);
        Spinner<Double> speedMaxInput = scaleSpinner(initialScale.getSpeedMax(), 50.0d, 400.0d, 10.0d);
        Spinner<Double> speedStepInput = scaleSpinner(initialScale.getSpeedInterval(), 5.0d, 100.0d, 5.0d);
        Spinner<Double> gridMaxInput = scaleSpinner(initialScale.getValueMax(), 50.0d, 1000.0d, 25.0d);
        Spinner<Double> gridStepInput = scaleSpinner(initialScale.getValueInterval(), 5.0d, 200.0d, 5.0d);

        Label validation = new Label();
        validation.setTextFill(Color.web("#FF6B6B"));

        GridPane grid = new GridPane();
        grid.setHgap(FxTheme.GAP_M);
        grid.setVgap(FxTheme.GAP_M);
        grid.addRow(0, new Label("License plate (required):"), plateInput);
        grid.addRow(1, new Label("Customer name:"), customerNameInput);
        grid.addRow(2, new Label("Customer phone:"), customerPhoneInput);
        grid.addRow(3, new Label("Notes:"), notesInput);
        grid.addRow(4, new Label("X axis:"), xAxisInput);
        grid.addRow(5, new Label("Y1 axis:"), y1Input);
        grid.addRow(6, new Label("Y2 axis:"), y2Input);
        grid.addRow(7, new Label("Y3 axis:"), y3Input);
        grid.addRow(8, new Label("RPM max / interval:"), pair(rpmMaxInput, rpmStepInput));
        grid.addRow(9, new Label("AFR max / interval:"), pair(afrMaxInput, afrStepInput));
        grid.addRow(10, new Label("Speed max / interval:"), pair(speedMaxInput, speedStepInput));
        grid.addRow(11, new Label("Grid max / interval:"), pair(gridMaxInput, gridStepInput));

        VBox content = new VBox(FxTheme.GAP_M, grid, validation);
        content.setPadding(new Insets(FxTheme.GAP_S, 0, 0, 0));
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
                    customerNameInput.getText().trim(),
                    customerPhoneInput.getText().trim(),
                    notesInput.getText().trim(),
                    new RunAxisSelection(
                        xAxisInput.getValue(),
                        y1Input.getValue(),
                        y2Input.getValue(),
                        y3Input.getValue()
                    ),
                    scaleSettings(
                        rpmMaxInput, rpmStepInput,
                        afrMaxInput, afrStepInput,
                        speedMaxInput, speedStepInput,
                        gridMaxInput, gridStepInput
                    )
                );
            }
            return null;
        });
        return dialog.showAndWait();
    }

    static Optional<ChartScaleSettings> showScaleSettings(Window owner, ChartScaleSettings initialSettings) {
        ChartScaleSettings seed = initialSettings == null ? ChartScaleSettings.defaults() : initialSettings;
        Dialog<ChartScaleSettings> dialog = new Dialog<ChartScaleSettings>();
        dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Chart Scale");
        dialog.setHeaderText("Manual chart grid scale");

        Spinner<Double> rpmMaxInput = scaleSpinner(seed.getRpmMax(), 1000.0d, 12000.0d, 500.0d);
        Spinner<Double> rpmStepInput = scaleSpinner(seed.getRpmInterval(), 100.0d, 2000.0d, 100.0d);
        Spinner<Double> afrMaxInput = scaleSpinner(seed.getAfrMax(), 10.0d, 30.0d, 0.5d);
        Spinner<Double> afrStepInput = scaleSpinner(seed.getAfrInterval(), 0.1d, 5.0d, 0.1d);
        Spinner<Double> speedMaxInput = scaleSpinner(seed.getSpeedMax(), 50.0d, 400.0d, 10.0d);
        Spinner<Double> speedStepInput = scaleSpinner(seed.getSpeedInterval(), 5.0d, 100.0d, 5.0d);
        Spinner<Double> gridMaxInput = scaleSpinner(seed.getValueMax(), 50.0d, 1000.0d, 25.0d);
        Spinner<Double> gridStepInput = scaleSpinner(seed.getValueInterval(), 5.0d, 200.0d, 5.0d);

        GridPane grid = new GridPane();
        grid.setHgap(FxTheme.GAP_M);
        grid.setVgap(FxTheme.GAP_M);
        grid.addRow(0, new Label("RPM max / interval:"), pair(rpmMaxInput, rpmStepInput));
        grid.addRow(1, new Label("AFR max / interval:"), pair(afrMaxInput, afrStepInput));
        grid.addRow(2, new Label("Speed max / interval:"), pair(speedMaxInput, speedStepInput));
        grid.addRow(3, new Label("Grid max / interval:"), pair(gridMaxInput, gridStepInput));
        VBox content = new VBox(FxTheme.GAP_M, grid);
        content.setPadding(new Insets(FxTheme.GAP_S, 0, 0, 0));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        dialog.setResultConverter(button -> button == ButtonType.OK
            ? scaleSettings(rpmMaxInput, rpmStepInput, afrMaxInput, afrStepInput,
                speedMaxInput, speedStepInput, gridMaxInput, gridStepInput)
            : null);
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

    private static Spinner<Double> scaleSpinner(double value, double min, double max, double step) {
        Spinner<Double> spinner = new Spinner<Double>();
        spinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(min, max, value, step));
        spinner.setEditable(true);
        spinner.setPrefWidth(100);
        return spinner;
    }

    private static HBox pair(Spinner<Double> maxInput, Spinner<Double> intervalInput) {
        HBox box = new HBox(FxTheme.GAP_S, maxInput, intervalInput);
        return box;
    }

    private static ChartScaleSettings scaleSettings(
        Spinner<Double> rpmMaxInput,
        Spinner<Double> rpmStepInput,
        Spinner<Double> afrMaxInput,
        Spinner<Double> afrStepInput,
        Spinner<Double> speedMaxInput,
        Spinner<Double> speedStepInput,
        Spinner<Double> gridMaxInput,
        Spinner<Double> gridStepInput
    ) {
        return new ChartScaleSettings(
            rpmMaxInput.getValue().doubleValue(),
            rpmStepInput.getValue().doubleValue(),
            afrMaxInput.getValue().doubleValue(),
            afrStepInput.getValue().doubleValue(),
            speedMaxInput.getValue().doubleValue(),
            speedStepInput.getValue().doubleValue(),
            gridMaxInput.getValue().doubleValue(),
            gridStepInput.getValue().doubleValue()
        );
    }
}
