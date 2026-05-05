package com.dyno.fx;

import com.dyno.export.ExportFormat;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Modal dialog for selecting export formats and output directory.
 *
 * All four formats are checked by default. The operator can uncheck any and
 * change the output folder before confirming.
 */
public final class ExportDialog extends Dialog<ExportDialog.Result> {
    private final CheckBox pdfCheck  = new CheckBox(UiText.text("PDF Report"));
    private final CheckBox pngCheck  = new CheckBox(UiText.text("Chart Image (PNG)"));
    private final CheckBox csvCheck  = new CheckBox(UiText.text("Frame Data (CSV)"));
    private final CheckBox jsonCheck = new CheckBox(UiText.text("Run Data (JSON)"));

    private final TextField dirField = new TextField();
    private Path selectedDir;

    private final ButtonType exportType =
        new ButtonType(UiText.text("EXPORT"), ButtonBar.ButtonData.OK_DONE);

    public ExportDialog(Stage owner, String contextDescription) {
        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        setTitle(UiText.text("Export Run Data"));

        // Default output directory: user home
        selectedDir = Paths.get(System.getProperty("user.home"));
        dirField.setText(selectedDir.toString());
        dirField.setEditable(false);
        dirField.setMinWidth(300);

        // All formats on by default
        pdfCheck.setSelected(true);
        pngCheck.setSelected(true);
        csvCheck.setSelected(true);
        jsonCheck.setSelected(true);

        DialogPane pane = getDialogPane();
        pane.getButtonTypes().addAll(exportType, ButtonType.CANCEL);
        pane.setContent(buildContent(contextDescription));

        // Disable EXPORT if no formats are checked or no directory set
        Node exportButton = pane.lookupButton(exportType);
        if (exportButton != null) {
            updateExportEnabled(exportButton);
            pdfCheck.selectedProperty().addListener((o, ov, nv) -> updateExportEnabled(exportButton));
            pngCheck.selectedProperty().addListener((o, ov, nv) -> updateExportEnabled(exportButton));
            csvCheck.selectedProperty().addListener((o, ov, nv) -> updateExportEnabled(exportButton));
            jsonCheck.selectedProperty().addListener((o, ov, nv) -> updateExportEnabled(exportButton));
        }

        setResultConverter(button -> {
            if (button != exportType) {
                return null;
            }
            Set<ExportFormat> formats = EnumSet.noneOf(ExportFormat.class);
            if (pdfCheck.isSelected())  formats.add(ExportFormat.PDF);
            if (pngCheck.isSelected())  formats.add(ExportFormat.PNG);
            if (csvCheck.isSelected())  formats.add(ExportFormat.CSV);
            if (jsonCheck.isSelected()) formats.add(ExportFormat.JSON);
            if (formats.isEmpty() || selectedDir == null) {
                return null;
            }
            return new Result(formats, selectedDir);
        });
    }

    /**
     * Shows the dialog and returns the confirmed Result, or null if cancelled.
     */
    public static Result show(Stage owner, String contextDescription) {
        ExportDialog dialog = new ExportDialog(owner, contextDescription);
        Optional<Result> result = dialog.showAndWait();
        return result.orElse(null);
    }

    private VBox buildContent(String contextDescription) {
        VBox box = new VBox(14);
        box.setPadding(new Insets(12));

        Label title = new Label(UiText.text("Export Run Data"));
        title.setStyle("-fx-font-size: 17px; -fx-font-weight: bold;");

        Label contextLabel = new Label(contextDescription);
        contextLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #5577AA;");
        contextLabel.setWrapText(true);

        Label formatsTitle = new Label(UiText.text("Export formats:"));
        formatsTitle.setStyle("-fx-font-weight: bold;");

        VBox checksBox = new VBox(7, pdfCheck, pngCheck, csvCheck, jsonCheck);
        checksBox.setPadding(new Insets(0, 0, 0, 8));

        Label dirTitle = new Label(UiText.text("Output folder:"));
        dirTitle.setStyle("-fx-font-weight: bold;");

        Button browseButton = new Button(UiText.text("Browse..."));
        browseButton.setOnAction(event -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(UiText.text("Select Output Folder"));
            if (selectedDir != null) {
                File initDir = selectedDir.toFile();
                if (initDir.isDirectory()) {
                    chooser.setInitialDirectory(initDir);
                }
            }
            // Use the dialog's owner stage to parent the chooser
            File chosen = chooser.showDialog(getOwner());
            if (chosen != null) {
                selectedDir = chosen.toPath();
                dirField.setText(selectedDir.toString());
            }
        });

        HBox dirRow = new HBox(8, dirField, browseButton);
        HBox.setHgrow(dirField, javafx.scene.layout.Priority.ALWAYS);
        dirRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        box.getChildren().addAll(
            title,
            contextLabel,
            formatsTitle,
            checksBox,
            dirTitle,
            dirRow
        );
        return box;
    }

    private void updateExportEnabled(Node button) {
        boolean anyFormat = pdfCheck.isSelected() || pngCheck.isSelected()
            || csvCheck.isSelected() || jsonCheck.isSelected();
        button.setDisable(!anyFormat);
    }

    // =========================================================================

    public static final class Result {
        private final Set<ExportFormat> formats;
        private final Path outputDir;

        public Result(Set<ExportFormat> formats, Path outputDir) {
            this.formats = formats;
            this.outputDir = outputDir;
        }

        public Set<ExportFormat> getFormats() {
            return formats;
        }

        public Path getOutputDir() {
            return outputDir;
        }
    }
}
