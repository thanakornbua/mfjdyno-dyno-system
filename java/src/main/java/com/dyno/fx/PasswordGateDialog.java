package com.dyno.fx;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.Optional;

public final class PasswordGateDialog extends Dialog<Boolean> {

    private static final String CORRECT_PASSWORD = "MFJ123456";

    private final PasswordField passwordField = new PasswordField();
    private final Label errorLabel = new Label();

    private PasswordGateDialog(Stage owner) {
        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        setTitle(UiText.text("Machine Configuration"));

        ButtonType confirmType = new ButtonType(UiText.text("CONFIRM"), ButtonBar.ButtonData.OK_DONE);

        DialogPane pane = getDialogPane();
        pane.getButtonTypes().addAll(confirmType, ButtonType.CANCEL);
        pane.setContent(buildContent());

        Button confirmButton = (Button) pane.lookupButton(confirmType);
        confirmButton.addEventFilter(ActionEvent.ACTION, event -> {
            if (!CORRECT_PASSWORD.equals(passwordField.getText())) {
                errorLabel.setText(UiText.text("Incorrect password"));
                errorLabel.setVisible(true);
                passwordField.clear();
                event.consume();
            }
        });

        setResultConverter(button -> confirmType.equals(button));
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

        VBox box = new VBox(10, title, prompt, passwordField, errorLabel);
        box.setPadding(new Insets(14));
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    public static boolean show(Stage owner) {
        PasswordGateDialog dialog = new PasswordGateDialog(owner);
        Optional<Boolean> result = dialog.showAndWait();
        return result.orElse(Boolean.FALSE).booleanValue();
    }
}
