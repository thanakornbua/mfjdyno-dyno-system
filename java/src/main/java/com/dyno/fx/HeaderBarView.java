package com.dyno.fx;

import com.dyno.health.OperatorStatusModel;
import com.dyno.presenter.OperatorViewModel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

public final class HeaderBarView extends VBox {
    private final HBox topRow;
    private final HBox toolbarRow;
    private final Label title;
    private final Label subtitle;
    private final Label runLabel;
    private final Label plateLabel;
    private final Label backendStatusTitle;
    private final Label backendStatusBadge;
    private final Label backendStatusPrimary;
    private final Label backendStatusSecondary;
    private final HBox backendStatusRow;
    private final VBox backendStatusBlock;
    private final Label connectionBadge;
    private final Label stateBadge;
    private final Button runModeButton;
    private final Button startButton;
    private final Button stopButton;
    private final Button printButton;
    private final Button compareButton;
    private final Button calibrationButton;
    private final Button auditLogButton;
    private final Button languageButton;
    private final Label bannerTitle;
    private final Label bannerMessage;
    private final BorderPane bannerPanel;

    public HeaderBarView(
        Runnable onRunModeRequested,
        Runnable onStartRequested,
        Runnable onStopRequested,
        Runnable onPrintRequested,
        Runnable onCompareRequested,
        Runnable onCalibrationRequested,
        Runnable onAuditLogRequested,
        Runnable onLanguageToggleRequested
    ) {
        setSpacing(12);
        setPadding(new Insets(12, 18, 10, 18));
        setStyle(
            "-fx-background-color: " + FxTheme.toCss(FxTheme.SURFACE) + ";" +
            "-fx-border-color: " + FxTheme.toCss(FxTheme.BORDER) + ";" +
            "-fx-border-width: 0 0 1 0;"
        );

        title = new Label(UiText.text("Dyno Operator Console"));
        title.setTextFill(FxTheme.TEXT_PRIMARY);
        title.setFont(Font.font("SansSerif", FontWeight.BOLD, 26));

        subtitle = new Label(UiText.text("Toolbar and operator status"));
        subtitle.setTextFill(FxTheme.TEXT_MUTED);
        subtitle.setFont(Font.font("SansSerif", FontWeight.NORMAL, 15));

        runLabel = new Label(UiText.text("RUN NOT CONFIGURED"));
        runLabel.setTextFill(FxTheme.TEXT_PRIMARY);
        runLabel.setFont(Font.font("Monospaced", FontWeight.BOLD, 22));

        plateLabel = new Label(UiText.text("Plate: —"));
        plateLabel.setTextFill(FxTheme.TEXT_SUBTLE);
        plateLabel.setFont(Font.font("SansSerif", FontWeight.NORMAL, 14));

        backendStatusTitle = new Label(UiText.text("SYSTEM STATUS"));
        backendStatusTitle.setTextFill(FxTheme.TEXT_SUBTLE);
        backendStatusTitle.setFont(Font.font("SansSerif", FontWeight.BOLD, 13));

        backendStatusBadge = buildBadge(UiText.text("UNAVAILABLE"));
        backendStatusPrimary = new Label(UiText.text("Checking backend status..."));
        backendStatusPrimary.setTextFill(FxTheme.TEXT_PRIMARY);
        backendStatusPrimary.setFont(Font.font("SansSerif", FontWeight.BOLD, 15));
        backendStatusSecondary = new Label(UiText.text("Automatic status refresh active"));
        backendStatusSecondary.setTextFill(FxTheme.TEXT_MUTED);
        backendStatusSecondary.setFont(Font.font("SansSerif", FontWeight.NORMAL, 13));
        backendStatusSecondary.setWrapText(true);

        backendStatusRow = new HBox(8, backendStatusBadge, backendStatusPrimary);
        backendStatusRow.setAlignment(Pos.CENTER_LEFT);
        backendStatusBlock = new VBox(3, backendStatusTitle, backendStatusRow, backendStatusSecondary);
        backendStatusBlock.setMaxWidth(300);

        connectionBadge = buildBadge(UiText.text("DISCONNECTED"));
        stateBadge = buildBadge(UiText.text("RUN NOT CONFIGURED"));

        runModeButton = buildToolbarButton(UiText.text("RUN MODE"), onRunModeRequested);
        startButton = buildToolbarButton(UiText.text("START"), onStartRequested);
        stopButton = buildToolbarButton(UiText.text("STOP"), onStopRequested);
        printButton = buildToolbarButton(UiText.text("PRINT"), onPrintRequested);
        compareButton = buildToolbarButton(UiText.text("COMPARE"), onCompareRequested);
        calibrationButton = buildToolbarButton(UiText.text("CALIBRATION"), onCalibrationRequested);
        auditLogButton = buildToolbarButton(UiText.text("AUDIT LOG"), onAuditLogRequested);
        languageButton = buildToolbarButton(UiText.languageButtonLabel(), onLanguageToggleRequested);

        VBox titleBlock = new VBox(3, title, subtitle);
        VBox runBlock = new VBox(3, runLabel, plateLabel);
        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);

