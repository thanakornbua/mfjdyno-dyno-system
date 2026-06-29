package com.dyno.fx;

import com.dyno.calibration.CalibrationApiClient;
import com.dyno.calibration.CalibrationDraftValidator;
import com.dyno.calibration.CalibrationProfileDto;
import com.dyno.calibration.CalibrationProfileEventDto;
import com.dyno.calibration.CalibrationResponseDto;
import com.dyno.calibration.CalibrationUpsertRequestDto;
import com.dyno.calibration.CalibrationValidationDto;
import com.dyno.calibration.DuplicateCalibrationProfileRequestDto;
import com.dyno.presenter.OperatorViewModel;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

public final class CalibrationDialog extends Dialog<CalibrationDialog.Result> {
    private static final DateTimeFormatter EVENT_TIME_FORMAT = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss")
        .withLocale(Locale.US)
        .withZone(ZoneId.systemDefault());

    private final CalibrationApiClient client;
    private final Executor executor;
    private final ObservableList<CalibrationProfileDto> rows;
    private final ObservableList<CalibrationProfileEventDto> eventRows = FXCollections.observableArrayList();
    private final TableView<CalibrationProfileDto> table = new TableView<CalibrationProfileDto>();
    private final TableView<CalibrationProfileEventDto> eventTable = new TableView<CalibrationProfileEventDto>();
    private final ButtonType activateType = new ButtonType(UiText.text("ACTIVATE"), ButtonBar.ButtonData.OK_DONE);

    private final Button createNewButton = new Button(UiText.text("CREATE NEW"));
    private final Button saveButton = new Button();
    private final Button duplicateButton = new Button(UiText.text("DUPLICATE"));

    private final Label activeNameLabel = new Label();
    private final Label activeDetailsLabel = new Label();
    private final Label activeValidationLabel = new Label();
    private final Label formModeLabel = new Label();
    private final Label formStateLabel = new Label();
    private final Label formValidationLabel = new Label();
    private final Label warningLabel = new Label();
    private final Label errorLabel = new Label();
    private final Label runtimeNoteLabel = new Label();
    private final Label statusLabel = new Label();

    private final TextField nameInput = new TextField();
    private final TextField rollerDiameterInput = new TextField();
    private final TextField pulsesPerRevInput = new TextField();
    private final TextField inertiaInput = new TextField();
    private final TextField sampleWindowInput = new TextField();
    private final TextField enginePulsesHintInput = new TextField();
    private final TextField engineRpmScaleInput = new TextField();
    private final TextArea notesInput = new TextArea();
    private final CheckBox activateAfterSaveInput = new CheckBox(UiText.text("Activate after save"));

    private final Label lockStatusLabel = new Label();
    private final Button lockButton = new Button(UiText.text("LOCK"));
    private final Button unlockButton = new Button(UiText.text("UNLOCK"));
    private HBox lockBar;

    private CalibrationResponseDto activeResponse;
    private Result result;
    private boolean createMode;
    private boolean busy;
    private boolean calibrationLocked;
    private long eventsRequestVersion;

    public CalibrationDialog(
        Stage owner,
        CalibrationApiClient client,
        Executor executor,
        CalibrationResponseDto activeResponse,
        List<CalibrationProfileDto> profiles,
        boolean locked
    ) {
        this.client = client;
        this.executor = executor;
        this.activeResponse = activeResponse;
        this.calibrationLocked = locked;
        this.rows = FXCollections.observableArrayList(profiles == null ? Collections.<CalibrationProfileDto>emptyList() : profiles);
        this.createMode = this.rows.isEmpty();

        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        setTitle(UiText.text("CALIBRATION"));

        DialogPane pane = getDialogPane();
        pane.getButtonTypes().addAll(activateType, ButtonType.CLOSE);
        pane.setContent(buildContent());

        installProfileColumns();
        installEventColumns();
        installInteractions();
        table.setItems(rows);
        eventTable.setItems(eventRows);
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        if (!rows.isEmpty()) {
            selectProfileById(activeProfileId());
            if (table.getSelectionModel().getSelectedItem() == null) {
                table.getSelectionModel().select(0);
            }
            populateFormFromProfile(table.getSelectionModel().getSelectedItem(), false);
            loadEventsForProfile(table.getSelectionModel().getSelectedItem());
        } else {
            prepareCreateMode();
        }
        refreshView(null, null);

        setResultConverter(button -> result);
    }

    public static Result show(
        Stage owner,
        CalibrationApiClient client,
        Executor executor,
        CalibrationResponseDto activeResponse,
        List<CalibrationProfileDto> profiles,
        boolean locked
    ) {
        CalibrationDialog dialog = new CalibrationDialog(owner, client, executor, activeResponse, profiles, locked);
        Optional<Result> result = dialog.showAndWait();
        return result.orElse(null);
    }

    private Node buildContent() {
        Label title = new Label(UiText.text("CALIBRATION PROFILES"));
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        Label note = new Label(UiText.text("Inspect stored calibration profiles and manage create/edit/duplicate/activate actions."));
        note.setWrapText(true);

        lockBar = buildLockBar();
        VBox activeBox = titledCard(UiText.text("ACTIVE PROFILE"), activeNameLabel, activeDetailsLabel, activeValidationLabel);
        VBox formBox = buildFormBox();
        VBox auditBox = buildAuditBox();

        table.setMinWidth(920);
        table.setPrefHeight(220);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label(UiText.text("No calibration profiles found.")));

