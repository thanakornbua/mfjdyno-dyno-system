package com.dyno.fx;

import com.dyno.calibration.CalibrationApiClient;
import com.dyno.calibration.DependencyDto;
import com.dyno.calibration.DependencyStatusDto;
import com.dyno.calibration.FlashStatusDto;
import com.dyno.calibration.SerialDeviceDto;
import com.dyno.calibration.SerialDevicesDto;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.StringConverter;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * First-boot setup wizard: create the system password, choose the ESP read and
 * flash serial devices, and optionally flash firmware.
 *
 * Built as a custom modal {@link Stage} (not a JavaFX {@code Dialog}) so close
 * behaviour is fully under our control — a {@code Dialog} with no
 * {@code CANCEL_CLOSE} button cannot be dismissed programmatically, which
 * previously left the setup stuck after the password saved.
 */
public final class FirstBootSetupDialog {

    private final Stage stage = new Stage();
    private final CalibrationApiClient client = CalibrationApiClient.fromEnvironment();
    private final BorderPane rootPane = new BorderPane();

    // Selections carried across steps.
    private String chosenReadPort;
    private String chosenFlashPort;
    private boolean completed = false;
    // Set from the dependency step: true when arduino-cli or the esp32 core is
    // reported missing, so the flash step can warn/disable up front (the backend
    // also gates the flash — this is just an early, informational hint).
    private boolean flashToolchainMissing = false;

    private FirstBootSetupDialog(Stage owner) {
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(UiText.text("First-Time Setup"));
        // The wizard is mandatory: block the window's close (X) button. It is
        // still dismissed programmatically via finish()/stage.close().
        stage.setOnCloseRequest(WindowEvent::consume);

        rootPane.setPadding(FxTheme.PAD_DIALOG);
        rootPane.setPrefWidth(460);
        Scene scene = new Scene(rootPane);
        stage.setScene(scene);
    }

    // ── Step 1: password ───────────────────────────────────────────────────────

    private void showPasswordStep() {
        Label title = heading(UiText.text("Step 1 of 4 — Create System Password"));
        Label prompt = body(UiText.text(
            "This password protects Machine Configuration on this dyno. Set it now to continue."));

        PasswordField newPwd = new PasswordField();
        newPwd.setPromptText(UiText.text("New password (min 6 chars, no spaces)"));
        PasswordField confirmPwd = new PasswordField();
        confirmPwd.setPromptText(UiText.text("Confirm password"));

        Label error = errorLabel();
        Button next = primaryButton(UiText.text("NEXT"));

        next.setOnAction(e -> {
            String p1 = newPwd.getText();
            String p2 = confirmPwd.getText();
            if (p1.isEmpty() || p2.isEmpty()) {
                showError(error, UiText.text("All fields are required"));
                return;
            }
            if (!p1.equals(p2)) {
                showError(error, UiText.text("Passwords do not match"));
                confirmPwd.clear();
                return;
            }
            if (p1.length() < 6) {
                showError(error, UiText.text("Password must be at least 6 characters"));
                return;
            }
            if (p1.chars().anyMatch(Character::isWhitespace)) {
                showError(error, UiText.text("Password must not contain whitespace"));
                return;
            }
            next.setDisable(true);
            showInfo(error, UiText.text("Saving..."));
            CompletableFuture.runAsync(() -> {
                try {
                    client.setupPassword(p1);
                    Platform.runLater(this::showDevicesStep);
                } catch (CalibrationApiClient.LockException ex) {
                    // 409 = already set (e.g. re-entry); treat as done for this step.
                    Platform.runLater(() -> {
                        if (ex.statusCode == 409) {
                            showDevicesStep();
                        } else {
                            next.setDisable(false);
                            showError(error, ex.getMessage());
                        }
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        next.setDisable(false);
                        showError(error, UiText.text("Connection error: ") + ex.getMessage());
                    });
                }
            });
        });

        setContent(title, prompt, newPwd, confirmPwd, error, footer(next));
    }

    // ── Step 2: devices ────────────────────────────────────────────────────────

