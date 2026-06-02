package com.dyno.history;

import javafx.scene.paint.Color;

import java.util.Collections;
import java.util.List;

public final class OverlayRunData {
    private static final String[] HEX_CYCLE = {
        "#F97316", "#A855F7", "#06B6D4", "#EAB308", "#EC4899", "#6B7280", "#F8FAFC"
    };

    private final long runId;
    private final String label;
    private final List<RunHistoryFrameDto> frames;
    private final Color color;

    public OverlayRunData(long runId, String label, List<RunHistoryFrameDto> frames, Color color) {
        this.runId = runId;
        this.label = label;
        this.frames = Collections.unmodifiableList(frames);
        this.color = color;
    }

    public long getRunId() {
        return runId;
    }

    public String getLabel() {
        return label;
    }

    public List<RunHistoryFrameDto> getFrames() {
        return frames;
    }

    public Color getColor() {
        return color;
    }

    public String getColorHex() {
        return String.format("#%02X%02X%02X",
            (int) Math.round(color.getRed() * 255),
            (int) Math.round(color.getGreen() * 255),
            (int) Math.round(color.getBlue() * 255));
    }

    public static Color colorForIndex(int index) {
        return Color.web(HEX_CYCLE[index % HEX_CYCLE.length]);
    }
}
