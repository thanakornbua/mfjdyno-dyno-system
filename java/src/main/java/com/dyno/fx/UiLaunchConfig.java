package com.dyno.fx;

import java.util.Locale;

public final class UiLaunchConfig {
    enum StartupMode {
        WINDOWED,
        MAXIMIZED,
        FULLSCREEN
    }

    private final StartupMode startupMode;
    private final boolean maximizeToFullscreen;

    private UiLaunchConfig(StartupMode startupMode, boolean maximizeToFullscreen) {
        this.startupMode = startupMode;
        this.maximizeToFullscreen = maximizeToFullscreen;
    }

    public StartupMode startupMode() {
        return startupMode;
    }

    public boolean maximizeToFullscreen() {
        return maximizeToFullscreen;
    }

    static UiLaunchConfig fromEnvironment() {
        boolean maximizeToFullscreen = parseBooleanFlag(
            firstNonBlank(
                System.getProperty("DYNO_UI_MAXIMIZE_TO_FULLSCREEN"),
                System.getenv("DYNO_UI_MAXIMIZE_TO_FULLSCREEN")
            ),
            true
        );
        String fullScreenFlag = firstNonBlank(System.getProperty("DYNO_UI_FULLSCREEN"), System.getenv("DYNO_UI_FULLSCREEN"));
        if ("true".equalsIgnoreCase(fullScreenFlag)) {
            return new UiLaunchConfig(StartupMode.FULLSCREEN, maximizeToFullscreen);
        }

        String startupModeValue = firstNonBlank(System.getProperty("DYNO_UI_MODE"), System.getenv("DYNO_UI_MODE"));
        StartupMode parsedMode = parseStartupMode(startupModeValue);
        return new UiLaunchConfig(parsedMode == null ? StartupMode.MAXIMIZED : parsedMode, maximizeToFullscreen);
    }

    private static StartupMode parseStartupMode(String value) {
        if (value == null) {
            return null;
        }
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "windowed":
                return StartupMode.WINDOWED;
            case "maximized":
                return StartupMode.MAXIMIZED;
            case "fullscreen":
                return StartupMode.FULLSCREEN;
            default:
                return null;
        }
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.trim().isEmpty()) {
            return primary;
        }
        if (fallback != null && !fallback.trim().isEmpty()) {
            return fallback;
        }
        return null;
    }

    private static boolean parseBooleanFlag(String value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized) || "off".equals(normalized)) {
            return false;
        }
        return defaultValue;
    }
}
