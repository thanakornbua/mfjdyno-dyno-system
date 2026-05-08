package com.dyno.ws;

import com.dyno.model.FrameMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class DynoWebSocketClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void parsesBackendLiveFrameEnvelope() throws Exception {
        FrameMessage frame = DynoWebSocketClient.parseLiveFrameMessage(
            MAPPER,
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
                + "\"run_state\":\"recording\""
                + "}"
                + "}"
        );

        assertEquals(Double.valueOf(4500.0), frame.getEngineRpm());
        assertEquals(Double.valueOf(1200.0), frame.getRollerRpm());
        assertEquals(Double.valueOf(95.4), frame.getSpeedKmh());
        assertEquals(Double.valueOf(87.3), frame.getPowerHp());
        assertEquals(Double.valueOf(135.0), frame.getTorqueNm());
        assertEquals(Double.valueOf(0.939), frame.getLambda());
        assertEquals(Double.valueOf(13.8), frame.getAfr());
        assertEquals(Double.valueOf(24.5), frame.getAmbientTempC());
        assertEquals(Double.valueOf(55.0), frame.getHumidityPct());
        assertEquals(Double.valueOf(1013.25), frame.getPressureHpa());
        assertEquals("recording", frame.getState());
    }

    @Test
    public void parsesPlainLiveFramePayload() throws Exception {
        FrameMessage frame = DynoWebSocketClient.parseLiveFrameMessage(
            MAPPER,
            "{"
                + "\"engine_rpm\":3200.0,"
                + "\"roller_rpm\":820.0,"
                + "\"speed_kmh\":50.0,"
                + "\"power_hp\":44.0,"
                + "\"torque_nm\":98.0,"
                + "\"lambda\":0.91,"
                + "\"afr\":13.4,"
                + "\"ambient_temp_c\":27.0,"
                + "\"humidity_pct\":62.0,"
                + "\"pressure_hpa\":1008.0,"
                + "\"run_state\":\"armed\""
                + "}"
        );

        assertEquals(Double.valueOf(3200.0), frame.getEngineRpm());
        assertEquals(Double.valueOf(820.0), frame.getRollerRpm());
        assertEquals("armed", frame.getState());
    }

    @Test
    public void parsesNestedLiveFrameEnvelope() throws Exception {
        FrameMessage frame = DynoWebSocketClient.parseLiveFrameMessage(
            MAPPER,
            "{"
                + "\"type\":\"live_frame\","
                + "\"data\":{"
                + "\"frame\":{"
                + "\"engine_rpm\":5100.0,"
                + "\"roller_rpm\":1275.0,"
                + "\"run_state\":\"recording\""
                + "}"
                + "}"
                + "}"
        );

        assertEquals(Double.valueOf(5100.0), frame.getEngineRpm());
        assertEquals(Double.valueOf(1275.0), frame.getRollerRpm());
        assertEquals("recording", frame.getState());
    }

    @Test
    public void ignoresNonFrameMessages() throws Exception {
        FrameMessage frame = DynoWebSocketClient.parseLiveFrameMessage(
            MAPPER,
            "{\"type\":\"health\",\"data\":{\"status\":\"ok\"}}"
        );

        assertNull(frame);
    }
}
