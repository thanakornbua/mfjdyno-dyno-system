package com.dyno.export;

import com.dyno.history.CompareRunsResponseDto;
import com.dyno.history.ComparedRunDto;
import com.dyno.history.RunHistoryDetailDto;
import com.dyno.history.RunHistoryFrameDto;
import javafx.scene.image.WritableImage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Orchestrates export of a single run or a compare view to one or more formats.
 *
 * All methods are designed to be called from a background thread. The chart
 * snapshot (WritableImage) must have been captured on the FX Application Thread
 * before invoking these methods.
 *
 * Output files are written to the provided outputDir, which is created if absent.
 */
public final class ExportService {
    private ExportService() {
    }

    /**
     * Exports a single run in all requested formats.
     *
     * @param detail        run metadata (peaks, config)
     * @param frames        full frame series
     * @param chartSnapshot FX snapshot taken on the FX thread; may be null if PNG not requested
     * @param formats       set of formats to produce
     * @param outputDir     directory where output files are written
     * @return result containing exported file paths and any per-format errors
     */
    public static ExportResult exportSingleRun(
        RunHistoryDetailDto detail,
        List<RunHistoryFrameDto> frames,
        WritableImage chartSnapshot,
        Set<ExportFormat> formats,
        Path outputDir
    ) {
        List<Path> exported = new ArrayList<Path>();
        List<String> errors = new ArrayList<String>();

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            errors.add("Cannot create output directory: " + e.getMessage());
            return new ExportResult(exported, errors);
        }

        String base = "dyno-run-" + (detail != null && detail.getRunId() != null
            ? String.format("%05d", detail.getRunId()) : "unknown");
        List<RunHistoryFrameDto> safeFrames = frames != null
            ? frames : Collections.<RunHistoryFrameDto>emptyList();

        if (formats.contains(ExportFormat.PDF)) {
            Path out = outputDir.resolve(base + ".pdf");
            try {
                DynoPdfExporter.writeSingleRun(detail, safeFrames, out);
                exported.add(out);
            } catch (Exception e) {
                errors.add("PDF: " + rootMessage(e));
            }
        }

        if (formats.contains(ExportFormat.PNG) && chartSnapshot != null) {
            Path out = outputDir.resolve(base + "-chart.png");
            try {
                DynoPngExporter.write(chartSnapshot, out);
                exported.add(out);
            } catch (Exception e) {
                errors.add("PNG: " + rootMessage(e));
            }
        }

        if (formats.contains(ExportFormat.CSV)) {
            Path out = outputDir.resolve(base + ".csv");
            try {
                DynoCsvExporter.write(safeFrames, out);
                exported.add(out);
            } catch (Exception e) {
                errors.add("CSV: " + rootMessage(e));
            }
        }

        if (formats.contains(ExportFormat.JSON)) {
            Path out = outputDir.resolve(base + ".json");
            try {
                DynoJsonExporter.writeSingleRun(detail, safeFrames, out);
                exported.add(out);
            } catch (Exception e) {
                errors.add("JSON: " + rootMessage(e));
            }
        }

        return new ExportResult(exported, errors);
    }

    /**
     * Exports a compare view in all requested formats.
     *
     * For CSV, one file per run is produced (dyno-run-NNNNN.csv).
     * For JSON, a single file containing all runs is produced.
     *
     * @param compareResponse  compare API response (1–4 runs with detail + frames)
     * @param chartSnapshot    FX snapshot; may be null
     * @param formats          set of formats to produce
     * @param outputDir        target directory
     */
    public static ExportResult exportCompare(
        CompareRunsResponseDto compareResponse,
        WritableImage chartSnapshot,
        Set<ExportFormat> formats,
        Path outputDir
    ) {
        List<Path> exported = new ArrayList<Path>();
        List<String> errors = new ArrayList<String>();

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            errors.add("Cannot create output directory: " + e.getMessage());
            return new ExportResult(exported, errors);
        }

        List<ComparedRunDto> runs = compareResponse != null && compareResponse.getRuns() != null
            ? compareResponse.getRuns() : Collections.<ComparedRunDto>emptyList();

        // Build filename base from run IDs
        StringBuilder idPart = new StringBuilder("dyno-compare");
        for (int i = 0; i < runs.size(); i++) {
            ComparedRunDto cr = runs.get(i);
            if (cr.getRun() != null && cr.getRun().getRunId() != null) {
                idPart.append('-').append(String.format("%05d", cr.getRun().getRunId()));
            }
        }
        String base = idPart.toString();

        if (formats.contains(ExportFormat.PDF)) {
            Path out = outputDir.resolve(base + ".pdf");
            try {
                DynoPdfExporter.writeCompare(compareResponse, out);
                exported.add(out);
            } catch (Exception e) {
                errors.add("PDF: " + rootMessage(e));
            }
        }

        if (formats.contains(ExportFormat.PNG) && chartSnapshot != null) {
            Path out = outputDir.resolve(base + "-chart.png");
            try {
                DynoPngExporter.write(chartSnapshot, out);
                exported.add(out);
            } catch (Exception e) {
                errors.add("PNG: " + rootMessage(e));
            }
        }

        if (formats.contains(ExportFormat.CSV)) {
            // One CSV per run
            for (int i = 0; i < runs.size(); i++) {
                ComparedRunDto cr = runs.get(i);
                if (cr.getRun() == null || cr.getRun().getRunId() == null) continue;
                List<RunHistoryFrameDto> frames = cr.getFrames() != null
                    ? cr.getFrames() : Collections.<RunHistoryFrameDto>emptyList();
                Path out = outputDir.resolve(
                    "dyno-run-" + String.format("%05d", cr.getRun().getRunId()) + ".csv");
                try {
                    DynoCsvExporter.write(frames, out);
                    exported.add(out);
                } catch (Exception e) {
                    errors.add("CSV run-" + cr.getRun().getRunId() + ": " + rootMessage(e));
                }
            }
        }

        if (formats.contains(ExportFormat.JSON)) {
            Path out = outputDir.resolve(base + ".json");
            try {
                DynoJsonExporter.writeCompare(compareResponse, out);
                exported.add(out);
            } catch (Exception e) {
                errors.add("JSON: " + rootMessage(e));
            }
        }

        return new ExportResult(exported, errors);
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return (msg != null && !msg.trim().isEmpty()) ? msg.trim() : t.getClass().getSimpleName();
    }
}
