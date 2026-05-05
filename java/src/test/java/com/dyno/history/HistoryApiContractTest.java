package com.dyno.history;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public final class HistoryApiContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void runListShapeParses() throws Exception {
        String json =
            "[{"
                + "\"run_id\":1,"
                + "\"started_at_ms\":1000,"
                + "\"ended_at_ms\":1300,"
                + "\"date\":\"2026-04-16T10:00:00Z\","
                + "\"source_mode\":\"replay\","
                + "\"correction_mode\":\"sae_j1349\","
                + "\"peak_power_hp\":88.0,"
                + "\"peak_power_rpm\":4200.0,"
                + "\"peak_torque_nm\":132.0,"
                + "\"peak_torque_rpm\":4200.0"
                + "}]";

        List<RunHistorySummaryDto> runs = MAPPER.readValue(
            json,
            new TypeReference<List<RunHistorySummaryDto>>() { }
        );

        assertEquals(1, runs.size());
        assertEquals(Long.valueOf(1L), runs.get(0).getRunId());
        assertEquals("replay", runs.get(0).getSourceMode());
        assertEquals(Double.valueOf(88.0), runs.get(0).getPeakPowerHp());
    }

    @Test
    public void compareShapeParses() throws Exception {
        String json =
            "{"
                + "\"runs\":[{"
                + "\"run\":{"
                + "\"run_id\":1,"
                + "\"started_at_ms\":1000,"
                + "\"ended_at_ms\":1300,"
                + "\"date\":\"2026-04-16T10:00:00Z\","
                + "\"source_mode\":\"replay\","
                + "\"correction_mode\":\"sae_j1349\","
                + "\"roller_diameter_m\":0.318,"
                + "\"encoder_pulses_per_rev\":60.0,"
                + "\"roller_inertia_kg_m2\":3.5,"
                + "\"sample_window_ms\":100,"
                + "\"peak_power_hp\":88.0,"
                + "\"peak_power_rpm\":4200.0,"
                + "\"peak_torque_nm\":132.0,"
                + "\"peak_torque_rpm\":4200.0"
                + "},"
                + "\"frames\":[{"
                + "\"run_id\":1,"
                + "\"ts_ms\":1000,"
                + "\"engine_rpm\":4200.0,"
                + "\"power_hp\":88.0,"
                + "\"torque_nm\":132.0,"
                + "\"speed_kmh\":98.0,"
                + "\"afr\":13.1,"
                + "\"ambient_temp_c\":24.5,"
                + "\"humidity_pct\":55.0,"
                + "\"pressure_hpa\":1013.25,"
                + "\"correction_factor\":1.02,"
                + "\"run_state\":\"recording\""
                + "}]"
                + "}]"
                + "}";

        CompareRunsResponseDto response = MAPPER.readValue(json, CompareRunsResponseDto.class);

        assertNotNull(response.getRuns());
        assertEquals(1, response.getRuns().size());
        assertEquals(Long.valueOf(1L), response.getRuns().get(0).getRun().getRunId());
        assertEquals(1, response.getRuns().get(0).getFrames().size());
        assertEquals(Double.valueOf(98.0), response.getRuns().get(0).getFrames().get(0).getSpeedKmh());
    }
}
