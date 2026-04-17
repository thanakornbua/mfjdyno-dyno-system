package com.dyno.operator.config.view;

import com.dyno.operator.config.model.EngineEdgeMode;
import com.dyno.operator.config.viewmodel.Esp32DaqConfigViewModel;
import java.util.Objects;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Lightweight JavaFX screen for editing ESP32 DAQ parameters through the Rust backend API.
 *
 * <p>This class is intentionally code-first so it can drop into an existing JavaFX shell
 * without adding new FXML/controller infrastructure. The backend remains the source of truth:
 * this screen surfaces client-side validation for operator guidance only.</p>
 */
public final class Esp32DaqConfigView extends BorderPane {
    public static final String ROUTE = "config/esp32-daq";

    private final Esp32DaqConfigViewModel viewModel;

    public Esp32DaqConfigView(Esp32DaqConfigViewModel viewModel) {
        this.viewModel = Objects.requireNonNull(viewModel, "viewModel");
        getStyleClass().add("esp32-daq-config-view");
        setPadding(new Insets(20));
        setPrefSize(1080, 780);

        setTop(buildHeader());
        setCenter(buildBody());
        setBottom(buildFooter());
    }

    public static Parent createRoot(Esp32DaqConfigViewModel viewModel) {
        return new Esp32DaqConfigView(viewModel);
    }

    /**
     * Call this from the app's navigator when the screen becomes visible.
     */
    public void onNavigatedTo() {
        viewModel.loadCurrentConfig();
    }

