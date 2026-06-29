package com.dyno.presenter;

import com.dyno.history.CompareRunsResponseDto;
import com.dyno.history.ComparedRunDto;
import com.dyno.history.RunHistoryDetailDto;
import com.dyno.history.RunHistoryFrameDto;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CompareDisplayMapper {
    private static final DecimalFormat ONE_DECIMAL = new DecimalFormat("0.0");
    private static final String[] POWER_COLORS = {
        "#2EC4FF",
        "#7CFFCB",
        "#B4F36C",
        "#69B7FF"
    };
    private static final String[] TORQUE_COLORS = {
        "#FFB547",
        "#F973B0",
        "#A78BFA",
        "#F59E0B"
    };

    private CompareDisplayMapper() {
    }

    public static CompareDisplayState map(CompareRunsResponseDto response) {
        return map(response, ChartScaleSettings.defaults());
    }

    public static CompareDisplayState map(CompareRunsResponseDto response, ChartScaleSettings scaleSettings) {
        List<ComparedRunDto> comparedRuns = response == null || response.getRuns() == null
            ? Collections.<ComparedRunDto>emptyList()
            : response.getRuns();
        ChartScaleSettings safeScale = scaleSettings == null ? ChartScaleSettings.defaults() : scaleSettings;

        List<ChartSeriesModel> series = new ArrayList<ChartSeriesModel>();
        ArrayList<String> summaryLines = new ArrayList<String>();
        ArrayList<String> selectedRuns = new ArrayList<String>();
        long datasetToken = 10_000L;

        for (int index = 0; index < comparedRuns.size(); index++) {
            ComparedRunDto comparedRun = comparedRuns.get(index);
            RunHistoryDetailDto run = comparedRun.getRun();
            if (run == null || run.getRunId() == null) {
                continue;
            }

            String runLabel = runLabel(run);
            selectedRuns.add(runLabel);
            datasetToken = datasetToken * 31L + run.getRunId().longValue();

            List<ChartPlotPoint> powerPoints = new ArrayList<ChartPlotPoint>();
            List<ChartPlotPoint> torquePoints = new ArrayList<ChartPlotPoint>();
            List<RunHistoryFrameDto> frames = comparedRun.getFrames() == null
                ? Collections.<RunHistoryFrameDto>emptyList()
                : comparedRun.getFrames();
            for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
                RunHistoryFrameDto frame = frames.get(frameIndex);
                if (frame.getEngineRpm() == null || frame.getEngineRpm().doubleValue() <= 0.0d) {
                    continue;
                }
                if (frame.getPowerHp() != null && frame.getPowerHp().doubleValue() >= 0.0d) {
                    powerPoints.add(new ChartPlotPoint(frame.getEngineRpm().doubleValue(), frame.getPowerHp().doubleValue()));
                }
                if (frame.getTorqueNm() != null && frame.getTorqueNm().doubleValue() >= 0.0d) {
                    torquePoints.add(new ChartPlotPoint(frame.getEngineRpm().doubleValue(), frame.getTorqueNm().doubleValue()));
                }
            }

            if (!powerPoints.isEmpty()) {
                series.add(new ChartSeriesModel(
                    "compare-power-" + run.getRunId(),
                    runLabel + " Power",
                    POWER_COLORS[index % POWER_COLORS.length],
                    powerPoints
                ));
            }
            if (!torquePoints.isEmpty()) {
                series.add(new ChartSeriesModel(
                    "compare-torque-" + run.getRunId(),
                    runLabel + " Torque",
                    TORQUE_COLORS[index % TORQUE_COLORS.length],
                    torquePoints
                ));
            }

            RunHistoryFrameDto peakFrame = peakPowerFrame(comparedRun);
            RunHistoryFrameDto fastestFrame = RunMetrics.fastestFrame(comparedRun);
            Double timeToFastest = RunMetrics.timeToFrameSeconds(frames, fastestFrame);
            summaryLines.add(
                runLabel
                    + "  "
                    + safeValue(peakFrame == null ? null : peakFrame.getPowerHp(), "HP")
                    + " @ "
                    + safeValue(peakFrame == null ? null : peakFrame.getEngineRpm(), "RPM")
                    + "  |  "
                    + safeValue(run.getPeakTorqueNm(), "Nm")
                    + " @ "
                    + safeValue(run.getPeakTorqueRpm(), "RPM")
                    + "  |  "
                    + "Max speed "
                    + safeValue(frameValue(fastestFrame, Metric.SPEED), "km/h")
                    + " in "
                    + safeValue(timeToFastest, "sec")
                    + "  |  "
                    + "AFR "
                    + safeValue(frameValue(peakFrame, Metric.AFR), "")
                    + "  |  "
                    + ambientText(peakFrame)
            );
        }

        String summaryPrimary;
        String axisSummary;
        String statusText;
        if (selectedRuns.isEmpty()) {
            summaryPrimary = "No comparison loaded";
            axisSummary = "No stored runs selected.";
            statusText = "Select 1 to 4 stored runs to compare them.";
        } else {
            summaryPrimary = "Comparing " + selectedRuns.size() + " stored run"
                + (selectedRuns.size() == 1 ? "" : "s");
            axisSummary = join(selectedRuns, "  vs  ");
            statusText = "Stored-run comparison overlay active.";
        }

        LiveDynoChartModel chartModel = new LiveDynoChartModel(
            datasetToken,
            selectedRuns.isEmpty() ? "COMPARE MODE" : "COMPARE MODE",
            "Compare Power / Torque vs Engine RPM",
            axisSummary,
            summaryPrimary,
            "Engine RPM (RPM)",
            "Power / Torque",
            series,
            statusText,
            false,
            0,
            Collections.<ChartSeriesModel>emptyList(),
            safeScale
        );

        return new CompareDisplayState(
            chartModel,
            summaryPrimary,
            summaryLines.isEmpty() ? "Select runs to load stored comparison data." : join(summaryLines, "\n")
        );
    }

    public static RunHistoryFrameDto peakPowerFrame(ComparedRunDto comparedRun) {
        return RunMetrics.peakPowerFrame(comparedRun);
    }

    private static final java.time.format.DateTimeFormatter DATE_DISPLAY =
        java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm");

    public static String runLabel(RunHistoryDetailDto run) {
        if (run == null || run.getRunId() == null) {
            return "RUN";
        }
        String vehicle = run.getVehicleName();
        String plate = run.getLicensePlate();
        boolean hasVehicle = vehicle != null && !vehicle.trim().isEmpty();
        boolean hasPlate = plate != null && !plate.trim().isEmpty();
        if (hasVehicle) {
            return hasPlate
                ? vehicle.trim() + " (" + plate.trim() + ")"
                : vehicle.trim();
        }
        if (hasPlate) {
            return "Plate: " + plate.trim();
        }
        String datePart = formatRunDate(run.getDate());
        return datePart != null
            ? "Run #" + run.getRunId() + " " + datePart
            : "Run #" + run.getRunId();
    }

    private static String formatRunDate(String isoDate) {
        if (isoDate == null || isoDate.trim().isEmpty()) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(isoDate).format(DATE_DISPLAY);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String safeValue(Double value, String unit) {
        if (value == null) {
            return "—";
        }
        String text = ONE_DECIMAL.format(value.doubleValue());
        if (unit == null || unit.trim().isEmpty()) {
            return text;
        }
        return text + " " + unit;
    }

    public static String ambientText(RunHistoryFrameDto frame) {
        if (frame == null) {
            return "Ambient —";
        }
        return "Ambient "
            + safeValue(frame.getAmbientTempC(), "C")
            + " / "
            + safeValue(frame.getHumidityPct(), "%")
            + " / "
            + safeValue(frame.getPressureHpa(), "hPa");
    }

    public static Double frameValue(RunHistoryFrameDto frame, Metric metric) {
        if (frame == null || metric == null) {
            return null;
        }
        switch (metric) {
            case SPEED:
                return frame.getSpeedKmh();
            case AFR:
                return frame.getAfr();
            default:
                return null;
        }
    }

    private static String join(List<String> values, String delimiter) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                builder.append(delimiter);
            }
            builder.append(values.get(index));
        }
        return builder.toString();
    }

    public enum Metric {
        SPEED,
        AFR
    }
}
