package com.dyno.state;

import com.dyno.model.FrameMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public final class LiveTelemetryStateTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void reconnectStatusKeepsMostRecentFrameVisible() throws Exception {
        LiveTelemetryState state = new LiveTelemetryState();
        FrameMessage frame = MAPPER.readValue(
            "{"
                + "\"run_state\":\"recording\","
                + "\"engine_rpm\":4200.0,"
                + "\"power_hp\":88.0,"
                + "\"torque_nm\":140.0"
                + "}",
            FrameMessage.class
        );

        state.updateConnection(ConnectionPhase.CONNECTED, "Connected");
        state.updateFrame(frame);
        state.updateConnection(ConnectionPhase.RECONNECT_WAIT, "Connect failed: refused Reconnecting in 4s.");

        LiveTelemetrySnapshot snapshot = state.getSnapshot();
        assertEquals(ConnectionPhase.RECONNECT_WAIT, snapshot.getConnectionPhase());
        assertEquals("Connect failed: refused Reconnecting in 4s.", snapshot.getConnectionMessage());
        assertSame(frame, snapshot.getFrame());
        assertEquals(Double.valueOf(4200.0), snapshot.getFrame().getEngineRpm());
    }
}
