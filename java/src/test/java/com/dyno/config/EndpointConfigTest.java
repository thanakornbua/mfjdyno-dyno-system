package com.dyno.config;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class EndpointConfigTest {
    @Test
    public void startupSummaryUsesConfiguredSystemProperties() {
        String previousApi = System.getProperty("dyno.api.base_url");
        String previousWs = System.getProperty("dyno.ws.uri");
        String previousControlLower = System.getProperty("dyno.control.api.base_url");
        String previousControlUpper = System.getProperty("DYNO_CONTROL_API_BASE_URL");
        try {
            System.setProperty("dyno.api.base_url", "http://10.0.0.5:9001");
            System.setProperty("dyno.ws.uri", "ws://10.0.0.5:9000");
            System.setProperty("dyno.control.api.base_url", "http://10.0.0.5:8080");

            assertEquals("http://10.0.0.5:9001", EndpointConfig.apiBaseUri().toString());
            assertEquals("ws://10.0.0.5:9000", EndpointConfig.wsUri().toString());
            assertEquals("http://10.0.0.5:8080", EndpointConfig.controlApiBaseUri().toString());
        } finally {
            restore("dyno.api.base_url", previousApi);
            restore("dyno.ws.uri", previousWs);
            restore("dyno.control.api.base_url", previousControlLower);
            restore("DYNO_CONTROL_API_BASE_URL", previousControlUpper);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
            return;
        }
        System.setProperty(key, value);
    }
}
