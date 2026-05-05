package com.dyno.health;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class OperatorStatusMapper {
    private OperatorStatusMapper() {
    }

    public static OperatorStatusModel initial() {
        return new OperatorStatusModel(
            OperatorStatusModel.OverallState.UNAVAILABLE,
            false,
            "unknown",
            false,
            false,
            false,
            "Checking backend status...",
            "Automatic status refresh active",
            null,
            Collections.<String>emptyList()
        );
    }

    public static OperatorStatusModel unavailable() {
        return new OperatorStatusModel(
            OperatorStatusModel.OverallState.UNAVAILABLE,
            false,
            "unknown",
            false,
            false,
            false,
            "Backend unavailable",
            "Status checks retry automatically",
            null,
            Collections.<String>emptyList()
        );
    }

    public static OperatorStatusModel fromHealth(StartupHealthDto health) {
        if (health == null) {
            return unavailable();
        }

        List<StartupCheckDto> checks = health.getChecks() == null
            ? Collections.<StartupCheckDto>emptyList()
            : health.getChecks();
        StartupCheckDto storageCheck = findCheck(checks, "database_path");
        StartupCheckDto serialCheck = findCheck(checks, "serial_port");
        StartupCheckDto ambientCheck = findCheck(checks, "bme280_i2c");

        boolean storageReady = isOk(storageCheck);
        boolean serialDegraded = isNonOk(serialCheck);
        boolean ambientDegraded = isNonOk(ambientCheck);
        String sourceMode = normalize(health.getSourceMode(), "unknown");
        boolean replayMode = "replay".equals(sourceMode);
        int nonOkChecks = countNonOkChecks(checks);

        List<String> warnings = new ArrayList<String>();
        if (!storageReady) {
            warnings.add("Storage unavailable");
        }
        if (serialDegraded) {
            warnings.add("Serial input unavailable — retrying");
        }
        if (ambientDegraded) {
            warnings.add("Ambient sensor unavailable — fallback values in use");
        }

        OperatorStatusModel.OverallState overallState = "ok".equals(normalize(health.getStatus(), "unknown"))
            ? OperatorStatusModel.OverallState.READY
            : OperatorStatusModel.OverallState.DEGRADED;

        String primaryMessage;
        if (!storageReady) {
            primaryMessage = "Storage unavailable";
        } else if (serialDegraded) {
            primaryMessage = "Serial input unavailable — retrying";
        } else if (ambientDegraded) {
            primaryMessage = "Ambient sensor unavailable — fallback values in use";
        } else if (replayMode) {
            primaryMessage = "Replay mode active";
        } else if (overallState == OperatorStatusModel.OverallState.READY) {
            primaryMessage = "Backend ready";
        } else {
            primaryMessage = "Backend degraded";
        }

        List<String> secondaryParts = new ArrayList<String>();
        if (replayMode && !"Replay mode active".equals(primaryMessage)) {
            secondaryParts.add("Replay mode active");
        } else if ("live".equals(sourceMode)) {
            secondaryParts.add("Live mode active");
        }

        String warningSummary = null;
        if (nonOkChecks > 0) {
            warningSummary = nonOkChecks == 1
                ? "1 startup warning active"
                : nonOkChecks + " startup warnings active";
            if (!warningSummary.equals(primaryMessage)) {
                secondaryParts.add(warningSummary);
            }
        } else if (overallState == OperatorStatusModel.OverallState.READY) {
            secondaryParts.add("Health checks passing");
        }

        if (secondaryParts.isEmpty()) {
            secondaryParts.add("Health checks passing");
        }

        return new OperatorStatusModel(
            overallState,
            true,
            sourceMode,
            storageReady,
            serialDegraded,
            ambientDegraded,
            primaryMessage,
            join(secondaryParts, " | "),
            warningSummary,
            warnings
        );
    }

    private static StartupCheckDto findCheck(List<StartupCheckDto> checks, String name) {
        for (StartupCheckDto check : checks) {
            if (check != null && name.equals(check.getName())) {
                return check;
            }
        }
        return null;
    }

    private static int countNonOkChecks(List<StartupCheckDto> checks) {
        int count = 0;
        for (StartupCheckDto check : checks) {
            if (isNonOk(check)) {
                count += 1;
            }
        }
        return count;
    }

    private static boolean isOk(StartupCheckDto check) {
        return check != null && "ok".equals(normalize(check.getStatus(), ""));
    }

    private static boolean isNonOk(StartupCheckDto check) {
        return check != null && !"ok".equals(normalize(check.getStatus(), ""));
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(separator);
            }
            builder.append(value.trim());
        }
        return builder.toString();
    }
}
