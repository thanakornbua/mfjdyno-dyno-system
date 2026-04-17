package com.dyno.operator.config.model;

import java.util.Locale;

public enum EngineEdgeMode {
    RISING("rising"),
    FALLING("falling"),
    BOTH("both");

    private final String wireValue;

    EngineEdgeMode(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static EngineEdgeMode fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            return RISING;
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (EngineEdgeMode mode : values()) {
            if (mode.wireValue.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unsupported engine edge mode: " + value);
    }
}
