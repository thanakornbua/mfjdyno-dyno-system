package com.dyno.export;

import com.dyno.history.RunHistoryFrameDto;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes a run's frame series as UTF-8 CSV.
 * Column order matches RunHistoryFrameDto field layout.
 */
public final class DynoCsvExporter {
    private static final String HEADER =
        "ts_ms,engine_rpm,roller_rpm,speed_kmh,power_hp,torque_nm," +
        "afr,lambda,ambient_temp_c,humidity_pct,pressure_hpa,correction_factor,run_state";

    private DynoCsvExporter() {
    }

    public static void write(List<RunHistoryFrameDto> frames, Path outputFile) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            writer.write(HEADER);
            writer.newLine();
            for (int i = 0; i < frames.size(); i++) {
                writer.write(csvRow(frames.get(i)));
                writer.newLine();
            }
        }
    }

    private static String csvRow(RunHistoryFrameDto f) {
        StringBuilder sb = new StringBuilder();
        appendNum(sb, f.getTsMs(), false);
        appendNum(sb, f.getEngineRpm(), true);
        appendNum(sb, f.getRollerRpm(), true);
        appendNum(sb, f.getSpeedKmh(), true);
        appendNum(sb, f.getPowerHp(), true);
        appendNum(sb, f.getTorqueNm(), true);
        appendNum(sb, f.getAfr(), true);
        appendNum(sb, f.getLambda(), true);
        appendNum(sb, f.getAmbientTempC(), true);
        appendNum(sb, f.getHumidityPct(), true);
        appendNum(sb, f.getPressureHpa(), true);
        appendNum(sb, f.getCorrectionFactor(), true);
        appendStr(sb, f.getRunState());
        return sb.toString();
    }

    private static void appendNum(StringBuilder sb, Object value, boolean comma) {
        if (comma) {
            sb.append(',');
        }
        if (value != null) {
            sb.append(value);
        }
    }

    private static void appendStr(StringBuilder sb, String value) {
        sb.append(',');
        if (value == null) {
            return;
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0) {
            sb.append('"').append(value.replace("\"", "\"\"")).append('"');
        } else {
            sb.append(value);
        }
    }
}
