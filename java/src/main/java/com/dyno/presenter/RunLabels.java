package com.dyno.presenter;

import com.dyno.history.RunHistoryDetailDto;
import com.dyno.history.RunHistorySummaryDto;

/**
 * Single source of truth for operator-facing run identifiers.
 * Runs are referred to as {plate}-{run_no}; runs recorded without a plate
 * fall back to RUN-{run_id}.
 */
public final class RunLabels {
    private RunLabels() {
    }

    public static String displayId(RunHistorySummaryDto run) {
        if (run == null) {
            return "RUN";
        }
        return displayId(run.getDisplayId(), run.getLicensePlate(), run.getRunNo(), run.getRunId());
    }

    public static String displayId(RunHistoryDetailDto run) {
        if (run == null) {
            return "RUN";
        }
        return displayId(run.getDisplayId(), run.getLicensePlate(), run.getRunNo(), run.getRunId());
    }

    public static String displayId(String serverDisplayId, String licensePlate, Long runNo, Long runId) {
        if (serverDisplayId != null && !serverDisplayId.trim().isEmpty()) {
            return serverDisplayId.trim();
        }
        boolean hasPlate = licensePlate != null && !licensePlate.trim().isEmpty();
        if (hasPlate && runNo != null) {
            return licensePlate.trim() + "-" + runNo;
        }
        return runId != null ? String.format("RUN-%05d", runId) : "RUN";
    }
}