        topRow = new HBox(18, titleBlock, topSpacer, backendStatusBlock, runBlock);
        topRow.setAlignment(Pos.CENTER_LEFT);

        Region toolbarSpacer = new Region();
        HBox.setHgrow(toolbarSpacer, Priority.ALWAYS);
        toolbarRow = new HBox(
            8,
            runModeButton,
            startButton,
            stopButton,
            printButton,
            compareButton,
            calibrationButton,
            auditLogButton,
            languageButton,
            toolbarSpacer,
            connectionBadge,
            stateBadge
        );
        toolbarRow.setAlignment(Pos.CENTER_LEFT);

        bannerTitle = new Label(UiText.text("DISCONNECTED"));
        bannerMessage = new Label(UiText.text("Disconnected from dyno backend"));
        bannerTitle.setFont(Font.font("SansSerif", FontWeight.BOLD, 16));
        bannerMessage.setFont(Font.font("SansSerif", FontWeight.NORMAL, 16));
        bannerMessage.setTextFill(FxTheme.TEXT_PRIMARY);
        bannerPanel = new BorderPane();
        bannerPanel.setLeft(bannerTitle);
        bannerPanel.setCenter(bannerMessage);
        bannerPanel.setPadding(new Insets(10, 12, 10, 12));

        getChildren().addAll(topRow, toolbarRow, bannerPanel);
        paintBadge(backendStatusBadge, OperatorViewModel.Tone.ALERT);
        paintBadge(connectionBadge, OperatorViewModel.Tone.ALERT);
        paintBadge(stateBadge, OperatorViewModel.Tone.UNAVAILABLE);
        applyBannerTone(OperatorViewModel.Tone.ALERT);
    }

    void render(
        OperatorViewModel model,
        OperatorStatusModel operatorStatus,
        String runLabelText,
        String plateText,
        String runStateBadgeText,
        OperatorViewModel.Tone runStateTone,
        OperatorViewModel.BannerModel banner,
        boolean runModeEnabled,
        boolean startEnabled,
        boolean stopEnabled,
        boolean printEnabled,
        boolean compareEnabled,
        boolean calibrationEnabled
    ) {
        title.setText(UiText.text("Dyno Operator Console"));
        subtitle.setText(UiText.text("Toolbar and operator status"));
        backendStatusTitle.setText(UiText.text("SYSTEM STATUS"));
        runModeButton.setText(UiText.text("RUN MODE"));
        startButton.setText(UiText.text("START"));
        stopButton.setText(UiText.text("STOP"));
        printButton.setText(UiText.text("PRINT"));
        compareButton.setText(UiText.text("COMPARE"));
        calibrationButton.setText(UiText.text("CALIBRATION"));
        auditLogButton.setText(UiText.text("AUDIT LOG"));
        languageButton.setText(UiText.languageButtonLabel());

        runLabel.setText(UiText.text(runLabelText));
        plateLabel.setText(UiText.text("Plate: ") + plateText);
        backendStatusBadge.setText(UiText.text(operatorStatus.getOverallLabel()));
        backendStatusPrimary.setText(UiText.text(operatorStatus.getPrimaryMessage()));
        backendStatusSecondary.setText(UiText.text(operatorStatus.getSecondaryMessage()));

        connectionBadge.setText(UiText.text(model.getConnectionText()));
        stateBadge.setText(UiText.text(runStateBadgeText));
        paintBadge(backendStatusBadge, operatorStatusTone(operatorStatus));
        paintBadge(connectionBadge, connectionTone(model.getConnectionText()));
        paintBadge(stateBadge, runStateTone);

        bannerTitle.setText(UiText.text(banner.getTitle()));
        bannerMessage.setText(UiText.text(banner.getMessage()));
        applyBannerTone(banner.getTone());

        runModeButton.setDisable(!runModeEnabled);
        startButton.setDisable(!startEnabled);
        stopButton.setDisable(!stopEnabled);
        printButton.setDisable(!printEnabled);
        compareButton.setDisable(!compareEnabled);
        calibrationButton.setDisable(!calibrationEnabled);
        auditLogButton.setDisable(false);
    }

    void applyLayoutTier(LiveRunShellView.LayoutTier tier) {
        switch (tier) {
            case COMPACT:
                setSpacing(8);
                setPadding(new Insets(8, 10, 8, 10));
                topRow.setSpacing(10);
                toolbarRow.setSpacing(5);
                title.setFont(Font.font("SansSerif", FontWeight.BOLD, 22));
                subtitle.setFont(Font.font("SansSerif", FontWeight.NORMAL, 14));
                backendStatusTitle.setFont(Font.font("SansSerif", FontWeight.BOLD, 12));
                backendStatusPrimary.setFont(Font.font("SansSerif", FontWeight.BOLD, 14));
                backendStatusSecondary.setFont(Font.font("SansSerif", FontWeight.NORMAL, 12));
                backendStatusBlock.setMaxWidth(240);
                runLabel.setFont(Font.font("Monospaced", FontWeight.BOLD, 19));
                plateLabel.setFont(Font.font("SansSerif", FontWeight.NORMAL, 13));
                applyButtonFontSize(15);
                break;
            case LARGE:
                setSpacing(14);
                setPadding(new Insets(14, 22, 14, 22));
                topRow.setSpacing(20);
                toolbarRow.setSpacing(10);
                title.setFont(Font.font("SansSerif", FontWeight.BOLD, 30));
                subtitle.setFont(Font.font("SansSerif", FontWeight.NORMAL, 16));
                backendStatusTitle.setFont(Font.font("SansSerif", FontWeight.BOLD, 14));
                backendStatusPrimary.setFont(Font.font("SansSerif", FontWeight.BOLD, 16));
                backendStatusSecondary.setFont(Font.font("SansSerif", FontWeight.NORMAL, 14));
                backendStatusBlock.setMaxWidth(340);
                runLabel.setFont(Font.font("Monospaced", FontWeight.BOLD, 24));
                plateLabel.setFont(Font.font("SansSerif", FontWeight.NORMAL, 15));
                applyButtonFontSize(18);
                break;
            case NORMAL:
            default:
                setSpacing(12);
                setPadding(new Insets(12, 18, 12, 18));
                topRow.setSpacing(18);
                toolbarRow.setSpacing(8);
                title.setFont(Font.font("SansSerif", FontWeight.BOLD, 26));
                subtitle.setFont(Font.font("SansSerif", FontWeight.NORMAL, 15));
                backendStatusTitle.setFont(Font.font("SansSerif", FontWeight.BOLD, 13));
                backendStatusPrimary.setFont(Font.font("SansSerif", FontWeight.BOLD, 15));
                backendStatusSecondary.setFont(Font.font("SansSerif", FontWeight.NORMAL, 13));
                backendStatusBlock.setMaxWidth(300);
                runLabel.setFont(Font.font("Monospaced", FontWeight.BOLD, 22));
                plateLabel.setFont(Font.font("SansSerif", FontWeight.NORMAL, 14));
                applyButtonFontSize(17);
                break;
        }
    }

    private void applyButtonFontSize(double size) {
        Font font = Font.font("SansSerif", FontWeight.BOLD, size);
        runModeButton.setFont(font);
        startButton.setFont(font);
        stopButton.setFont(font);
        printButton.setFont(font);
        compareButton.setFont(font);
        calibrationButton.setFont(font);
        auditLogButton.setFont(font);
        languageButton.setFont(font);
    }

    private Button buildToolbarButton(String text, Runnable action) {
        Button button = new Button(text);
        button.setFocusTraversable(false);
        button.setFont(Font.font("SansSerif", FontWeight.BOLD, 17));
        button.setPadding(new Insets(10, 22, 10, 22));
        button.setStyle(
            "-fx-background-color: " + FxTheme.toCss(FxTheme.SURFACE_ALT) + ";" +
            "-fx-text-fill: " + FxTheme.toCss(FxTheme.TEXT_PRIMARY) + ";" +
            "-fx-background-radius: 9;" +
            "-fx-border-color: " + FxTheme.toCss(FxTheme.BORDER) + ";" +
            "-fx-border-radius: 9;"
        );
        button.setOnAction(event -> action.run());
        return button;
    }

    private Label buildBadge(String text) {
        Label badge = new Label(text);
        badge.setFont(Font.font("SansSerif", FontWeight.BOLD, 15));
        badge.setPadding(new Insets(7, 12, 7, 12));
        return badge;
    }

    private void paintBadge(Label badge, OperatorViewModel.Tone tone) {
        badge.setTextFill(FxTheme.toneColor(tone));
        badge.setStyle(
            "-fx-background-color: " + toRgbaCss(FxTheme.toneBackground(tone), 0.23) + ";" +
            "-fx-background-radius: 9;" +
            "-fx-border-color: " + FxTheme.toCss(FxTheme.toneBorder(tone)) + ";" +
            "-fx-border-radius: 9;"
        );
    }

    private void applyBannerTone(OperatorViewModel.Tone tone) {
        bannerTitle.setTextFill(FxTheme.toneColor(tone));
        bannerPanel.setStyle(
            "-fx-background-color: " + toRgbaCss(FxTheme.toneBackground(tone), 0.22) + ";" +
            "-fx-background-radius: 10;" +
            "-fx-border-color: " + FxTheme.toCss(FxTheme.toneBorder(tone)) + ";" +
            "-fx-border-radius: 10;"
        );
    }

    private OperatorViewModel.Tone connectionTone(String text) {
        if ("CONNECTED".equals(text)) {
            return OperatorViewModel.Tone.NORMAL;
        }
        if ("RECONNECTING".equals(text) || "CONNECTING".equals(text)) {
            return OperatorViewModel.Tone.CAUTION;
        }
        return OperatorViewModel.Tone.ALERT;
    }

    private OperatorViewModel.Tone operatorStatusTone(OperatorStatusModel operatorStatus) {
        switch (operatorStatus.getOverallState()) {
            case READY:
                return OperatorViewModel.Tone.NORMAL;
            case DEGRADED:
                return OperatorViewModel.Tone.CAUTION;
            case UNAVAILABLE:
            default:
                return OperatorViewModel.Tone.ALERT;
        }
    }

    private String toRgbaCss(javafx.scene.paint.Color color, double alpha) {
        return "rgba(" +
            (int) Math.round(color.getRed() * 255) + "," +
            (int) Math.round(color.getGreen() * 255) + "," +
            (int) Math.round(color.getBlue() * 255) + "," +
            alpha + ")";
    }
}
