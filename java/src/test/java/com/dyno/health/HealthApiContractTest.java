package com.dyno.health;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public final class HealthApiContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void healthShapeParses() throws Exception {
        String json =
            "{"
                + "\"status\":\"degraded\","
                + "\"source_mode\":\"live\","
                + "\"checks\":["
                + "{"
                + "\"name\":\"database_path\","
                + "\"required\":true,"
                + "\"status\":\"ok\","
                + "\"summary\":\"database path /var/lib/dyno/dyno.db is writable\""
                + "},"
                + "{"
                + "\"name\":\"serial_port\","
                + "\"required\":true,"
                + "\"status\":\"degraded\","
                + "\"summary\":\"serial device /dev/serial0 is missing; live ingest will keep retrying until it appears\""
                + "},"
                + "{"
                + "\"name\":\"bme280_i2c\","
                + "\"required\":false,"
                + "\"status\":\"degraded\","
                + "\"summary\":\"optional I2C device /dev/i2c-1 is missing; ambient reads will fall back to stub values\""
                + "}"
                + "]"
                + "}";

        StartupHealthDto health = MAPPER.readValue(json, StartupHealthDto.class);

        assertEquals("degraded", health.getStatus());
        assertEquals("live", health.getSourceMode());
        assertNotNull(health.getChecks());
        assertEquals(3, health.getChecks().size());
        assertEquals("serial_port", health.getChecks().get(1).getName());
        assertEquals("degraded", health.getChecks().get(1).getStatus());
        assertFalse(Boolean.TRUE.equals(health.getChecks().get(2).getRequired()));
    }
}
