package com.dyno.presenter;

import com.dyno.model.FrameMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public final class LiveFrameContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void backendEnvelopeParsesIntoFrontendFrameModel() throws Exception {
        String json =
            "{"
                + "\"type\":\"live_frame\","
                + "\"data\":{"
                + "\"ts_ms\":1234,"
                + "\"engine_rpm\":4500.0,"
                + "\"roller_rpm\":1200.0,"
                + "\"speed_kmh\":95.4,"
                + "\"power_hp\":87.3,"
                + "\"torque_nm\":135.0,"
                + "\"lambda\":0.939,"
                + "\"afr\":13.8,"
                + "\"ambient_temp_c\":24.5,"
                + "\"humidity_pct\":55.0,"
                + "\"pressure_hpa\":1013.25,"
                + "\"correction_factor\":1.02,"
                + "\"run_state\":\"recording\","
                + "\"faults\":[\"can_timeout\"],"
                + "\"alerts\":{\"lambda\":\"warning\"}"
                + "}"
                + "}";

        JsonNode root = MAPPER.readTree(json);
        FrameMessage frame = MAPPER.treeToValue(root.get("data"), FrameMessage.class);

        assertEquals("live_frame", root.get("type").asText());
        assertNotNull(frame);
        assertEquals("recording", frame.getState());
        assertEquals(Double.valueOf(4500.0), frame.getEngineRpm());
        assertEquals(Double.valueOf(1200.0), frame.getRollerRpm());
        assertEquals(Double.valueOf(24.5), frame.getAmbientTempC());
        assertEquals(Double.valueOf(101.325), frame.getPressureKpa());
        assertEquals(Integer.valueOf(1), frame.getFaultCount());
        assertEquals(Double.valueOf(0.939), frame.getLambda());
        assertEquals(Double.valueOf(1.234), frame.getTs());
    }
}