    private VBox buildHeader() {
        Label eyebrow = new Label("ESP32 DAQ Configuration");
        eyebrow.setStyle(
            "-fx-font-size: 11px;"
                + "-fx-font-weight: 700;"
                + "-fx-text-fill: #7a6b57;"
                + "-fx-letter-spacing: 0.8px;"
        );

        Label title = new Label("DAQ Parameters");
        title.setStyle(
            "-fx-font-size: 28px;"
                + "-fx-font-weight: 700;"
                + "-fx-text-fill: #1f1b18;"
        );

        Label description = new Label(
            "Configure ESP32 acquisition settings for engine pulse, encoder, CAN, and UART telemetry. "
                + "Changes are validated locally for operator guidance and then confirmed by the backend."
        );
        description.setWrapText(true);
        description.setStyle(
            "-fx-font-size: 13px;"
                + "-fx-text-fill: #5f5851;"
        );

        Button loadButton = new Button("Load current config");
        loadButton.setDefaultButton(false);
        loadButton.disableProperty().bind(viewModel.busyProperty());
        loadButton.setOnAction(event -> viewModel.loadCurrentConfig());

        Button resetButton = new Button("Reset to current device config");
        resetButton.disableProperty().bind(viewModel.busyProperty().or(viewModel.hasCurrentConfigProperty().not()));
        resetButton.setOnAction(event -> viewModel.resetToCurrentConfig());

        Button applyButton = new Button("Apply config");
        applyButton.setDefaultButton(true);
        applyButton.disableProperty().bind(viewModel.busyProperty().or(viewModel.canSubmitProperty().not()));
        applyButton.setOnAction(event -> viewModel.submitChanges());
        applyButton.setStyle(
            "-fx-background-color: #bc5a45;"
                + "-fx-text-fill: white;"
                + "-fx-font-weight: 700;"
        );

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox actions = new HBox(10, loadButton, resetButton, spacer, applyButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(8, eyebrow, title, description, actions);
        header.setPadding(new Insets(0, 0, 18, 0));
        return header;
    }

    private Parent buildBody() {
        VBox formSections = new VBox(
            16,
            buildEnginePulseSection(),
            buildEncoderSection(),
            buildCanSection(),
            buildUartSection()
        );

        VBox rightRail = new VBox(16, buildStatusCard(), buildValidationCard());
        rightRail.setPrefWidth(320);
        rightRail.setMinWidth(280);

        HBox contentRow = new HBox(18, buildScrollWrapper(formSections), rightRail);
        HBox.setHgrow(contentRow.getChildren().get(0), Priority.ALWAYS);

        StackPane root = new StackPane(contentRow);
        root.disableProperty().bind(viewModel.busyProperty());
        return root;
    }

    private ScrollPane buildScrollWrapper(VBox formSections) {
        ScrollPane scrollPane = new ScrollPane(formSections);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setStyle("-fx-background-color: transparent;");
        HBox.setHgrow(scrollPane, Priority.ALWAYS);
        return scrollPane;
    }

    private VBox buildEnginePulseSection() {
        GridPane grid = sectionGrid();
        addRow(grid, 0, "Pulse pin", numericField(viewModel.enginePulsePinProperty(), "GPIO pin"));
        addRow(grid, 1, "Pulses / rev", numericField(viewModel.enginePulsesPerRevProperty(), "e.g. 1.0"));

        ComboBox<EngineEdgeMode> edgeModeBox = new ComboBox<>();
        edgeModeBox.getItems().setAll(EngineEdgeMode.values());
        edgeModeBox.valueProperty().bindBidirectional(viewModel.engineEdgeModeProperty());
        edgeModeBox.setMaxWidth(Double.MAX_VALUE);
        addRow(grid, 2, "Edge mode", edgeModeBox);

        return sectionCard(
            "Engine Pulse",
            "Ignition-pulse interpretation for crank RPM acquisition.",
            grid
        );
    }

    private VBox buildEncoderSection() {
        GridPane grid = sectionGrid();
        addRow(grid, 0, "Encoder pin", numericField(viewModel.encoderPinProperty(), "GPIO pin"));
        addRow(grid, 1, "Encoder PPR", numericField(viewModel.encoderPprProperty(), "pulses per revolution"));

        return sectionCard(
            "Encoder",
            "Roller encoder input and pulse scaling for roller RPM acquisition.",
            grid
        );
    }

    private VBox buildCanSection() {
        GridPane grid = sectionGrid();
        addRow(grid, 0, "CAN RX pin", numericField(viewModel.canRxPinProperty(), "GPIO pin"));
        addRow(grid, 1, "CAN TX pin", numericField(viewModel.canTxPinProperty(), "GPIO pin"));
        addRow(grid, 2, "CAN bitrate", numericField(viewModel.canBitrateProperty(), "500000"));

        return sectionCard(
            "CAN",
            "ESP32-side CAN/TWAI settings used for AFR and related device data capture.",
            grid
        );
    }

    private VBox buildUartSection() {
        GridPane grid = sectionGrid();
        addRow(grid, 0, "UART TX pin", numericField(viewModel.uartTxPinProperty(), "GPIO pin"));
        addRow(grid, 1, "UART RX pin", numericField(viewModel.uartRxPinProperty(), "GPIO pin"));
        addRow(grid, 2, "UART baud", numericField(viewModel.uartBaudProperty(), "921600"));
        addRow(grid, 3, "Telemetry rate", numericField(viewModel.telemetryRateHzProperty(), "Hz"));

        return sectionCard(
            "UART / Telemetry",
            "Backend transport and frame-rate settings. The backend still performs authoritative validation before apply.",
            grid
        );
    }

    private VBox buildStatusCard() {
        Label heading = new Label("Apply Status");
        heading.setStyle("-fx-font-size: 16px; -fx-font-weight: 700; -fx-text-fill: #1f1b18;");

        Label badge = new Label();
        badge.textProperty().bind(Bindings.createStringBinding(
            () -> switch (viewModel.applyStatusProperty().get()) {
                case IDLE -> "Idle";
                case LOADING -> "Loading";
                case READY -> "Ready";
                case EDITING -> "Editing";
                case VALIDATION_ERROR -> "Validation error";
                case SUBMITTING -> "Applying";
                case APPLIED -> "Applied";
                case ERROR -> "Error";
            },
            viewModel.applyStatusProperty()
        ));
        styleStatusBadge(badge, viewModel.applyStatusProperty().get());
        viewModel.applyStatusProperty().addListener((obs, oldValue, newValue) -> styleStatusBadge(badge, newValue));

        Label detail = new Label();
        detail.textProperty().bind(viewModel.statusMessageProperty());
        detail.setWrapText(true);
        detail.setStyle("-fx-font-size: 13px; -fx-text-fill: #4e4740;");

        Label dirtyState = new Label();
        dirtyState.textProperty().bind(Bindings.when(viewModel.dirtyProperty())
            .then("Draft has unsaved edits.")
            .otherwise("Draft matches the last loaded backend config."));
        dirtyState.setStyle("-fx-font-size: 12px; -fx-text-fill: #7a6b57;");

        VBox card = new VBox(10, heading, badge, detail, new Separator(), dirtyState);
        card.setPadding(new Insets(16));
        card.setStyle(cardStyle());
        return card;
    }

    private VBox buildValidationCard() {
        Label heading = new Label("Validation");
        heading.setStyle("-fx-font-size: 16px; -fx-font-weight: 700; -fx-text-fill: #1f1b18;");

        Label errorsHeading = new Label("Errors");
        errorsHeading.setStyle("-fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: #9d3d2c;");

        ListView<String> errorsList = new ListView<>();
        errorsList.itemsProperty().bind(viewModel.validationErrorsProperty());
        errorsList.setPlaceholder(new Label("No validation errors."));
        errorsList.setPrefHeight(120);
        errorsList.visibleProperty().bind(Bindings.size(viewModel.validationErrorsProperty()).greaterThan(0));
        errorsList.managedProperty().bind(errorsList.visibleProperty());

        Label warningsHeading = new Label("Warnings");
        warningsHeading.setStyle("-fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: #9c6a1a;");

        ListView<String> warningsList = new ListView<>();
        warningsList.itemsProperty().bind(viewModel.validationWarningsProperty());
        warningsList.setPlaceholder(new Label("No validation warnings."));
        warningsList.setPrefHeight(140);
        warningsList.visibleProperty().bind(Bindings.size(viewModel.validationWarningsProperty()).greaterThan(0));
        warningsList.managedProperty().bind(warningsList.visibleProperty());

        Label authorityNote = new Label(
            "Frontend checks are advisory. The Rust backend remains the authoritative validator before config apply."
        );
        authorityNote.setWrapText(true);
        authorityNote.setStyle("-fx-font-size: 12px; -fx-text-fill: #7a6b57;");

        VBox card = new VBox(
            10,
            heading,
            errorsHeading,
            errorsList,
            warningsHeading,
            warningsList,
            new Separator(),
            authorityNote
        );
        card.setPadding(new Insets(16));
        card.setStyle(cardStyle());
        return card;
    }

    private HBox buildFooter() {
        Label note = new Label(
            "Apply changes only when the backend is reachable. Live transport changes such as UART reassignment may still be rejected by the backend."
        );
        note.setWrapText(true);
        note.setStyle("-fx-font-size: 12px; -fx-text-fill: #7a6b57;");

        HBox footer = new HBox(note);
        footer.setPadding(new Insets(18, 0, 0, 0));
        return footer;
    }

    private VBox sectionCard(String title, String description, GridPane content) {
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 17px; -fx-font-weight: 700; -fx-text-fill: #1f1b18;");

        Label descriptionLabel = new Label(description);
        descriptionLabel.setWrapText(true);
        descriptionLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #6b645d;");

        VBox box = new VBox(12, titleLabel, descriptionLabel, content);
        box.setPadding(new Insets(16));
        box.setStyle(cardStyle());
        return box;
    }

    private GridPane sectionGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(10);

        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setMinWidth(140);
        labelCol.setPrefWidth(160);

        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        fieldCol.setFillWidth(true);

        grid.getColumnConstraints().addAll(labelCol, fieldCol);
        return grid;
    }

