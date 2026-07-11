package com.dyno.presenter;

import com.dyno.history.ComparedRunDto;
import com.dyno.history.RunHistoryFrameDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public final class RunMetricsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ComparedRunDto comparedRunWithPreRunFrames() throws Exception {
        String json =
            "{"
                + "\"run\":{"
                + "\"run_id\":1,"
                + "\"peak_power_hp\":80.0,"
                + "\"peak_power_rpm\":4000.0,"
                + "\"peak_torque_nm\":110.0,"
                + "\"peak_torque_rpm\":3000.0"
                + "},"
                + "\"frames\":["
                // Pre-run idle/armed frames prepended by the storage layer,
                // with a bogus high "speed" and an earlier timestamp.
                + "{\"ts_ms\":0,\"engine_rpm\":900.0,\"speed_kmh\":500.0,\"run_state\":\"idle\"},"
                + "{\"ts_ms\":50,\"engine_rpm\":1500.0,\"speed_kmh\":500.0,\"run_state\":\"armed\"},"
                + "{\"ts_ms\":100,\"engine_rpm\":2000.0,\"power_hp\":40.0,\"torque_nm\":100.0,"
                + "\"speed_kmh\":60.0,\"afr\":13.0,\"run_state\":\"recording\"},"
                + "{\"ts_ms\":200,\"engine_rpm\":4000.0,\"power_hp\":80.0,\"torque_nm\":90.0,"
                + "\"speed_kmh\":98.0,\"afr\":13.2,\"run_state\":\"recording\"}"
                + "]"
                + "}";
        return MAPPER.readValue(json, ComparedRunDto.class);
    }

    @Test
    public void timeToFrameSecondsBaselinesOnFirstRecordingFrameNotPreRunFrames() throws Exception {
        ComparedRunDto comparedRun = comparedRunWithPreRunFrames();
        List<RunHistoryFrameDto> frames = comparedRun.getFrames();
        RunHistoryFrameDto fastest = RunMetrics.fastestFrame(comparedRun);

        assertNotNull(fastest);
        assertEquals(98.0, fastest.getSpeedKmh(), 0.0001);

        Double timeToFastest = RunMetrics.timeToFrameSeconds(frames, fastest);
        assertNotNull(timeToFastest);
        // Recording starts at ts_ms=100 (not the pre-run ts_ms=0), fastest
        // frame is at ts_ms=200 -> 0.1s, not 0.2s.
        assertEquals(0.1, timeToFastest, 0.0001);
    }

    @Test
    public void fastestFrameIgnoresPreRunIdleAndArmedFrames() throws Exception {
        ComparedRunDto comparedRun = comparedRunWithPreRunFrames();
        RunHistoryFrameDto fastest = RunMetrics.fastestFrame(comparedRun);

        assertNotNull(fastest);
        assertEquals("recording", fastest.getRunState());
        assertEquals(98.0, fastest.getSpeedKmh(), 0.0001);
    }

    @Test
    public void frameAtPeakPowerMatchesStoredPeakRpmAmongRecordingFrames() throws Exception {
        ComparedRunDto comparedRun = comparedRunWithPreRunFrames();
        RunHistoryFrameDto peakFrame = RunMetrics.frameAtPeakPower(comparedRun);

        assertNotNull(peakFrame);
        assertEquals(4000.0, peakFrame.getEngineRpm(), 0.0001);
        assertEquals(13.2, peakFrame.getAfr(), 0.0001);
    }
}