        runtimeNoteLabel.setWrapText(true);
        runtimeNoteLabel.setText(UiText.text(
            "Activation applies to future/live calculations immediately. Stored runs keep their original calibration snapshot."
        ));
        runtimeNoteLabel.setTextFill(FxTheme.TEXT_MUTED);

        statusLabel.setWrapText(true);
        statusLabel.setTextFill(FxTheme.TEXT_MUTED);

        HBox actions = new HBox(8, createNewButton, saveButton, duplicateButton);
        VBox box = new VBox(12, title, note, lockBar, activeBox, table, actions, formBox, auditBox, runtimeNoteLabel, statusLabel);
        box.setPadding(new Insets(12));
        VBox.setVgrow(table, Priority.ALWAYS);
        return box;
    }

    private HBox buildLockBar() {
        lockStatusLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        HBox bar = new HBox(12, lockStatusLabel, lockButton, unlockButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 10, 8, 10));
        bar.setStyle(FxTheme.cardStyle(FxTheme.SURFACE_ALT));
        return bar;
    }

    private VBox buildFormBox() {
        formModeLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        formModeLabel.setTextFill(FxTheme.TEXT_PRIMARY);
        formStateLabel.setWrapText(true);
        formValidationLabel.setWrapText(true);
        warningLabel.setWrapText(true);
        errorLabel.setWrapText(true);
        notesInput.setPrefRowCount(3);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.addRow(0, new Label(UiText.text("Profile name")), nameInput);
        grid.addRow(1, new Label(UiText.text("Roller diameter (m)")), rollerDiameterInput);
        grid.addRow(2, new Label(UiText.text("Pulses / rev")), pulsesPerRevInput);
        grid.addRow(3, new Label(UiText.text("Inertia (kg·m²)")), inertiaInput);
        grid.addRow(4, new Label(UiText.text("Sample window (ms)")), sampleWindowInput);
        grid.addRow(5, new Label(UiText.text("Engine pulses hint")), enginePulsesHintInput);
        grid.addRow(6, new Label(UiText.text("Engine RPM scale")), engineRpmScaleInput);
        grid.addRow(7, new Label(UiText.text("Notes")), notesInput);
        grid.add(activateAfterSaveInput, 1, 8);
        GridPane.setHgrow(nameInput, Priority.ALWAYS);
        GridPane.setHgrow(rollerDiameterInput, Priority.ALWAYS);
        GridPane.setHgrow(pulsesPerRevInput, Priority.ALWAYS);
        GridPane.setHgrow(inertiaInput, Priority.ALWAYS);
        GridPane.setHgrow(sampleWindowInput, Priority.ALWAYS);
        GridPane.setHgrow(enginePulsesHintInput, Priority.ALWAYS);
        GridPane.setHgrow(engineRpmScaleInput, Priority.ALWAYS);
        GridPane.setHgrow(notesInput, Priority.ALWAYS);

        return titledCard(
            UiText.text("PROFILE FORM"),
            formModeLabel,
            formStateLabel,
            grid,
            formValidationLabel,
            warningLabel,
            errorLabel
        );
    }

    private VBox buildAuditBox() {
        eventTable.setPrefHeight(180);
        eventTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        eventTable.setPlaceholder(new Label(UiText.text("Select a profile to load audit history.")));
        return titledCard(UiText.text("AUDIT HISTORY"), eventTable);
    }

    private VBox titledCard(String titleText, Node... nodes) {
        Label title = new Label(titleText);
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        title.setTextFill(FxTheme.TEXT_PRIMARY);

        VBox box = new VBox(6);
        box.getChildren().add(title);
        for (Node node : nodes) {
            if (node instanceof Label) {
                ((Label) node).setTextFill(FxTheme.TEXT_PRIMARY);
            }
            box.getChildren().add(node);
        }
        box.setPadding(new Insets(10));
        box.setStyle(FxTheme.cardStyle(FxTheme.SURFACE_ALT));
        return box;
    }

    private void installProfileColumns() {
        TableColumn<CalibrationProfileDto, String> nameColumn = new TableColumn<CalibrationProfileDto, String>(UiText.text("PROFILE"));
        nameColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(safeText(data.getValue().getName(), "—")));

        TableColumn<CalibrationProfileDto, String> activeColumn = new TableColumn<CalibrationProfileDto, String>(UiText.text("STATUS"));
        activeColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(Boolean.TRUE.equals(data.getValue().getActive())
            ? UiText.text("ACTIVE") : UiText.text("INACTIVE")));

        TableColumn<CalibrationProfileDto, String> diameterColumn = new TableColumn<CalibrationProfileDto, String>(UiText.text("ROLLER DIAMETER"));
        diameterColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(formatDouble(data.getValue().getRollerDiameterM(), 3, "m")));

        TableColumn<CalibrationProfileDto, String> pulsesColumn = new TableColumn<CalibrationProfileDto, String>(UiText.text("PULSES / REV"));
        pulsesColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(formatDouble(data.getValue().getEncoderPulsesPerRev(), 1, "")));

        TableColumn<CalibrationProfileDto, String> inertiaColumn = new TableColumn<CalibrationProfileDto, String>(UiText.text("INERTIA"));
        inertiaColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(formatDouble(data.getValue().getRollerInertiaKgM2(), 3, "kg·m²")));

        TableColumn<CalibrationProfileDto, String> windowColumn = new TableColumn<CalibrationProfileDto, String>(UiText.text("SAMPLE WINDOW"));
        windowColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(formatLong(data.getValue().getSampleWindowMs(), "ms")));

        table.getColumns().setAll(nameColumn, activeColumn, diameterColumn, pulsesColumn, inertiaColumn, windowColumn);
    }

    private void installEventColumns() {
        TableColumn<CalibrationProfileEventDto, String> timeColumn = new TableColumn<CalibrationProfileEventDto, String>(UiText.text("TIME"));
        timeColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(formatTimestamp(data.getValue().getCreatedAtMs())));

        TableColumn<CalibrationProfileEventDto, String> typeColumn = new TableColumn<CalibrationProfileEventDto, String>(UiText.text("EVENT"));
        typeColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(safeText(data.getValue().getEventType(), "—")));

        TableColumn<CalibrationProfileEventDto, String> summaryColumn = new TableColumn<CalibrationProfileEventDto, String>(UiText.text("SUMMARY"));
        summaryColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(safeText(data.getValue().getSummary(), "—")));

        eventTable.getColumns().setAll(timeColumn, typeColumn, summaryColumn);
    }

    private void installInteractions() {
        table.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null) {
                createMode = false;
                populateFormFromProfile(newValue, false);
                loadEventsForProfile(newValue);
            } else if (!createMode) {
                clearEventRows();
            }
            refreshView(null, null);
        });

        createNewButton.setOnAction(event -> {
            prepareCreateMode();
            refreshView(UiText.text("Creating a new calibration profile."), OperatorViewModel.Tone.NORMAL);
        });
        saveButton.setOnAction(event -> handleSave());
        duplicateButton.setOnAction(event -> handleDuplicate());

        lockButton.setOnAction(event -> handleLock());
        unlockButton.setOnAction(event -> handleUnlock());

        installFormRefresh(nameInput);
        installFormRefresh(rollerDiameterInput);
        installFormRefresh(pulsesPerRevInput);
        installFormRefresh(inertiaInput);
        installFormRefresh(sampleWindowInput);
        installFormRefresh(enginePulsesHintInput);
        installFormRefresh(engineRpmScaleInput);
        installFormRefresh(notesInput);
        activateAfterSaveInput.selectedProperty().addListener((obs, oldValue, newValue) -> refreshView(null, null));

        Node activateNode = getDialogPane().lookupButton(activateType);
        if (activateNode instanceof Button) {
            Button button = (Button) activateNode;
            button.addEventFilter(ActionEvent.ACTION, event -> {
                event.consume();
                handleActivate();
            });
        }
    }

    private void installFormRefresh(TextField input) {
        input.textProperty().addListener((obs, oldValue, newValue) -> refreshView(null, null));
    }

    private void installFormRefresh(TextArea input) {
        input.textProperty().addListener((obs, oldValue, newValue) -> refreshView(null, null));
    }

    private void handleLock() {
        PasswordField pwdField = new PasswordField();
        pwdField.setPromptText(UiText.text("Password"));
        ButtonType confirmType = new ButtonType(UiText.text("Confirm"), ButtonBar.ButtonData.OK_DONE);
        Dialog<String> pwdDialog = new Dialog<String>();
        pwdDialog.setTitle(UiText.text("Lock Calibration"));
        pwdDialog.setHeaderText(UiText.text("Enter password to lock calibration"));
        pwdDialog.initOwner(getOwner());
        pwdDialog.initModality(Modality.APPLICATION_MODAL);
        pwdDialog.getDialogPane().getButtonTypes().addAll(confirmType, ButtonType.CANCEL);
        pwdDialog.getDialogPane().setContent(new VBox(8, new Label(UiText.text("Password:")), pwdField));
        pwdDialog.setResultConverter(bt -> confirmType.equals(bt) ? pwdField.getText() : null);
        Optional<String> pwd = pwdDialog.showAndWait();
        if (!pwd.isPresent() || pwd.get().trim().isEmpty()) {
            return;
        }
        final String password = pwd.get().trim();
        setBusy(true, UiText.text("Locking calibration..."));
        CompletableFuture
            .supplyAsync(() -> {
                try {
                    client.lockCalibration(password);
                    return Boolean.TRUE;
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, executor)
            .thenAccept(ok -> Platform.runLater(() -> {
                calibrationLocked = true;
                setBusy(false, null);
                refreshView(UiText.text("Calibration locked."), OperatorViewModel.Tone.NORMAL);
            }))
            .exceptionally(err -> {
                Platform.runLater(() -> handleLockFailure(err));
                return null;
            });
    }

    private void handleUnlock() {
        PasswordField pwdField = new PasswordField();
        pwdField.setPromptText(UiText.text("Password"));
        ButtonType confirmType = new ButtonType(UiText.text("Confirm"), ButtonBar.ButtonData.OK_DONE);
        Dialog<String> pwdDialog = new Dialog<String>();
        pwdDialog.setTitle(UiText.text("Unlock Calibration"));
        pwdDialog.setHeaderText(UiText.text("Enter password to unlock calibration"));
        pwdDialog.initOwner(getOwner());
        pwdDialog.initModality(Modality.APPLICATION_MODAL);
        pwdDialog.getDialogPane().getButtonTypes().addAll(confirmType, ButtonType.CANCEL);
        pwdDialog.getDialogPane().setContent(new VBox(8, new Label(UiText.text("Password:")), pwdField));
        pwdDialog.setResultConverter(bt -> confirmType.equals(bt) ? pwdField.getText() : null);
        Optional<String> pwd = pwdDialog.showAndWait();
        if (!pwd.isPresent() || pwd.get().trim().isEmpty()) {
            return;
        }
        final String password = pwd.get().trim();
        setBusy(true, UiText.text("Unlocking calibration..."));
        CompletableFuture
            .supplyAsync(() -> {
                try {
                    client.unlockCalibration(password);
                    return Boolean.TRUE;
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, executor)
            .thenAccept(ok -> Platform.runLater(() -> {
                calibrationLocked = false;
                setBusy(false, null);
                refreshView(UiText.text("Calibration unlocked."), OperatorViewModel.Tone.NORMAL);
            }))
            .exceptionally(err -> {
                Platform.runLater(() -> handleLockFailure(err));
                return null;
            });
    }

    private void handleLockFailure(Throwable throwable) {
        setBusy(false, null);
        Throwable root = rootCause(throwable);
        if (root instanceof CalibrationApiClient.LockException) {
            int code = ((CalibrationApiClient.LockException) root).statusCode;
            String msg = code == 401 ? UiText.text("Wrong password.")
                : code == 423 ? UiText.text("Calibration is already in the requested lock state.")
                : UiText.text("Lock operation failed (HTTP ") + code + ").";
            OperatorViewModel.Tone tone = code == 423 ? OperatorViewModel.Tone.CAUTION : OperatorViewModel.Tone.ALERT;
            refreshView(msg, tone);
        } else if (root instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            refreshView(UiText.text("Lock operation interrupted."), OperatorViewModel.Tone.ALERT);
        } else {
            refreshView(UiText.text("Lock operation failed: ") + root.getMessage(), OperatorViewModel.Tone.ALERT);
        }
    }

    private void handleSave() {
        ParsedForm parsed = parseForm();
        if (!parsed.validation.isValid()) {
            refreshView(UiText.text("Resolve validation errors before saving."), OperatorViewModel.Tone.ALERT);
            return;
        }

        if (createMode) {
            submitMutation(
                UiText.text("Saving calibration profile..."),
                () -> {
                    CalibrationResponseDto response = client.createCalibrationProfile(parsed.request);
                    List<CalibrationProfileDto> profiles = client.listCalibrationProfiles();
                    List<CalibrationProfileEventDto> events = client.listCalibrationProfileEvents(response.getProfile().getProfileId().longValue());
                    return new MutationResult(response, profiles, events);
                },
                UiText.text("Saved calibration profile: ")
            );
            return;
        }

        CalibrationProfileDto selected = table.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getProfileId() == null) {
            refreshView(UiText.text("Select a calibration profile first."), OperatorViewModel.Tone.CAUTION);
            return;
        }

        submitMutation(
            UiText.text("Saving calibration profile..."),
            () -> {
                CalibrationResponseDto response = client.updateCalibrationProfile(selected.getProfileId().longValue(), parsed.request);
                List<CalibrationProfileDto> profiles = client.listCalibrationProfiles();
                List<CalibrationProfileEventDto> events = client.listCalibrationProfileEvents(response.getProfile().getProfileId().longValue());
                return new MutationResult(response, profiles, events);
            },
            UiText.text("Saved calibration profile: ")
        );
    }

    private void handleDuplicate() {
        CalibrationProfileDto selected = table.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getProfileId() == null) {
            refreshView(UiText.text("Select a calibration profile first."), OperatorViewModel.Tone.CAUTION);
            return;
        }

        String requestedName = normalizeOptionalText(nameInput.getText());
        submitMutation(
            UiText.text("Duplicating calibration profile..."),
            () -> {
                CalibrationResponseDto response = client.duplicateCalibrationProfile(
                    selected.getProfileId().longValue(),
                    new DuplicateCalibrationProfileRequestDto(requestedName, Boolean.valueOf(activateAfterSaveInput.isSelected()))
                );
                List<CalibrationProfileDto> profiles = client.listCalibrationProfiles();
                List<CalibrationProfileEventDto> events = client.listCalibrationProfileEvents(response.getProfile().getProfileId().longValue());
                return new MutationResult(response, profiles, events);
            },
            UiText.text("Duplicated calibration profile: ")
        );
    }

    private void handleActivate() {
        CalibrationProfileDto selected = table.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getProfileId() == null) {
            refreshView(UiText.text("Select a calibration profile first."), OperatorViewModel.Tone.CAUTION);
            return;
        }
        if (selected.getProfileId().equals(activeProfileId())) {
            refreshView(UiText.text("Selected profile is already active."), OperatorViewModel.Tone.UNAVAILABLE);
            return;
        }
        CalibrationValidationDto validation = validateSelection(selected);
        if (!validation.isValid()) {
            refreshView(UiText.text("Selected profile cannot be activated until validation errors are resolved."), OperatorViewModel.Tone.ALERT);
            return;
        }

        submitMutation(
            UiText.text("Activating calibration profile..."),
            () -> {
                CalibrationResponseDto response = client.activateCalibration(selected.getProfileId().longValue());
                List<CalibrationProfileDto> profiles = client.listCalibrationProfiles();
                List<CalibrationProfileEventDto> events = client.listCalibrationProfileEvents(response.getProfile().getProfileId().longValue());
                return new MutationResult(response, profiles, events);
            },
            UiText.text("Activated calibration: ")
        );
    }

    private void submitMutation(String pendingMessage, ThrowingSupplier<MutationResult> supplier, String successPrefix) {
        setBusy(true, pendingMessage);
        CompletableFuture
            .supplyAsync(() -> {
                try {
                    return supplier.get();
                } catch (Exception error) {
                    throw new CompletionException(error);
                }
            }, executor)
            .thenAccept(result -> Platform.runLater(() -> applyMutationResult(result, successPrefix)))
            .exceptionally(error -> {
                Platform.runLater(() -> handleAsyncFailure(error));
                return null;
            });
    }

    private void applyMutationResult(MutationResult mutation, String successPrefix) {
        setBusy(false, null);
        rows.setAll(mutation.profiles);
        eventRows.setAll(mutation.events);
        if (mutation.response.getProfile() != null && Boolean.TRUE.equals(mutation.response.getProfile().getActive())) {
            activeResponse = mutation.response;
        }
        CalibrationProfileDto profile = mutation.response.getProfile();
        if (profile != null && profile.getProfileId() != null) {
            selectProfileById(profile.getProfileId());
            populateFormFromProfile(findProfileById(profile.getProfileId()), false);
        }

        OperatorViewModel.Tone tone = toneForValidation(mutation.response.getValidation());
        String profileName = profile == null ? UiText.text("calibration") : safeText(profile.getName(), UiText.text("calibration"));
        String message = successPrefix + profileName;
        if (mutation.response.isActivated()) {
            message = message + UiText.text(". Future calculations now use this profile.");
        }
        refreshView(message, tone);
        result = new Result(mutation.response, message, tone);
    }

    private void handleAsyncFailure(Throwable throwable) {
        setBusy(false, null);
        Throwable root = rootCause(throwable);
        if (root instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            refreshView(UiText.text("Calibration request interrupted."), OperatorViewModel.Tone.ALERT);
            return;
        }
        String message = root instanceof IOException ? root.getMessage() : UiText.text("Calibration request failed: ") + root.getMessage();
        refreshView(message, OperatorViewModel.Tone.ALERT);
    }

    private void loadEventsForProfile(CalibrationProfileDto profile) {
        clearEventRows();
        if (profile == null || profile.getProfileId() == null) {
            return;
        }
        final long requestVersion = ++eventsRequestVersion;
        CompletableFuture
            .supplyAsync(() -> {
                try {
                    return client.listCalibrationProfileEvents(profile.getProfileId().longValue());
                } catch (Exception error) {
                    throw new CompletionException(error);
                }
            }, executor)
            .thenAccept(events -> Platform.runLater(() -> {
                if (requestVersion != eventsRequestVersion) {
                    return;
                }
                eventRows.setAll(events);
            }))
            .exceptionally(error -> {
                Platform.runLater(() -> {
                    if (requestVersion == eventsRequestVersion) {
                        statusLabel.setText(UiText.text("Failed to load audit history: ") + rootCause(error).getMessage());
                        statusLabel.setTextFill(FxTheme.ALERT);
                    }
                });
                return null;
            });
    }

    private void clearEventRows() {
        eventsRequestVersion += 1;
        eventRows.clear();
    }

    private void refreshView(String statusMessage, OperatorViewModel.Tone statusTone) {
        renderActiveProfile();
        renderFormState();
        renderFormValidation();
        renderStatus(statusMessage, statusTone);
        updateActionState();
    }

    private void renderActiveProfile() {
        CalibrationProfileDto profile = activeResponse == null ? null : activeResponse.getProfile();
        CalibrationValidationDto validation = activeResponse == null ? null : activeResponse.getValidation();

        activeNameLabel.setText(UiText.text("Name: ") + safeText(profile == null ? null : profile.getName(), "—"));
        activeDetailsLabel.setText(formatProfileDetails(profile));
        activeValidationLabel.setText(formatValidationSummary(validation));
        activeValidationLabel.setTextFill(colorForValidation(validation));
    }

    private void renderFormState() {
        CalibrationProfileDto selected = table.getSelectionModel().getSelectedItem();
        formModeLabel.setText(createMode ? UiText.text("Create calibration profile") : UiText.text("Edit calibration profile"));
        if (createMode) {
            formStateLabel.setText(UiText.text("A new profile will be created. It will only become active if 'Activate after save' is checked."));
            return;
        }
        if (selected == null) {
            formStateLabel.setText(UiText.text("Select a profile to edit or choose create new."));
            return;
        }
        String state = Boolean.TRUE.equals(selected.getActive()) ? UiText.text("ACTIVE") : UiText.text("INACTIVE");
        formStateLabel.setText(
            UiText.text("Editing profile: ") + safeText(selected.getName(), "—") + " (" + state + ")"
        );
    }

    private void renderFormValidation() {
        ParsedForm parsed = parseForm();
        CalibrationValidationDto validation = parsed.validation;
        formValidationLabel.setText(formatValidationSummary(validation));
        formValidationLabel.setTextFill(colorForValidation(validation));
        warningLabel.setText(formatMessages(UiText.text("Warnings"), validation.getWarnings()));
        warningLabel.setTextFill(validation.getWarnings().isEmpty() ? FxTheme.TEXT_MUTED : FxTheme.WARNING);
        errorLabel.setText(formatMessages(UiText.text("Errors"), validation.getErrors()));
        errorLabel.setTextFill(validation.getErrors().isEmpty() ? FxTheme.TEXT_MUTED : FxTheme.ALERT);
    }

    private void renderStatus(String statusMessage, OperatorViewModel.Tone statusTone) {
        if (statusMessage != null) {
            statusLabel.setText(statusMessage);
            statusLabel.setTextFill(FxTheme.toneColor(statusTone == null ? OperatorViewModel.Tone.NORMAL : statusTone));
            return;
        }
        if (busy) {
            statusLabel.setTextFill(FxTheme.TEXT_MUTED);
            return;
        }
        if (statusLabel.getText() == null || statusLabel.getText().trim().isEmpty()) {
            statusLabel.setText(UiText.text("Select a profile to inspect, edit, duplicate, or activate it."));
            statusLabel.setTextFill(FxTheme.TEXT_MUTED);
        }
    }

    private void updateActionState() {
        ParsedForm parsed = parseForm();
        boolean hasSelected = hasSelectedProfile();
        boolean formEditable = !busy && !calibrationLocked && (createMode || hasSelected);

        nameInput.setDisable(!formEditable);
        rollerDiameterInput.setDisable(!formEditable);
        pulsesPerRevInput.setDisable(!formEditable);
        inertiaInput.setDisable(!formEditable);
        sampleWindowInput.setDisable(!formEditable);
        enginePulsesHintInput.setDisable(!formEditable);
        engineRpmScaleInput.setDisable(!formEditable);
        notesInput.setDisable(!formEditable);
        activateAfterSaveInput.setDisable(!formEditable);

        createNewButton.setDisable(busy || calibrationLocked);
        duplicateButton.setDisable(busy || calibrationLocked || !hasSelected);
        saveButton.setText(createMode ? UiText.text("CREATE PROFILE") : UiText.text("SAVE CHANGES"));
        saveButton.setDisable(busy || calibrationLocked || (!createMode && !hasSelected) || !parsed.validation.isValid());

        Node activateNode = getDialogPane().lookupButton(activateType);
        if (activateNode instanceof Button) {
            Button button = (Button) activateNode;
            CalibrationProfileDto selected = table.getSelectionModel().getSelectedItem();
            CalibrationValidationDto selectedValidation = validateSelection(selected);
            boolean sameAsActive = selected != null && selected.getProfileId() != null && selected.getProfileId().equals(activeProfileId());
            button.setDisable(busy || calibrationLocked || selected == null || selected.getProfileId() == null || sameAsActive || !selectedValidation.isValid());
        }

        lockStatusLabel.setText(calibrationLocked ? "🔒 " + UiText.text("LOCKED") : "🔓 " + UiText.text("UNLOCKED"));
        lockStatusLabel.setTextFill(calibrationLocked ? FxTheme.ALERT : FxTheme.SUCCESS);
        lockButton.setVisible(!calibrationLocked);
        lockButton.setManaged(!calibrationLocked);
        unlockButton.setVisible(calibrationLocked);
        unlockButton.setManaged(calibrationLocked);
        lockButton.setDisable(busy);
        unlockButton.setDisable(busy);
        lockBar.setStyle(
            "-fx-background-color: " + toRgbaCss(calibrationLocked ? FxTheme.ALERT : FxTheme.SUCCESS, 0.15) + ";" +
            "-fx-background-radius: 10;" +
            "-fx-border-color: " + FxTheme.toCss(calibrationLocked ? FxTheme.ALERT : FxTheme.SUCCESS) + ";" +
            "-fx-border-width: 1;" +
            "-fx-border-radius: 10;"
        );
    }

    private ParsedForm parseForm() {
        String name = normalizeText(nameInput.getText());
        Double rollerDiameter = parseRequiredDouble(rollerDiameterInput.getText(), "roller_diameter_m");
        Double pulsesPerRev = parseRequiredDouble(pulsesPerRevInput.getText(), "encoder_pulses_per_rev");
        Double inertia = parseRequiredDouble(inertiaInput.getText(), "roller_inertia_kg_m2");
        Long sampleWindow = parseRequiredLong(sampleWindowInput.getText(), "sample_window_ms");
        Double enginePulsesHint = parseOptionalDouble(enginePulsesHintInput.getText(), "engine_pulses_per_rev_hint");
        Double engineRpmScale = parseOptionalDouble(engineRpmScaleInput.getText(), "engine_rpm_scale");
        String notes = normalizeOptionalText(notesInput.getText());

        CalibrationValidationDto parseValidation = CalibrationValidationDto.of(true, Collections.<String>emptyList(), Collections.<String>emptyList());
        CalibrationUpsertRequestDto request = new CalibrationUpsertRequestDto(
            name,
            rollerDiameter,
            pulsesPerRev,
            inertia,
            sampleWindow,
            enginePulsesHint,
            engineRpmScale,
            notes,
            Boolean.valueOf(activateAfterSaveInput.isSelected())
        );
        CalibrationValidationDto validation = CalibrationDraftValidator.validate(request);
        return new ParsedForm(request, mergeValidation(parseValidation, validation));
    }

    private CalibrationValidationDto validateSelection(CalibrationProfileDto profile) {
        if (profile == null) {
            return CalibrationValidationDto.of(false, Collections.<String>emptyList(), Collections.singletonList(UiText.text("No profile selected.")));
        }
        if (profile.getProfileId() != null && profile.getProfileId().equals(activeProfileId()) && activeResponse != null && activeResponse.getValidation() != null) {
            return activeResponse.getValidation();
        }
        CalibrationUpsertRequestDto request = new CalibrationUpsertRequestDto(
            profile.getName(),
            profile.getRollerDiameterM(),
            profile.getEncoderPulsesPerRev(),
            profile.getRollerInertiaKgM2(),
            profile.getSampleWindowMs(),
            profile.getEnginePulsesPerRevHint(),
            profile.getEngineRpmScale(),
            profile.getNotes(),
            Boolean.FALSE
        );
        return CalibrationDraftValidator.validate(request);
    }

    private CalibrationValidationDto mergeValidation(CalibrationValidationDto parseValidation, CalibrationValidationDto draftValidation) {
        if (!parseValidation.isValid()) {
            return parseValidation;
        }
        return draftValidation;
    }

    private void prepareCreateMode() {
        createMode = true;
        table.getSelectionModel().clearSelection();
        nameInput.setText("");
        rollerDiameterInput.setText("");
        pulsesPerRevInput.setText("");
        inertiaInput.setText("");
        sampleWindowInput.setText("");
        enginePulsesHintInput.setText("");
        engineRpmScaleInput.setText("");
        notesInput.setText("");
        activateAfterSaveInput.setSelected(true);
        clearEventRows();
    }

    private void populateFormFromProfile(CalibrationProfileDto profile, boolean forceCreateMode) {
        createMode = forceCreateMode;
        if (profile == null) {
            return;
        }
        nameInput.setText(safeText(profile.getName(), ""));
        rollerDiameterInput.setText(numberText(profile.getRollerDiameterM()));
        pulsesPerRevInput.setText(numberText(profile.getEncoderPulsesPerRev()));
        inertiaInput.setText(numberText(profile.getRollerInertiaKgM2()));
        sampleWindowInput.setText(profile.getSampleWindowMs() == null ? "" : String.valueOf(profile.getSampleWindowMs().longValue()));
        enginePulsesHintInput.setText(numberText(profile.getEnginePulsesPerRevHint()));
        engineRpmScaleInput.setText(numberText(profile.getEngineRpmScale()));
        notesInput.setText(profile.getNotes() == null ? "" : profile.getNotes());
        activateAfterSaveInput.setSelected(Boolean.TRUE.equals(profile.getActive()));
    }

    private CalibrationProfileDto findProfileById(Long profileId) {
        if (profileId == null) {
            return null;
        }
        for (CalibrationProfileDto row : rows) {
            if (profileId.equals(row.getProfileId())) {
                return row;
            }
        }
        return null;
    }

    private boolean hasSelectedProfile() {
        CalibrationProfileDto selected = table.getSelectionModel().getSelectedItem();
        return selected != null && selected.getProfileId() != null;
    }

    private Long activeProfileId() {
        return activeResponse != null && activeResponse.getProfile() != null ? activeResponse.getProfile().getProfileId() : null;
    }

    private void selectProfileById(Long profileId) {
        if (profileId == null) {
            return;
        }
        for (int index = 0; index < rows.size(); index++) {
            CalibrationProfileDto row = rows.get(index);
            if (row.getProfileId() != null && row.getProfileId().equals(profileId)) {
                table.getSelectionModel().select(index);
                return;
            }
        }
    }

    private void setBusy(boolean busy, String message) {
        this.busy = busy;
        if (message != null) {
            statusLabel.setText(message);
            statusLabel.setTextFill(FxTheme.TEXT_MUTED);
        }
        updateActionState();
    }

    private String formatProfileDetails(CalibrationProfileDto profile) {
        if (profile == null) {
            return UiText.text("No profile data available.");
        }
        return UiText.text("Roller diameter: ") + formatDouble(profile.getRollerDiameterM(), 3, "m")
            + " | " + UiText.text("Pulses/rev: ") + formatDouble(profile.getEncoderPulsesPerRev(), 1, "")
            + " | " + UiText.text("Inertia: ") + formatDouble(profile.getRollerInertiaKgM2(), 3, "kg·m²")
            + " | " + UiText.text("Sample window: ") + formatLong(profile.getSampleWindowMs(), "ms");
    }

    private String formatValidationSummary(CalibrationValidationDto validation) {
        if (validation == null) {
            return UiText.text("Validation unavailable.");
        }
        if (!validation.getErrors().isEmpty()) {
            return UiText.text("Validation: invalid") + " (" + validation.getErrors().size() + ")";
        }
        if (!validation.getWarnings().isEmpty()) {
            return UiText.text("Validation: warnings") + " (" + validation.getWarnings().size() + ")";
        }
        return UiText.text("Validation: valid");
    }

    private String formatMessages(String label, List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return label + ": " + UiText.text("none");
        }
        StringBuilder builder = new StringBuilder();
        builder.append(label).append(":");
        for (String message : messages) {
            builder.append("\n- ").append(message);
        }
        return builder.toString();
    }

    private Color colorForValidation(CalibrationValidationDto validation) {
        return FxTheme.toneColor(toneForValidation(validation));
    }

    private OperatorViewModel.Tone toneForValidation(CalibrationValidationDto validation) {
        if (validation == null) {
            return OperatorViewModel.Tone.UNAVAILABLE;
        }
        if (!validation.getErrors().isEmpty()) {
            return OperatorViewModel.Tone.ALERT;
        }
        if (!validation.getWarnings().isEmpty()) {
            return OperatorViewModel.Tone.CAUTION;
        }
        return OperatorViewModel.Tone.NORMAL;
    }

    private String formatDouble(Double value, int decimals, String unit) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return "—";
        }
        String text = String.format(Locale.US, "%1$." + decimals + "f", value.doubleValue());
        if (unit == null || unit.isEmpty()) {
            return text;
        }
        return text + " " + unit;
    }

    private String formatLong(Long value, String unit) {
        if (value == null) {
            return "—";
        }
        return unit == null || unit.isEmpty() ? String.valueOf(value.longValue()) : value.longValue() + " " + unit;
    }

    private String formatTimestamp(Long value) {
        if (value == null) {
            return "—";
        }
        try {
            return EVENT_TIME_FORMAT.format(Instant.ofEpochMilli(value.longValue()));
        } catch (Exception ignored) {
            return String.valueOf(value.longValue());
        }
    }

    private String safeText(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim();
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeOptionalText(String value) {
        String normalized = normalizeText(value);
        return normalized.isEmpty() ? null : normalized;
    }

    private String numberText(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return "";
        }
        return String.format(Locale.US, "%.6f", value.doubleValue()).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private Double parseRequiredDouble(String raw, String fieldName) {
        String text = normalizeText(raw);
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(Double.parseDouble(text));
        } catch (NumberFormatException error) {
            return Double.valueOf(Double.NaN);
        }
    }

    private Double parseOptionalDouble(String raw, String fieldName) {
        String text = normalizeText(raw);
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(Double.parseDouble(text));
        } catch (NumberFormatException error) {
            return Double.valueOf(Double.NaN);
        }
    }

    private Long parseRequiredLong(String raw, String fieldName) {
        String text = normalizeText(raw);
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(Long.parseLong(text));
        } catch (NumberFormatException error) {
            return Long.valueOf(-1L);
        }
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String toRgbaCss(Color color, double alpha) {
        return "rgba(" +
            (int) Math.round(color.getRed() * 255) + "," +
            (int) Math.round(color.getGreen() * 255) + "," +
            (int) Math.round(color.getBlue() * 255) + "," +
            alpha + ")";
    }

    private static final class ParsedForm {
        private final CalibrationUpsertRequestDto request;
        private final CalibrationValidationDto validation;

        private ParsedForm(CalibrationUpsertRequestDto request, CalibrationValidationDto validation) {
            this.request = request;
            this.validation = validation;
        }
    }

    private static final class MutationResult {
        private final CalibrationResponseDto response;
        private final List<CalibrationProfileDto> profiles;
        private final List<CalibrationProfileEventDto> events;

        private MutationResult(
            CalibrationResponseDto response,
            List<CalibrationProfileDto> profiles,
            List<CalibrationProfileEventDto> events
        ) {
            this.response = response;
            this.profiles = profiles;
            this.events = events;
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    public static final class Result {
        private final CalibrationResponseDto response;
        private final String statusMessage;
        private final OperatorViewModel.Tone tone;

        private Result(CalibrationResponseDto response, String statusMessage, OperatorViewModel.Tone tone) {
            this.response = response;
            this.statusMessage = statusMessage;
            this.tone = tone;
        }

        public CalibrationResponseDto getResponse() {
            return response;
        }

        public String getStatusMessage() {
            return statusMessage;
        }

        public OperatorViewModel.Tone getTone() {
            return tone;
        }
    }
}