    private void showDevicesStep() {
        Label title = heading(UiText.text("Step 2 of 4 — Select ESP Device"));
        Label prompt = body(UiText.text(
            "Choose the serial port the dyno uses to talk to the ESP — a single USB cable "
                + "carries telemetry, config sync, and firmware flashing."));

        ComboBox<SerialDeviceDto> deviceCombo = deviceCombo();
        Label deviceLabel = fieldLabel(UiText.text("ESP32 device (telemetry + flashing):"));

        Button refresh = secondaryButton(UiText.text("REFRESH"));
        Label error = errorLabel();
        Button next = primaryButton(UiText.text("NEXT"));
        Button back = secondaryButton(UiText.text("BACK"));
        back.setOnAction(e -> showPasswordStep());

        refresh.setOnAction(e -> loadDevices(deviceCombo, error));

        next.setOnAction(e -> {
            SerialDeviceDto device = deviceCombo.getValue();
            String path = device != null ? device.getPath() : null;
            if (path == null) {
                showError(error, UiText.text("Select a device"));
                return;
            }
            next.setDisable(true);
            showInfo(error, UiText.text("Saving..."));
            CompletableFuture.runAsync(() -> {
                try {
                    client.saveDevices(path, path);
                    Platform.runLater(() -> {
                        chosenReadPort = path;
                        chosenFlashPort = path;
                        showDependencyStep();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        next.setDisable(false);
                        showError(error, UiText.text("Could not save devices: ") + ex.getMessage());
                    });
                }
            });
        });

        setContent(title, prompt, deviceLabel, deviceCombo, refresh, error, footer(back, next));
        loadDevices(deviceCombo, error);
    }

