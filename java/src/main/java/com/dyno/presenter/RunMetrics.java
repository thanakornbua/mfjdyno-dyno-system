package com.dyno.presenter;

import com.dyno.history.ComparedRunDto;
import com.dyno.history.RunHistoryDetailDto;
import com.dyno.history.RunHistoryFrameDto;

import java.util.Collections;
import java.util.List;

public final class RunMetrics {
    private RunMetrics() {
    }

    /**
     * The frame nearest the run's stored peak-power RPM, used only to read
     * side data (AFR, ambient conditions) at that point — the peak
     * power/torque numbers themselves must come from the backend-computed
     * {@code peak_power_hp}/{@code peak_power_rpm} on the run DTO, not from
     * re-scanning frames, so every surface (dashboard, history, compare,
     * PDF) reports the same peak.
     */
    public static RunHistoryFrameDto frameAtPeakPower(ComparedRunDto comparedRun) {
        if (comparedRun == null) {
            return null;
        }
        return frameAtPeakPower(comparedRun.getRun(), comparedRun.getFrames());
    }

    public static RunHistoryFrameDto frameAtPeakPower(RunHistoryDetailDto run, List<RunHistoryFrameDto> frames) {
        return frameNearestRpm(frames, run == null ? null : run.getPeakPowerRpm());
    }

    private static RunHistoryFrameDto frameNearestRpm(List<RunHistoryFrameDto> frames, Double targetRpm) {
        if (targetRpm == null) {
            return null;
        }
        double target = targetRpm.doubleValue();
        List<RunHistoryFrameDto> safeFrames = frames == null
            ? Collections.<RunHistoryFrameDto>emptyList()
            : frames;
        RunHistoryFrameDto best = null;
        double bestDelta = Double.POSITIVE_INFINITY;
        for (int index = 0; index < safeFrames.size(); index++) {
            RunHistoryFrameDto frame = safeFrames.get(index);
            // The stored peak is only ever computed over recording-state
            // frames, so restrict the match to those too.
            if (frame == null || !isRecording(frame) || frame.getEngineRpm() == null) {
                continue;
            }
            double delta = Math.abs(frame.getEngineRpm().doubleValue() - target);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = frame;
            }
        }
        return best;
    }

    public static RunHistoryFrameDto fastestFrame(ComparedRunDto comparedRun) {
        return fastestFrame(comparedRun == null ? null : comparedRun.getFrames());
    }

    private static final String RUN_STATE_RECORDING = "recording";

    public static RunHistoryFrameDto fastestFrame(List<RunHistoryFrameDto> frames) {
        RunHistoryFrameDto best = null;
        double bestSpeed = Double.NEGATIVE_INFINITY;
        List<RunHistoryFrameDto> safeFrames = frames == null
            ? Collections.<RunHistoryFrameDto>emptyList()
            : frames;
        for (int index = 0; index < safeFrames.size(); index++) {
            RunHistoryFrameDto frame = safeFrames.get(index);
            // Stored frames include prepended pre-run idle/armed frames; a
            // bogus pre-run speed reading must not win "fastest".
            if (frame == null || !isRecording(frame) || frame.getSpeedKmh() == null) {
                continue;
            }
            double value = frame.getSpeedKmh().doubleValue();
            if (value < 0.0d) {
                continue;
            }
            if (best == null || value > bestSpeed) {
                best = frame;
                bestSpeed = value;
            }
        }
        return best;
    }

    /**
     * Elapsed seconds from the start of recording (not from the start of
     * the stored frame list, which includes prepended pre-run idle/armed
     * frames) to {@code target}.
     */
    public static Double timeToFrameSeconds(List<RunHistoryFrameDto> frames, RunHistoryFrameDto target) {
        if (frames == null || frames.isEmpty() || target == null || target.getTsMs() == null) {
            return null;
        }
        Long startMs = null;
        for (int index = 0; index < frames.size(); index++) {
            RunHistoryFrameDto frame = frames.get(index);
            if (frame != null && frame.getTsMs() != null && isRecording(frame)) {
                startMs = frame.getTsMs();
                break;
            }
        }
        if (startMs == null) {
            // Defensive fallback: no recording-state frame found (shouldn't
            // happen for a finalized run) — fall back to the first frame.
            for (int index = 0; index < frames.size(); index++) {
                RunHistoryFrameDto frame = frames.get(index);
                if (frame != null && frame.getTsMs() != null) {
                    startMs = frame.getTsMs();
                    break;
                }
            }
        }
        if (startMs == null) {
            return null;
        }
        return Double.valueOf((target.getTsMs().doubleValue() - startMs.doubleValue()) / 1000.0d);
    }

    private static boolean isRecording(RunHistoryFrameDto frame) {
        return RUN_STATE_RECORDING.equals(frame.getRunState());
    }
}
