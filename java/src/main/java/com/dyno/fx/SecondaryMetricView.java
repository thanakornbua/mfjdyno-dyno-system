package com.dyno.fx;

import com.dyno.presenter.OperatorViewModel;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

final class SecondaryMetricView extends VBox {
    private final Label label = new Label(UiText.text("Metric"));
    private final Label value = new Label("—");
    private final Label unit = new Label("");

    SecondaryMetricView() {
        setSpacing(4);
        setPadding(new Insets(4, 4, 4, 4));
        setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");

        label.setTextFill(FxTheme.TEXT_SUBTLE);
        label.setFont(Font.font("SansSerif", FontWeight.BOLD, 14));

        value.setTextFill(FxTheme.TEXT_PRIMARY);
        value.setFont(Font.font("Monospaced", FontWeight.BOLD, 26));

        unit.setTextFill(FxTheme.TEXT_MUTED);
        unit.setFont(Font.font("SansSerif", FontWeight.NORMAL, 14));

        getChildren().addAll(label, value, unit);
    }

    void render(OperatorViewModel.SecondaryMetricModel model) {
        label.setText(UiText.text(model.getLabel()));
        value.setText(model.getValueText());
        unit.setText(model.getUnitText().isEmpty() ? " " : model.getUnitText());
        value.setTextFill(FxTheme.toneColor(model.getTone()));
    }

    void applySizing(double valueFontSize) {
        value.setFont(Font.font("Monospaced", FontWeight.BOLD, valueFontSize));
    }
}