    private void addRow(GridPane grid, int rowIndex, String labelText, javafx.scene.Node field) {
        Label label = new Label(labelText);
        label.setStyle("-fx-font-size: 13px; -fx-font-weight: 600; -fx-text-fill: #3a342f;");
        GridPane.setHgrow(field, Priority.ALWAYS);
        grid.add(label, 0, rowIndex);
        grid.add(field, 1, rowIndex);
    }

    private TextField numericField(javafx.beans.property.StringProperty property, String promptText) {
        TextField field = new TextField();
        field.textProperty().bindBidirectional(property);
        field.setPromptText(promptText);
        field.setMaxWidth(Double.MAX_VALUE);
        return field;
    }

    private void styleStatusBadge(Label badge, Esp32DaqConfigViewModel.ApplyStatus status) {
        String style = switch (status) {
            case IDLE, READY -> badgeStyle("#ece5dc", "#4e4740");
            case EDITING -> badgeStyle("#f6ecd7", "#8a6022");
            case LOADING, SUBMITTING -> badgeStyle("#dde7f4", "#375b88");
            case APPLIED -> badgeStyle("#dcecdf", "#2c6a3b");
            case VALIDATION_ERROR, ERROR -> badgeStyle("#f6dbd6", "#9d3d2c");
        };
        badge.setStyle(style);
        badge.setPadding(new Insets(6, 10, 6, 10));
    }

    private String badgeStyle(String background, String foreground) {
        return "-fx-background-color: " + background + ";"
            + "-fx-background-radius: 999;"
            + "-fx-font-size: 12px;"
            + "-fx-font-weight: 700;"
            + "-fx-text-fill: " + foreground + ";";
    }

    private String cardStyle() {
        return "-fx-background-color: #fbf7f2;"
            + "-fx-background-radius: 14;"
            + "-fx-border-color: #e7ddd2;"
            + "-fx-border-radius: 14;";
    }
}
