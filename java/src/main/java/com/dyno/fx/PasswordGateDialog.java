package com.dyno.fx;

import com.dyno.calibration.CalibrationApiClient;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class PasswordGateDialog extends Dialog<Boolean> {

    private final PasswordField passwordField = new PasswordField();
    private final Label errorLabel = new Label();
    private final Stage owner;
    private boolean passwordVerified = false;
    private Button confirmButton;

    private PasswordGateDialog(Stage owner) {
        this.owner = owner;
        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        setTitle(UiText.text("Machine Configuration"));

        ButtonType confirmType = new ButtonType(UiText.text("CONFIRM"), ButtonBar.ButtonData.OK_DONE);

        DialogPane pane = getDialogPane();
        pane.getButtonTypes().addAll(confirmType, ButtonType.CANCEL);
        pane.setContent(buildContent());

        confirmButton = (Button) pane.lookupButton(confirmType);
        confirmButton.addEventFilter(ActionEvent.ACTION, event -> {
            if (passwordVerified) {
                return;
            }
            event.consume();
            String pwd = passwordField.getText();
            if (pwd.isEmpty()) {
                showError(UiText.text("Please enter a password"));
                return;
            }
            confirmButton.setDisable(true);
            showInfo(UiText.text("Verifying..."));

            CompletableFuture.runAsync(() -> {
                try {
                    CalibrationApiClient.fromEnvironment().verifyPassword(pwd);
                    Platform.runLater(() -> {
                        passwordVerified = true;
                        setResult(Boolean.TRUE);
                        close();
                    });
                } catch (CalibrationApiClient.LockException e) {
                    if (e.isSetupRequired()) {
                        Platform.runLater(() -> {
                            confirmButton.setDisable(false);
                            passwordField.clear();
                            boolean created = FirstBootSetupDialog.show(owner);
                            if (created) {
                                passwordVerified = true;
                                setResult(Boolean.TRUE);
                                close();
                            } else {
                                showError(UiText.text("System password setup is required before continuing"));
                            }
                        });
                        return;
                    }
                    Platform.runLater(() -> {
                        confirmButton.setDisable(false);
                        passwordField.clear();
                        showError(UiText.text("Incorrect password"));
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        confirmButton.setDisable(false);
                        showError(UiText.text("Connection error: ") + e.getMessage());
                    });
                }
            });
        });

        setResultConverter(button -> confirmType.equals(button) && passwordVerified);
    }

    private VBox buildContent() {
        Label title = new Label(UiText.text("Machine Configuration — Authorised Access Only"));
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        Label prompt = new Label(UiText.text("Enter password to continue:"));
        prompt.setStyle("-fx-font-size: 12px;");

        passwordField.setPromptText(UiText.text("Password"));
        passwordField.setPrefWidth(260);

        errorLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #CC2200;");
        errorLabel.setVisible(false);

        Hyperlink changeLink = new Hyperlink(UiText.text("Change password"));
        changeLink.setStyle("-fx-font-size: 11px;");
        changeLink.setOnAction(e -> showChangePasswordDialog());

        VBox box = new VBox(FxTheme.GAP_M, title, prompt, passwordField, errorLabel, changeLink);
        box.setPadding(FxTheme.PAD_DIALOG);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #CC2200;");
        errorLabel.setVisible(true);
    }

    private void showInfo(String message) {
        errorLabel.setText(message);
        errorLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");
        errorLabel.setVisible(true);
    }

    private void showChangePasswordDialog() {
        PasswordField currentPwdField = new PasswordField();
        currentPwdField.setPromptText(UiText.text("Current password"));
        PasswordField newPwdField = new PasswordField();
        newPwdField.setPromptText(UiText.text("New password (min 6 chars, no spaces)"));
        PasswordField confirmPwdField = new PasswordField();
        confirmPwdField.setPromptText(UiText.text("Confirm new password"));

        Label changeErrorLabel = new Label();
        changeErrorLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #CC2200;");
        changeErrorLabel.setVisible(false);
        changeErrorLabel.setWrapText(true);
        changeErrorLabel.setMaxWidth(280);

        ButtonType confirmType = new ButtonType(UiText.text("CONFIRM"), ButtonBar.ButtonData.OK_DONE);
        Dialog<Void> changeDialog = new Dialog<>();
        changeDialog.setTitle(UiText.text("Change Password"));
        changeDialog.initOwner(getOwner());
        changeDialog.initModality(Modality.APPLICATION_MODAL);
        changeDialog.getDialogPane().getButtonTypes().addAll(confirmType, ButtonType.CANCEL);

        VBox content = new VBox(FxTheme.GAP_S,
            new Label(UiText.text("Current password:")), currentPwdField,
            new Label(UiText.text("New password:")), newPwdField,
            new Label(UiText.text("Confirm new password:")), confirmPwdField,
            changeErrorLabel
        );
        content.setPadding(FxTheme.PAD_DIALOG);
        content.setPrefWidth(300);
        changeDialog.getDialogPane().setContent(content);

        Button changeConfirmBtn = (Button) changeDialog.getDialogPane().lookupButton(confirmType);

        boolean[] changeDone = { false };
        changeConfirmBtn.addEventFilter(ActionEvent.ACTION, event -> {
            if (changeDone[0]) return;
            event.consume();

            String current = currentPwdField.getText();
            String newPwd = newPwdField.getText();
            String confirm = confirmPwdField.getText();

            if (current.isEmpty() || newPwd.isEmpty() || confirm.isEmpty()) {
                changeErrorLabel.setText(UiText.text("All fields are required"));
                changeErrorLabel.setVisible(true);
                return;
            }
            if (!newPwd.equals(confirm)) {
                changeErrorLabel.setText(UiText.text("New passwords do not match"));
                changeErrorLabel.setVisible(true);
                newPwdField.clear();
                confirmPwdField.clear();
                return;
            }
            if (newPwd.length() < 6) {
                changeErrorLabel.setText(UiText.text("New password must be at least 6 characters"));
                changeErrorLabel.setVisible(true);
                return;
            }
            if (newPwd.chars().anyMatch(Character::isWhitespace)) {
                changeErrorLabel.setText(UiText.text("New password must not contain whitespace"));
                changeErrorLabel.setVisible(true);
                return;
            }

            changeConfirmBtn.setDisable(true);
            changeErrorLabel.setText(UiText.text("Changing password..."));
            changeErrorLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");
            changeErrorLabel.setVisible(true);

            CompletableFuture.runAsync(() -> {
                try {
                    CalibrationApiClient.fromEnvironment().changePassword(current, newPwd);
                    Platform.runLater(() -> {
                        changeErrorLabel.setText(UiText.text("Password changed successfully"));
                        changeErrorLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #007700;");
                        changeErrorLabel.setVisible(true);
                        changeDone[0] = true;
                        changeConfirmBtn.fire();
                    });
                } catch (CalibrationApiClient.LockException e) {
                    Platform.runLater(() -> {
                        changeConfirmBtn.setDisable(false);
                        String msg = e.statusCode == 401
                            ? UiText.text("Current password incorrect")
                            : e.getMessage();
                        changeErrorLabel.setText(msg);
                        changeErrorLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #CC2200;");
                        changeErrorLabel.setVisible(true);
                        currentPwdField.clear();
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        changeConfirmBtn.setDisable(false);
                        changeErrorLabel.setText(UiText.text("Error: ") + e.getMessage());
                        changeErrorLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #CC2200;");
                        changeErrorLabel.setVisible(true);
                    });
                }
            });
        });

        changeDialog.setResultConverter(bt -> null);
        changeDialog.showAndWait();
    }

    public static boolean show(Stage owner) {
        PasswordGateDialog dialog = new PasswordGateDialog(owner);
        Optional<Boolean> result = dialog.showAndWait();
        return result.orElse(Boolean.FALSE).booleanValue();
    }
}
