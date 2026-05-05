package com.dyno.fx;

import com.dyno.history.CompareRunsResponseDto;
import com.dyno.history.ComparedRunDto;
import com.dyno.history.RunHistoryFrameDto;
import com.dyno.history.RunHistoryDetailDto;
import com.dyno.presenter.CompareDisplayMapper;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.Collections;
import java.util.List;

public final class CompareDataView extends Dialog<Void> {
    public CompareDataView(Stage owner, CompareRunsResponseDto response) {
        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        setTitle(UiText.text("COMPARE SUMMARY"));

        DialogPane pane = getDialogPane();
        pane.getButtonTypes().add(ButtonType.CLOSE);
        pane.setContent(buildContent(response));
    }

    public static void show(Stage owner, CompareRunsResponseDto response) {
        new CompareDataView(owner, response).showAndWait();
    }

    private ScrollPane buildContent(CompareRunsResponseDto response) {
        VBox box = new VBox(12);
        box.setPadding(new Insets(12));

        Label title = new Label(UiText.text("COMPARE SUMMARY"));
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        Label note = new Label(UiText.text("Summary values come from backend run metadata and stored frame series."));
        note.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);

        ColumnConstraints metricColumn = new ColumnConstraints();
        metricColumn.setHgrow(Priority.NEVER);
        metricColumn.setMinWidth(170);
        grid.getColumnConstraints().add(metricColumn);

        List<ComparedRunDto> runs = response == null || response.getRuns() == null
            ? Collections.<ComparedRunDto>emptyList()
            : response.getRuns();

        addHeader(grid, 0, 0, UiText.text("METRIC"));
        for (int index = 0; index < runs.size(); index++) {
            RunHistoryDetailDto run = runs.get(index).getRun();
            addHeader(grid, index + 1, 0, CompareDisplayMapper.runLabel(run));
        }

        addMetricRow(grid, 1, UiText.text("Peak Power"), runs, Metric.PEAK_POWER);
        addMetricRow(grid, 2, UiText.text("Peak Power RPM"), runs, Metric.PEAK_POWER_RPM);
        addMetricRow(grid, 3, UiText.text("Peak Torque"), runs, Metric.PEAK_TORQUE);
        addMetricRow(grid, 4, UiText.text("Peak Torque RPM"), runs, Metric.PEAK_TORQUE_RPM);
        addMetricRow(grid, 5, UiText.text("Peak Speed"), runs, Metric.PEAK_SPEED);
        addMetricRow(grid, 6, UiText.text("AFR At Peak"), runs, Metric.AFR_AT_PEAK);
        addMetricRow(grid, 7, UiText.text("Ambient"), runs, Metric.AMBIENT);

        box.getChildren().addAll(title, note, grid);

        ScrollPane scrollPane = new ScrollPane(box);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefViewportWidth(860);
        scrollPane.setPrefViewportHeight(420);
        return scrollPane;
    }

    private void addMetricRow(GridPane grid, int row, String label, List<ComparedRunDto> runs, Metric metric) {
        addCell(grid, 0, row, label, true);
        for (int index = 0; index < runs.size(); index++) {
            addCell(grid, index + 1, row, metricValue(runs.get(index), metric), false);
        }
    }

    private String metricValue(ComparedRunDto comparedRun, Metric metric) {
        RunHistoryDetailDto run = comparedRun.getRun();
        RunHistoryFrameDto peakFrame = CompareDisplayMapper.peakPowerFrame(comparedRun);
        switch (metric) {
            case PEAK_POWER:
                return CompareDisplayMapper.safeValue(run == null ? null : run.getPeakPowerHp(), "HP");
            case PEAK_POWER_RPM:
                return CompareDisplayMapper.safeValue(run == null ? null : run.getPeakPowerRpm(), "RPM");
            case PEAK_TORQUE:
                return CompareDisplayMapper.safeValue(run == null ? null : run.getPeakTorqueNm(), "Nm");
            case PEAK_TORQUE_RPM:
                return CompareDisplayMapper.safeValue(run == null ? null : run.getPeakTorqueRpm(), "RPM");
            case PEAK_SPEED:
                return CompareDisplayMapper.safeValue(
                    CompareDisplayMapper.frameValue(peakFrame, CompareDisplayMapper.Metric.SPEED),
                    "km/h"
                );
            case AFR_AT_PEAK:
                return CompareDisplayMapper.safeValue(
                    CompareDisplayMapper.frameValue(peakFrame, CompareDisplayMapper.Metric.AFR),
                    ""
                );
            case AMBIENT:
            default:
                return CompareDisplayMapper.ambientText(peakFrame);
        }
    }

    private void addHeader(GridPane grid, int column, int row, String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold;");
        label.setWrapText(true);
        grid.add(label, column, row);
    }

    private void addCell(GridPane grid, int column, int row, String text, boolean metric) {
        Label label = new Label(text);
        label.setWrapText(true);
        if (metric) {
            label.setStyle("-fx-font-weight: bold;");
        }
        grid.add(label, column, row);
    }

    private enum Metric {
        PEAK_POWER,
        PEAK_POWER_RPM,
        PEAK_TORQUE,
        PEAK_TORQUE_RPM,
        PEAK_SPEED,
        AFR_AT_PEAK,
        AMBIENT
    }
}
