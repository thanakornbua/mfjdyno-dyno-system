package com.dyno.presenter;

import com.dyno.history.CompareRunsResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class CompareDisplayMapperTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void compareResponseBuildsOverlayChartAndSummary() throws Exception {
        String json =
            "{"
                + "\"runs\":["
                + "{"
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
                + "\"frames\":["
                + "{"
                + "\"run_id\":1,"
                + "\"ts_ms\":1000,"
                + "\"engine_rpm\":3200.0,"
                + "\"power_hp\":55.0,"
                + "\"torque_nm\":120.0,"
                + "\"speed_kmh\":76.0,"
                + "\"afr\":12.8,"
                + "\"ambient_temp_c\":24.5,"
                + "\"humidity_pct\":55.0,"
                + "\"pressure_hpa\":1013.25,"
                + "\"correction_factor\":1.02,"
                + "\"run_state\":\"recording\""
                + "},"
                + "{"
                + "\"run_id\":1,"
                + "\"ts_ms\":1100,"
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
                + "},"
                + "{"
                + "\"run\":{"
                + "\"run_id\":2,"
                + "\"started_at_ms\":2000,"
                + "\"ended_at_ms\":2300,"
                + "\"date\":\"2026-04-16T10:05:00Z\","
                + "\"source_mode\":\"live\","
                + "\"correction_mode\":\"none\","
                + "\"roller_diameter_m\":0.318,"
                + "\"encoder_pulses_per_rev\":60.0,"
                + "\"roller_inertia_kg_m2\":3.5,"
                + "\"sample_window_ms\":100,"
                + "\"peak_power_hp\":91.0,"
                + "\"peak_power_rpm\":4300.0,"
                + "\"peak_torque_nm\":129.0,"
                + "\"peak_torque_rpm\":3900.0"
                + "},"
                + "\"frames\":["
                + "{"
                + "\"run_id\":2,"
                + "\"ts_ms\":2100,"
                + "\"engine_rpm\":4300.0,"
                + "\"power_hp\":91.0,"
                + "\"torque_nm\":129.0,"
                + "\"speed_kmh\":101.0,"
                + "\"afr\":13.3,"
                + "\"ambient_temp_c\":29.0,"
                + "\"humidity_pct\":52.0,"
                + "\"pressure_hpa\":1009.0,"
                + "\"correction_factor\":1.00,"
                + "\"run_state\":\"recording\""
                + "}]"
                + "}"
                + "]"
                + "}";

        CompareRunsResponseDto response = MAPPER.readValue(json, CompareRunsResponseDto.class);
        CompareDisplayState display = CompareDisplayMapper.map(response);

        assertEquals("Comparing 2 stored runs", display.getSummaryPrimary());
        assertTrue(display.getSummarySecondary().contains("RUN-00001"));
        assertTrue(display.getSummarySecondary().contains("Ambient 24.5 C / 55.0 % / 1013."));
        assertEquals(4, display.getChartModel().getSeries().size());
        assertEquals("COMPARE MODE", display.getChartModel().getRunLabel());
    }
}