    private void loadDevices(ComboBox<SerialDeviceDto> deviceCombo, Label error) {
        showInfo(error, UiText.text("Scanning devices..."));
        CompletableFuture.runAsync(() -> {
            try {
                SerialDevicesDto devices = client.listSerialDevices();
                Platform.runLater(() -> {
                    List<SerialDeviceDto> list = devices.getDevices();
                    deviceCombo.getItems().setAll(list);
                    // Default to a persisted value (read port, else flash
                    // port), else the ESP guess.
                    selectByPath(deviceCombo, devices.getReadSerialPort());
                    if (deviceCombo.getValue() == null) {
                        selectByPath(deviceCombo, devices.getFlashSerialPort());
                    }
                    if (deviceCombo.getValue() == null) {
                        list.stream().filter(SerialDeviceDto::isEsp32Guess).findFirst()
                            .ifPresent(deviceCombo::setValue);
                    }
                    if (list.isEmpty()) {
                        showError(error, UiText.text(
                            "No serial devices detected. Connect the ESP and press Refresh."));
                    } else {
                        clearMessage(error);
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() ->
                    showError(error, UiText.text("Could not list devices: ") + ex.getMessage()));
            }
        });
    }

    // ── Step 4: flash (optional) ────────────────────────────────────────────────

    private void showFlashStep() {
        Label title = heading(UiText.text("Step 4 of 4 — Flash ESP Firmware (optional)"));
        Label prompt = body(UiText.text(
            "You can flash the ESP firmware now, or skip and do it later. "
                + "Flashing requires arduino-cli and the firmware sources on this machine."));

        TextArea logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPrefRowCount(8);
        logArea.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setVisible(false);
        spinner.setMaxSize(20, 20);

        Label status = body("");
        Button flashBtn = primaryButton(UiText.text("FLASH NOW"));
        Button back = secondaryButton(UiText.text("BACK"));
        back.setOnAction(e -> showDependencyStep());
        Button finishBtn = secondaryButton(UiText.text("SKIP & FINISH"));
        finishBtn.setOnAction(e -> finish());

        // The dependency step already told us the flash toolchain is incomplete;
        // disable flashing up front (the backend gate would reject it anyway).
        if (flashToolchainMissing) {
            flashBtn.setDisable(true);
            status.setText(UiText.text(
                "Flashing is unavailable: arduino-cli or the esp32 core is missing "
                    + "(see the previous step). Install it, or skip and flash later."));
        }

        flashBtn.setOnAction(e -> {
            flashBtn.setDisable(true);
            finishBtn.setDisable(true);
            spinner.setVisible(true);
            status.setText(UiText.text("Starting flash..."));
            logArea.clear();
            CompletableFuture.runAsync(() -> {
                try {
                    client.flashEsp(chosenFlashPort);
                    pollFlashStatus(logArea, status, spinner, flashBtn, finishBtn);
                } catch (CalibrationApiClient.LockException ex) {
                    Platform.runLater(() -> {
                        spinner.setVisible(false);
                        flashBtn.setDisable(false);
                        finishBtn.setDisable(false);
                        status.setText(UiText.text("Cannot flash: ") + ex.getMessage());
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        spinner.setVisible(false);
                        flashBtn.setDisable(false);
                        finishBtn.setDisable(false);
                        status.setText(UiText.text("Flash error: ") + ex.getMessage());
                    });
                }
            });
        });

        HBox statusRow = new HBox(FxTheme.GAP_S, spinner, status);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        setContent(title, prompt, statusRow, logArea, footer(back, flashBtn, finishBtn));
    }

    private void pollFlashStatus(TextArea logArea, Label status, ProgressIndicator spinner,
                                 Button flashBtn, Button finishBtn) {
        while (true) {
            FlashStatusDto s;
            try {
                s = client.getFlashStatus();
            } catch (Exception ex) {
                Platform.runLater(() ->
                    status.setText(UiText.text("Lost contact during flash: ") + ex.getMessage()));
                break;
            }
            FlashStatusDto snapshot = s;
            Platform.runLater(() -> {
                logArea.setText(snapshot.getLog());
                logArea.positionCaret(snapshot.getLog().length());
                if (snapshot.isRunning()) {
                    status.setText(UiText.text("Flashing... this can take a few minutes."));
                }
            });
            if (s.isTerminal()) {
                boolean ok = s.isSuccess();
                Platform.runLater(() -> {
                    spinner.setVisible(false);
                    finishBtn.setDisable(false);
                    finishBtn.setText(UiText.text("FINISH"));
                    if (ok) {
                        status.setText(UiText.text("Flash completed successfully."));
                        flashBtn.setDisable(true);
                    } else {
                        status.setText(UiText.text("Flash failed — see log. You can retry or finish."));
                        flashBtn.setDisable(false);
                    }
                });
                break;
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // ── Step 3: dependency check (informational) ────────────────────────────────

    private void showDependencyStep() {
        Label title = heading(UiText.text("Step 3 of 4 — Dependency Check"));
        Label prompt = body(UiText.text(
            "This is informational only. Missing optional items do not block continuing. "
                + "If the flash toolchain is missing, flashing in the next step is unavailable "
                + "until you install it."));

        ListView<DependencyDto> list = new ListView<>();
        list.setPrefHeight(220);
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(DependencyDto dep, boolean empty) {
                super.updateItem(dep, empty);
                if (empty || dep == null) {
                    setText(null);
                    return;
                }
                String indicator = dep.isOk() ? "OK" : dep.isMissing() ? "MISSING" : "UNKNOWN";
                String requiredTag = dep.isRequired() ? " (required)" : " (optional)";
                StringBuilder text = new StringBuilder();
                text.append('[').append(indicator).append("] ")
                    .append(dep.getName()).append(requiredTag)
                    .append(" — ").append(dep.getDetail());
                if (!dep.isOk() && dep.getRemediation() != null && !dep.getRemediation().isBlank()) {
                    text.append("\n    ").append(dep.getRemediation());
                }
                setText(text.toString());
                setWrapText(true);
            }
        });

        Label error = errorLabel();
        Button refresh = secondaryButton(UiText.text("REFRESH"));
        Button back = secondaryButton(UiText.text("BACK"));
        back.setOnAction(e -> showDevicesStep());
        Button next = primaryButton(UiText.text("NEXT"));
        next.setOnAction(e -> showFlashStep());
        refresh.setOnAction(e -> loadDependencies(list, error));

        setContent(title, prompt, list, refresh, error, footer(back, next));
        loadDependencies(list, error);
    }

    private static final int DEPENDENCY_CHECK_MAX_ATTEMPTS = 3;
    private static final long[] DEPENDENCY_CHECK_RETRY_DELAYS_MS = {2000L, 4000L};

    static String dependencyRetryStatusMessage(int attempt, int maxAttempts) {
        return attempt <= 1
            ? "Checking dependencies..."
            : "Still checking — retrying (attempt " + attempt + " of " + maxAttempts + ")...";
    }

    private void loadDependencies(ListView<DependencyDto> list, Label error) {
        Platform.runLater(() -> showInfo(error, UiText.text(dependencyRetryStatusMessage(1, DEPENDENCY_CHECK_MAX_ATTEMPTS))));
        CompletableFuture.runAsync(() -> {
            Exception lastError = null;
            for (int attempt = 1; attempt <= DEPENDENCY_CHECK_MAX_ATTEMPTS; attempt++) {
                if (!stage.isShowing()) {
                    return;
                }
                if (attempt > 1) {
                    final int attemptNumber = attempt;
                    Platform.runLater(() ->
                        showInfo(error, UiText.text(dependencyRetryStatusMessage(attemptNumber, DEPENDENCY_CHECK_MAX_ATTEMPTS))));
                    try {
                        Thread.sleep(DEPENDENCY_CHECK_RETRY_DELAYS_MS[attempt - 2]);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!stage.isShowing()) {
                        return;
                    }
                }
                try {
                    DependencyStatusDto status = client.listDependencies();
                    Platform.runLater(() -> {
                        if (!stage.isShowing()) {
                            return;
                        }
                        list.getItems().setAll(status.getDependencies());
                        // Note whether the flash toolchain is missing so the flash
                        // step can warn/disable up front.
                        flashToolchainMissing = status.getDependencies().stream()
                            .anyMatch(dep -> dep.blocksFlashing() && dep.isMissing());
                        clearMessage(error);
                    });
                    return;
                } catch (Exception ex) {
                    lastError = ex;
                }
            }
            final Exception finalError = lastError;
            Platform.runLater(() -> {
                if (!stage.isShowing()) {
                    return;
                }
                showError(error, UiText.text("Could not check dependencies: ")
                    + (finalError == null ? "" : finalError.getMessage()));
            });
        });
    }

    private void finish() {
        completed = true;
        stage.close();
    }

    // ── Small UI helpers ────────────────────────────────────────────────────────

    private void setContent(Region... children) {
        VBox box = new VBox(FxTheme.GAP_M, children);
        box.setAlignment(Pos.TOP_LEFT);
        rootPane.setCenter(box);
    }

    private HBox footer(Button... buttons) {
        HBox bar = new HBox(FxTheme.GAP_S, buttons);
        bar.setAlignment(Pos.CENTER_RIGHT);
        return bar;
    }

    private ComboBox<SerialDeviceDto> deviceCombo() {
        ComboBox<SerialDeviceDto> combo = new ComboBox<>();
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(SerialDeviceDto device) {
                if (device == null) {
                    return "";
                }
                String suffix = device.isEsp32Guess() ? "  (ESP)" : "";
                return device.getPath() + "  —  " + device.getLabel() + suffix;
            }

            @Override
            public SerialDeviceDto fromString(String value) {
                return null;
            }
        });
        return combo;
    }

    private void selectByPath(ComboBox<SerialDeviceDto> combo, String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        combo.getItems().stream()
            .filter(d -> path.equals(d.getPath()))
            .findFirst()
            .ifPresent(combo::setValue);
    }

    private Label heading(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        label.setWrapText(true);
        label.setMaxWidth(420);
        return label;
    }

    private Label body(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 12px;");
        label.setWrapText(true);
        label.setMaxWidth(420);
        return label;
    }

    private Label fieldLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
        return label;
    }

