package com.dyno.health;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class OperatorStatusMapperTest {
    @Test
    public void healthyLiveModeMapsToReady() throws Exception {
        OperatorStatusModel model = OperatorStatusMapper.fromHealth(
            health("ok", "live", Collections.singletonList(check("database_path", "ok")))
        );

        assertEquals(OperatorStatusModel.OverallState.READY, model.getOverallState());
        assertTrue(model.isBackendReachable());
        assertTrue(model.isStorageReady());
        assertEquals("Backend ready", model.getPrimaryMessage());
        assertEquals("Live mode active | Health checks passing", model.getSecondaryMessage());
    }

    @Test
    public void replayModeMapsToReadyWithReplayMessage() throws Exception {
        OperatorStatusModel model = OperatorStatusMapper.fromHealth(
            health("ok", "replay", Collections.singletonList(check("database_path", "ok")))
        );

        assertEquals(OperatorStatusModel.OverallState.READY, model.getOverallState());
        assertEquals("Replay mode active", model.getPrimaryMessage());
        assertEquals("Health checks passing", model.getSecondaryMessage());
    }

    @Test
    public void serialAndAmbientChecksMapToDegradedMessages() throws Exception {
        OperatorStatusModel model = OperatorStatusMapper.fromHealth(
            health(
                "degraded",
                "live",
                Arrays.asList(
                    check("database_path", "ok"),
                    check("serial_port", "degraded"),
                    check("bme280_i2c", "degraded")
                )
            )
        );

        assertEquals(OperatorStatusModel.OverallState.DEGRADED, model.getOverallState());
        assertTrue(model.isSerialDegraded());
        assertTrue(model.isAmbientDegraded());
        assertEquals("Serial input unavailable — retrying", model.getPrimaryMessage());
        assertEquals("2 startup warnings active", model.getWarningSummary());
        assertEquals("Live mode active | 2 startup warnings active", model.getSecondaryMessage());
    }

    @Test
    public void storageProblemsMapToDegradedStorageState() throws Exception {
        OperatorStatusModel model = OperatorStatusMapper.fromHealth(
            health("error", "live", Collections.singletonList(check("database_path", "error")))
        );

        assertEquals(OperatorStatusModel.OverallState.DEGRADED, model.getOverallState());
        assertFalse(model.isStorageReady());
        assertEquals("Storage unavailable", model.getPrimaryMessage());
        assertEquals("Live mode active | 1 startup warning active", model.getSecondaryMessage());
    }

    @Test
    public void unavailableFallbackMapsToUnavailable() {
        OperatorStatusModel model = OperatorStatusMapper.unavailable();

        assertEquals(OperatorStatusModel.OverallState.UNAVAILABLE, model.getOverallState());
        assertFalse(model.isBackendReachable());
        assertEquals("Backend unavailable", model.getPrimaryMessage());
        assertEquals("Status checks retry automatically", model.getSecondaryMessage());
    }

    private static StartupHealthDto health(String status, String sourceMode, java.util.List<StartupCheckDto> checks) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        StringBuilder json = new StringBuilder();
        json.append("{\"status\":\"").append(status).append("\",");
        json.append("\"source_mode\":\"").append(sourceMode).append("\",");
        json.append("\"checks\":[");
        for (int i = 0; i < checks.size(); i++) {
            StartupCheckDto check = checks.get(i);
            if (i > 0) {
                json.append(",");
            }
            json.append("{")
                .append("\"name\":\"").append(check.getName()).append("\",")
                .append("\"required\":true,")
                .append("\"status\":\"").append(check.getStatus()).append("\",")
                .append("\"summary\":\"summary\"")
                .append("}");
        }
        json.append("]}");
        return mapper.readValue(json.toString(), StartupHealthDto.class);
    }

    private static StartupCheckDto check(String name, String status) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        return mapper.readValue(
            "{\"name\":\"" + name + "\",\"required\":true,\"status\":\"" + status + "\",\"summary\":\"summary\"}",
            StartupCheckDto.class
        );
    }
}
