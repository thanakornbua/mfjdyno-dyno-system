package com.dyno.fx;

import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.text.DecimalFormat;

/**
 * Speedometer-style dial gauge: 240° sweep, tick marks with numeric labels,
 * needle, red zone over the top 15% of the range, and a large center readout.
 * Canvas redraws only when the displayed value (or range) actually changes.
 */
final class DialGaugeView extends VBox {
    private static final double SWEEP_DEG = 240.0;
    private static final double START_DEG = 210.0; // 0% of range (measured CCW from 3 o'clock)
    private static final double RED_ZONE_FRACTION = 0.15;
    private static final DecimalFormat WHOLE = new DecimalFormat("#,##0");
    private static final DecimalFormat ONE_DECIMAL = new DecimalFormat("#,##0.0");

    private final Canvas canvas;
    private final String title;
    private final String unit;
    private final boolean wholeNumbers;

    private double min;
    private double max;
    private double majorTick;
    private Double value;
    private double lastDrawnNeedle = Double.NaN;
    private String lastDrawnText = null;

    DialGaugeView(String title, String unit, double min, double max, double majorTick, double diameter) {
        this.title = title;
        this.unit = unit;
        this.min = min;
        this.max = max;
        this.majorTick = majorTick;
        this.wholeNumbers = majorTick >= 1.0;
        this.canvas = new Canvas(diameter, diameter);

        setAlignment(Pos.CENTER);
        setSpacing(FxTheme.GAP_XS);
        setStyle(FxTheme.cardStyle(FxTheme.SURFACE));
        setPadding(FxTheme.PAD_CARD);
        getChildren().add(canvas);
        draw();
    }

    void setRange(double min, double max, double majorTick) {
        if (this.min == min && this.max == max && this.majorTick == majorTick) {
            return;
        }
        this.min = min;
        this.max = max;
        this.majorTick = majorTick;
        forceRedraw();
    }

    void update(Double newValue) {
        this.value = newValue;
        double needle = needleFraction();
        String text = readoutText();
        // Skip sub-pixel needle moves and unchanged readouts at 20 Hz.
        if (!Double.isNaN(lastDrawnNeedle)
            && Math.abs(needle - lastDrawnNeedle) < 0.002
            && text.equals(lastDrawnText)) {
            return;
        }
        draw();
    }

    private void forceRedraw() {
        lastDrawnNeedle = Double.NaN;
        lastDrawnText = null;
        draw();
    }

    private double needleFraction() {
        if (value == null || max <= min) {
            return 0.0;
        }
        double f = (value.doubleValue() - min) / (max - min);
        return Math.max(0.0, Math.min(1.0, f));
    }

    private String readoutText() {
        if (value == null) {
            return "—";
        }
        return (wholeNumbers ? WHOLE : ONE_DECIMAL).format(value.doubleValue());
    }

    private void draw() {
        double size = canvas.getWidth();
        double cx = size / 2.0;
        double cy = size / 2.0;
        double radius = size / 2.0 - 8;

        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, size, size);

        // Track arc
        g.setStroke(FxTheme.BORDER_STRONG);
        g.setLineWidth(radius * 0.10);
        double arcR = radius * 0.86;
        g.strokeArc(cx - arcR, cy - arcR, arcR * 2, arcR * 2,
            START_DEG - SWEEP_DEG, SWEEP_DEG, javafx.scene.shape.ArcType.OPEN);

        // Red zone (top 15% of range)
        g.setStroke(FxTheme.ALERT);
        double redSweep = SWEEP_DEG * RED_ZONE_FRACTION;
        g.strokeArc(cx - arcR, cy - arcR, arcR * 2, arcR * 2,
            START_DEG - SWEEP_DEG, redSweep, javafx.scene.shape.ArcType.OPEN);

        // Ticks + labels
        g.setTextAlign(TextAlignment.CENTER);
        g.setFont(ThaiAwareFont.font(FontWeight.BOLD, Math.max(9, radius * 0.11)));
        int tickCount = majorTick > 0 ? (int) Math.round((max - min) / majorTick) : 0;
        for (int i = 0; i <= tickCount; i++) {
            double tickValue = min + i * majorTick;
            double frac = (tickValue - min) / (max - min);
            double angleDeg = START_DEG - frac * SWEEP_DEG;
            double a = Math.toRadians(angleDeg);
            double cos = Math.cos(a);
            double sin = -Math.sin(a);
            g.setStroke(FxTheme.TEXT_MUTED);
            g.setLineWidth(2);
            g.strokeLine(
                cx + cos * radius * 0.78, cy + sin * radius * 0.78,
                cx + cos * radius * 0.92, cy + sin * radius * 0.92);
            g.setFill(FxTheme.TEXT_SUBTLE);
            String label = tickValue >= 1000 && wholeNumbers
                ? WHOLE.format(tickValue / 1000.0) + "k"
                : (wholeNumbers ? WHOLE : ONE_DECIMAL).format(tickValue);
            g.fillText(label, cx + cos * radius * 0.63, cy + sin * radius * 0.63 + radius * 0.04);
        }

        // Needle
        double needle = needleFraction();
        double needleAngle = Math.toRadians(START_DEG - needle * SWEEP_DEG);
        double nCos = Math.cos(needleAngle);
        double nSin = -Math.sin(needleAngle);
        g.setStroke(value == null ? FxTheme.UNAVAILABLE : FxTheme.ACCENT);
        g.setLineWidth(Math.max(2.5, radius * 0.035));
        g.strokeLine(cx - nCos * radius * 0.10, cy - nSin * radius * 0.10,
            cx + nCos * radius * 0.72, cy + nSin * radius * 0.72);
        g.setFill(FxTheme.BORDER_STRONG);
        double hub = radius * 0.07;
        g.fillOval(cx - hub, cy - hub, hub * 2, hub * 2);

        // Title above center, value + unit below center
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(FxTheme.TEXT_MUTED);
        g.setFont(ThaiAwareFont.font(FontWeight.BOLD, Math.max(10, radius * 0.13)));
        g.fillText(title, cx, cy - radius * 0.28);
        g.setFill(value == null ? FxTheme.UNAVAILABLE : FxTheme.TEXT_PRIMARY);
        g.setFont(ThaiAwareFont.monospaced(FontWeight.BOLD, Math.max(16, radius * 0.30)));
        String text = readoutText();
        g.fillText(text, cx, cy + radius * 0.48);
        g.setFill(FxTheme.TEXT_SUBTLE);
        g.setFont(ThaiAwareFont.font(FontWeight.NORMAL, Math.max(9, radius * 0.11)));
        g.fillText(unit, cx, cy + radius * 0.62);

        lastDrawnNeedle = needle;
        lastDrawnText = text;
    }
}
