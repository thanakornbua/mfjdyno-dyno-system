package com.dyno.presenter;

import com.dyno.history.ComparedRunDto;
import com.dyno.history.RunHistoryFrameDto;

import java.util.Collections;
import java.util.List;

public final class RunMetrics {
    private RunMetrics() {
    }

    public static RunHistoryFrameDto peakPowerFrame(ComparedRunDto comparedRun) {
        return peakPowerFrame(comparedRun == null ? null : comparedRun.getFrames());
    }

    public static RunHistoryFrameDto peakPowerFrame(List<RunHistoryFrameDto> frames) {
        RunHistoryFrameDto best = null;
        double bestPower = Double.NEGATIVE_INFINITY;
        List<RunHistoryFrameDto> safeFrames = frames == null
            ? Collections.<RunHistoryFrameDto>emptyList()
            : frames;
        for (int index = 0; index < safeFrames.size(); index++) {
            RunHistoryFrameDto frame = safeFrames.get(index);
            if (frame == null || frame.getPowerHp() == null) {
                continue;
            }
            double value = frame.getPowerHp().doubleValue();
            if (value < 0.0d) {
                continue;
            }
            if (best == null || value > bestPower) {
                best = frame;
                bestPower = value;
            }
        }
        return best;
    }

    public static RunHistoryFrameDto fastestFrame(ComparedRunDto comparedRun) {
        return fastestFrame(comparedRun == null ? null : comparedRun.getFrames());
    }

    public static RunHistoryFrameDto fastestFrame(List<RunHistoryFrameDto> frames) {
        RunHistoryFrameDto best = null;
        double bestSpeed = Double.NEGATIVE_INFINITY;
        List<RunHistoryFrameDto> safeFrames = frames == null
            ? Collections.<RunHistoryFrameDto>emptyList()
            : frames;
        for (int index = 0; index < safeFrames.size(); index++) {
            RunHistoryFrameDto frame = safeFrames.get(index);
            if (frame == null || frame.getSpeedKmh() == null) {
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

    public static Double timeToFrameSeconds(List<RunHistoryFrameDto> frames, RunHistoryFrameDto target) {
        if (frames == null || frames.isEmpty() || target == null || target.getTsMs() == null) {
            return null;
        }
        Long startMs = null;
        for (int index = 0; index < frames.size(); index++) {
            RunHistoryFrameDto frame = frames.get(index);
            if (frame != null && frame.getTsMs() != null) {
                startMs = frame.getTsMs();
                break;
            }
        }
        if (startMs == null) {
            return null;
        }
        return Double.valueOf((target.getTsMs().doubleValue() - startMs.doubleValue()) / 1000.0d);
    }
}
