package com.dyno.fx;

import com.dyno.presenter.OperatorViewModel;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

final class GaugeCardView extends VBox {
    private final Label label = new Label(UiText.text("Gauge"));
    private final Label value = new Label("—");
    private final Label unit = new Label("");
    private final Label state = new Label("UNAVAILABLE");

    GaugeCardView() {
        setSpacing(4);
        setPadding(new Insets(FxTheme.GAP_S));
        setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");

        label.setTextFill(FxTheme.TEXT_SUBTLE);
        label.setFont(Font.font("SansSerif", FontWeight.BOLD, 15));

        value.setTextFill(FxTheme.TEXT_PRIMARY);
        value.setFont(Font.font("Monospaced", FontWeight.BOLD, 38));

        unit.setTextFill(FxTheme.TEXT_MUTED);
        unit.setFont(Font.font("SansSerif", FontWeight.NORMAL, 15));

        state.setFont(Font.font("SansSerif", FontWeight.BOLD, 14));
        state.setTextFill(FxTheme.TEXT_MUTED);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(label, value, unit, spacer, state);
    }

    void render(OperatorViewModel.GaugeModel model) {
        OperatorViewModel.Tone tone = model.getTone();
        label.setText(UiText.text(model.getLabel()));
        value.setText(model.getValueText());
        unit.setText(model.getUnitText().isEmpty() ? " " : model.getUnitText());
        state.setText(UiText.text(model.getStateText()));
        String stateText = model.getStateText() == null ? "" : model.getStateText().trim();

        value.setTextFill(FxTheme.toneColor(tone));
        state.setTextFill(FxTheme.toneColor(tone));

        boolean showState = !stateText.isEmpty();
        state.setVisible(showState);
        state.setManaged(showState);
    }

    void applySizing(double valueFontSize) {
        value.setFont(Font.font("Monospaced", FontWeight.BOLD, valueFontSize));
    }
}