    private Label errorLabel() {
        Label label = new Label();
        label.setStyle("-fx-font-size: 11px; -fx-text-fill: #CC2200;");
        label.setWrapText(true);
        label.setMaxWidth(420);
        label.setVisible(false);
        return label;
    }

    private void showError(Label label, String message) {
        label.setText(message);
        label.setStyle("-fx-font-size: 11px; -fx-text-fill: #CC2200;");
        label.setVisible(true);
    }

    private void showInfo(Label label, String message) {
        label.setText(message);
        label.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");
        label.setVisible(true);
    }

    private void clearMessage(Label label) {
        label.setText("");
        label.setVisible(false);
    }

    private Button primaryButton(String text) {
        Button button = new Button(text);
        button.setDefaultButton(true);
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(text);
        HBox.setHgrow(button, Priority.NEVER);
        return button;
    }

    /**
     * True while a wizard is open. {@code show} runs a nested event loop
     * ({@code showAndWait}), so a queued {@code Platform.runLater} from a
     * second entry path (startup setup check vs. the password gate's 409
     * handler) can fire while the first wizard is still up — without this
     * guard that would stack a second undismissable modal.
     * Only touched on the FX application thread.
     */
    private static boolean showing = false;

    /**
     * Shows the wizard and blocks until the operator finishes it. If a wizard
     * is already open, returns {@code false} immediately instead of stacking
     * a second modal (the caller should treat this as "setup not completed
     * by this call" — the already-open wizard is handling setup).
     */
    public static boolean show(Stage owner) {
        if (showing) {
            return false;
        }
        showing = true;
        try {
            FirstBootSetupDialog wizard = new FirstBootSetupDialog(owner);
            wizard.showPasswordStep();
            wizard.stage.showAndWait();
            return wizard.completed;
        } finally {
            showing = false;
        }
    }
}
