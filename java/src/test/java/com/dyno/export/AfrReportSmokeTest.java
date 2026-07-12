package com.dyno.export;

import com.dyno.history.CompareRunsResponseDto;
import com.dyno.history.RunHistoryDetailDto;
import com.dyno.history.RunHistoryFrameDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/** Smoke test: renders single-run and compare PDFs with AFR data end to end. */
public final class AfrReportSmokeTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static void assumeFontAvailable() {
        boolean available;
        try {
            FontProvider.locateFont();
            available = true;
        } catch (Exception missing) {
            available = false;
        }
        org.junit.Assume.assumeTrue("Sarabun font not available on this machine", available);
    }

    private static RunHistoryFrameDto frame(long ts, double rpm, double hp, double nm, Double afr) throws Exception {
        StringBuilder json = new StringBuilder("{\"ts_ms\":").append(ts)
            .append(",\"engine_rpm\":").append(rpm)
            .append(",\"power_hp\":").append(hp)
            .append(",\"torque_nm\":").append(nm)
            .append(",\"run_state\":\"recording\"")
            .append(",\"ambient_temp_c\":31.0,\"humidity_pct\":62.0,\"pressure_hpa\":1009.0,\"correction_factor\":1.02");
        if (afr != null) json.append(",\"afr\":").append(afr);
        json.append("}");
        return MAPPER.readValue(json.toString(), RunHistoryFrameDto.class);
    }

    private static RunHistoryDetailDto detail(long id) throws Exception {
        String json = "{\"run_id\":" + id + ",\"date\":\"2026-07-12\",\"source_mode\":\"CAN\","
            + "\"correction_mode\":\"SAE\",\"peak_power_hp\":96.0,\"peak_power_rpm\":8200.0,"
            + "\"peak_torque_nm\":92.0,\"peak_torque_rpm\":6400.0,\"license_plate\":\"1กข234\",\"run_no\":" + id + "}";
        return MAPPER.readValue(json, RunHistoryDetailDto.class);
    }

    private static List<RunHistoryFrameDto> sweepFrames() throws Exception {
        List<RunHistoryFrameDto> frames = new ArrayList<RunHistoryFrameDto>();
        long ts = 1000;
        for (int rpm = 2000; rpm <= 9000; rpm += 200) {
            double hp = 96.0 * Math.exp(-Math.pow((rpm - 8200.0) / 3000.0, 2));
            double nm = hp * 7127.0 / Math.max(rpm, 1);
            // AFR walks the whole scale: rich low, on-target mid, lean top; one gap.
            Double afr = rpm == 4600 ? null : Double.valueOf(10.5 + (rpm - 2000) / 7000.0 * 5.5);
            frames.add(frame(ts, rpm, hp, nm, afr));
            ts += 150;
        }
        return frames;
    }

    @Test
    public void rendersSingleRunAndComparePdfsWithAfrSections() throws Exception {
        assumeFontAvailable();
        Path dir = Files.createTempDirectory("afr-smoke");

        Path single = dir.resolve("single.pdf");
        DynoPdfExporter.writeSingleRun(detail(1), sweepFrames(), single);
        assertTrue(Files.isRegularFile(single));
        assertTrue("single-run PDF suspiciously small", Files.size(single) > 15_000);

        StringBuilder cj = new StringBuilder("{\"runs\":[");
        for (int i = 1; i <= 2; i++) {
            if (i > 1) cj.append(',');
            cj.append("{\"run\":{\"run_id\":").append(i)
              .append(",\"peak_power_hp\":96.0,\"peak_power_rpm\":8200.0,\"peak_torque_nm\":92.0,")
              .append("\"peak_torque_rpm\":6400.0,\"license_plate\":\"RUN").append(i).append("\",\"run_no\":").append(i)
              .append("},\"frames\":[");
            List<RunHistoryFrameDto> frames = sweepFrames();
            for (int f = 0; f < frames.size(); f++) {
                if (f > 0) cj.append(',');
                RunHistoryFrameDto fr = frames.get(f);
                cj.append("{\"ts_ms\":").append(fr.getTsMs())
                  .append(",\"engine_rpm\":").append(fr.getEngineRpm())
                  .append(",\"power_hp\":").append(fr.getPowerHp() * (i == 2 ? 0.93 : 1.0))
                  .append(",\"torque_nm\":").append(fr.getTorqueNm())
                  .append(",\"run_state\":\"recording\"");
                if (fr.getAfr() != null) cj.append(",\"afr\":").append(fr.getAfr() + (i == 2 ? 0.6 : 0.0));
                cj.append('}');
            }
            cj.append("]}");
        }
        cj.append("]}");
        CompareRunsResponseDto compare = MAPPER.readValue(cj.toString(), CompareRunsResponseDto.class);

        Path cmp = dir.resolve("compare.pdf");
        DynoPdfExporter.writeCompare(compare, cmp);
        assertTrue(Files.isRegularFile(cmp));
        assertTrue("compare PDF suspiciously small", Files.size(cmp) > 15_000);

        System.out.println("SMOKE_SINGLE=" + single);
        System.out.println("SMOKE_COMPARE=" + cmp);
    }
}
