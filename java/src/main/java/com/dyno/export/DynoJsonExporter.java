package com.dyno.export;

import com.dyno.history.CompareRunsResponseDto;
import com.dyno.history.RunHistoryDetailDto;
import com.dyno.history.RunHistoryFrameDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes run data to pretty-printed JSON via Jackson.
 * DTOs are serialized as-is; their existing @JsonProperty annotations
 * produce snake_case field names matching the backend API.
 */
public final class DynoJsonExporter {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private DynoJsonExporter() {
    }

    /**
     * Writes a single-run export: { "run": {...}, "frames": [...] }
     */
    public static void writeSingleRun(
        RunHistoryDetailDto detail,
        List<RunHistoryFrameDto> frames,
        Path outputFile
    ) throws IOException {
        Map<String, Object> wrapper = new LinkedHashMap<String, Object>();
        wrapper.put("run", detail);
        wrapper.put("frames", frames);
        MAPPER.writeValue(outputFile.toFile(), wrapper);
    }

    /**
     * Writes a compare export: the full CompareRunsResponseDto structure.
     * Each run entry contains its detail and frame series.
     */
    public static void writeCompare(CompareRunsResponseDto response, Path outputFile) throws IOException {
        MAPPER.writeValue(outputFile.toFile(), response);
    }
}
