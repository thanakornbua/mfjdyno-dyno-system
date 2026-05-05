package com.dyno.fx;

import com.dyno.presenter.OperatorViewModel;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

final class MetricTileView extends VBox {
    private final Label label = new Label(UiText.text("Metric"));
    private final Label value = new Label("—");
    private final Label unit = new Label("");
    private final Label footer = new Label(" ");

    MetricTileView() {
        setSpacing(4);
        setPadding(new Insets(4, 4, 4, 4));
        setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");

        label.setTextFill(FxTheme.TEXT_SUBTLE);
        label.setFont(Font.font("SansSerif", FontWeight.BOLD, 15));

        value.setTextFill(FxTheme.TEXT_PRIMARY);
        value.setFont(Font.font("Monospaced", FontWeight.BOLD, 48));

        unit.setTextFill(FxTheme.TEXT_MUTED);
        unit.setFont(Font.font("SansSerif", FontWeight.NORMAL, 16));

        footer.setTextFill(FxTheme.TEXT_SUBTLE);
        footer.setFont(Font.font("SansSerif", FontWeight.NORMAL, 14));

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(label, value, unit, spacer, footer);
    }

    void render(OperatorViewModel.MetricTileModel model) {
        label.setText(UiText.text(model.getLabel()));
        value.setText(model.getValueText());
        unit.setText(model.getUnitText().isEmpty() ? " " : model.getUnitText());
        footer.setText(UiText.text(model.getFooterText()));

        value.setTextFill(FxTheme.toneColor(model.getTone()));
    }

    void applySizing(double valueFontSize) {
        value.setFont(Font.font("Monospaced", FontWeight.BOLD, valueFontSize));
    }
}
