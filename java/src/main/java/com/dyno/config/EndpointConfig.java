package com.dyno.config;

import java.net.URI;

public final class EndpointConfig {
    private static final String DEFAULT_API_BASE_URL = "http://127.0.0.1:9001";
    private static final String DEFAULT_WS_URI = "ws://127.0.0.1:9000";
    private static final String DEFAULT_CONTROL_API_BASE_URL = "http://127.0.0.1:9001";

    private EndpointConfig() {
    }

    public static URI apiBaseUri() {
        return URI.create(resolveApiBaseUrl());
    }

    public static URI wsUri() {
        return URI.create(resolveWsUri());
    }

    public static URI controlApiBaseUri() {
        return URI.create(resolveControlApiBaseUrl());
    }

    public static boolean debugWebSocketFrames() {
        return truthy(firstNonBlank(
            System.getProperty("dyno.ws.debug"),
            System.getenv("DYNO_UI_WS_DEBUG")
        ));
    }

    public static String startupSummary() {
        return "[dyno-ui] startup configuration:"
            + " api=" + resolveApiBaseUrl()
            + " ws=" + resolveWsUri()
            + " control_api=" + resolveControlApiBaseUrl()
            + " (the console starts even if the backend is unavailable; live telemetry reconnects automatically,"
            + " and history/calibration requests show request-time errors until the backend recovers)";
    }

    private static String resolveApiBaseUrl() {
        String raw = firstNonBlank(
            System.getProperty("dyno.api.base_url"),
            System.getenv("DYNO_UI_API_BASE_URL"),
            backendUrl("http", "DYNO_BACKEND_HTTP_PORT", "9001")
        );
        return raw == null ? DEFAULT_API_BASE_URL : raw;
    }

    private static String resolveWsUri() {
        String raw = firstNonBlank(
            System.getProperty("dyno.ws.uri"),
            System.getenv("DYNO_UI_WS_URI"),
            backendUrl("ws", "DYNO_BACKEND_WS_PORT", "9000")
        );
        return raw == null ? DEFAULT_WS_URI : raw;
    }

    private static String resolveControlApiBaseUrl() {
        String raw = firstNonBlank(
            System.getProperty("dyno.control.api.base_url"),
            System.getProperty("DYNO_CONTROL_API_BASE_URL"),
            System.getenv("DYNO_CONTROL_API_BASE_URL"),
            resolveApiBaseUrl()
        );
        return raw == null ? DEFAULT_CONTROL_API_BASE_URL : raw;
    }

    private static String backendUrl(String scheme, String portEnv, String defaultPort) {
        String host = firstNonBlank(System.getenv("DYNO_BACKEND_HOST"));
        String port = firstNonBlank(System.getenv(portEnv));
        if (host == null && port == null) {
            return null;
        }
        return scheme + "://" + (host == null ? "127.0.0.1" : host) + ":" + (port == null ? defaultPort : port);
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.trim().isEmpty()) {
                return candidate.trim();
            }
        }
        return null;
    }

    private static boolean truthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return "1".equals(normalized)
            || "true".equals(normalized)
            || "yes".equals(normalized)
            || "on".equals(normalized);
    }
}
