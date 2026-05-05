package com.dyno.fx;

import com.dyno.presenter.OperatorViewModel;
import javafx.scene.paint.Color;

public final class FxTheme {
    public static final Color APP_BACKGROUND = Color.web("#000000");
    public static final Color SURFACE = Color.web("#0A0A0A");
    public static final Color SURFACE_ALT = Color.web("#111111");
    public static final Color BORDER = Color.web("#222222");
    public static final Color BORDER_STRONG = Color.web("#383838");
    public static final Color TEXT_PRIMARY = Color.web("#FFFFFF");
    public static final Color TEXT_MUTED = Color.web("#C8C8C8");
    public static final Color TEXT_SUBTLE = Color.web("#808080");
    public static final Color ACCENT = Color.web("#00FFE5");
    public static final Color SUCCESS = Color.web("#00FF6E");
    public static final Color WARNING = Color.web("#FFCC00");
    public static final Color ALERT = Color.web("#FF3030");
    public static final Color UNAVAILABLE = Color.web("#505050");

    private FxTheme() {
    }

    public static Color toneColor(OperatorViewModel.Tone tone) {
        if (tone == null) {
            return UNAVAILABLE;
        }
        switch (tone) {
            case NORMAL:
                return SUCCESS;
            case CAUTION:
                return WARNING;
            case ALERT:
            case FAULT:
                return ALERT;
            case ACCENT:
                return ACCENT;
            case UNAVAILABLE:
            default:
                return UNAVAILABLE;
        }
    }

    public static Color toneBorder(OperatorViewModel.Tone tone) {
        Color base = toneColor(tone);
        return Color.rgb(
            Math.min(255, (int) Math.round(base.getRed() * 255) + 12),
            Math.min(255, (int) Math.round(base.getGreen() * 255) + 12),
            Math.min(255, (int) Math.round(base.getBlue() * 255) + 12)
        );
    }

    public static Color toneBackground(OperatorViewModel.Tone tone) {
        Color base = toneColor(tone);
        return Color.rgb(
            (int) Math.round(base.getRed() * 255),
            (int) Math.round(base.getGreen() * 255),
            (int) Math.round(base.getBlue() * 255),
            0.25
        );
    }

    public static String toCss(Color color) {
        int r = (int) Math.round(color.getRed() * 255);
        int g = (int) Math.round(color.getGreen() * 255);
        int b = (int) Math.round(color.getBlue() * 255);
        return String.format("#%02X%02X%02X", r, g, b);
    }

    public static String cardStyle(Color background) {
        return "-fx-background-color: " + toCss(background) + ";" +
            "-fx-background-radius: 10;" +
            "-fx-border-color: " + toCss(BORDER_STRONG) + ";" +
            "-fx-border-width: 1;" +
            "-fx-border-radius: 10;";
    }
}
