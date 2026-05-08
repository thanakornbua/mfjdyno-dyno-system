package com.dyno.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RunControlResponseTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void missingSuccessFallsBackToHttpSuccessStatus() throws Exception {
        RunControlResponse response = MAPPER.readValue(
            "{"
                + "\"message\":\"Run started\","
                + "\"configured\":true,"
                + "\"started\":true,"
                + "\"recording\":false,"
                + "\"run_label\":\"RUN-001\","
                + "\"license_plate\":\"ABC 123\""
                + "}",
            RunControlResponse.class
        );

        response.normalizeFallbacks(200);

        assertTrue(response.isSuccess());
        assertTrue(response.isConfigured());
        assertTrue(response.isStarted());
    }

    @Test
    public void explicitFalseSuccessWinsOverHttpSuccessStatus() throws Exception {
        RunControlResponse response = MAPPER.readValue(
            "{"
                + "\"success\":false,"
                + "\"message\":\"Rejected\","
                + "\"configured\":false,"
                + "\"started\":false"
                + "}",
            RunControlResponse.class
        );

        response.normalizeFallbacks(200);

        assertFalse(response.isSuccess());
    }
}
