package com.dyno.presenter;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RunIdentityState {
    public static final class PreparedRun {
        private final String plate;
        private final int runNumber;
        private final String runLabel;

        public PreparedRun(String plate, int runNumber, String runLabel) {
            this.plate = plate;
            this.runNumber = runNumber;
            this.runLabel = runLabel;
        }

        public String getPlate() {
            return plate;
        }

        public int getRunNumber() {
            return runNumber;
        }

        public String getRunLabel() {
            return runLabel;
        }
    }

    private final Map<String, Integer> runCounters = new LinkedHashMap<String, Integer>();
    private String lastUsedPlate = "";
    private PreparedRun preparedRun;
    private String currentRunLabel = "NO RUN";
    private String currentPlate = "—";

    public PreparedRun preview(String plateInput) {
        String plate = normalizePlate(plateInput);
        int nextRunNumber = runCounters.getOrDefault(plate, Integer.valueOf(0)).intValue() + 1;
        return new PreparedRun(plate, nextRunNumber, formatLabel(plate, nextRunNumber));
    }

    public PreparedRun prepare(String plateInput) {
        preparedRun = preview(plateInput);
        return preparedRun;
    }

    public PreparedRun getPreparedRun() {
        return preparedRun;
    }

    public boolean hasPreparedRun() {
        return preparedRun != null;
    }

    public PreparedRun commitPreparedRun() {
        PreparedRun next = preparedRun;
        if (next == null) {
            next = prepare(lastUsedPlate);
        }
        preparedRun = null;
        runCounters.put(next.getPlate(), Integer.valueOf(next.getRunNumber()));
        lastUsedPlate = next.getPlate();
        currentRunLabel = next.getRunLabel();
        currentPlate = next.getPlate();
        return next;
    }

    public String getLastUsedPlate() {
        return lastUsedPlate;
    }

    public String getDisplayRunLabel() {
        if (!"NO RUN".equals(currentRunLabel)) {
            return currentRunLabel;
        }
        if (preparedRun != null) {
            return preparedRun.getRunLabel();
        }
        return currentRunLabel;
    }

    public String getCurrentPlate() {
        if (!"—".equals(currentPlate)) {
            return currentPlate;
        }
        if (preparedRun != null) {
            return preparedRun.getPlate();
        }
        return currentPlate;
    }

    private String normalizePlate(String input) {
        String plate = input == null ? "" : input.trim();
        if (plate.isEmpty()) {
            if (lastUsedPlate != null && !lastUsedPlate.trim().isEmpty()) {
                return lastUsedPlate.trim();
            }
            return "NO-PLATE";
        }
        return plate;
    }

    private String formatLabel(String plate, int runNumber) {
        return plate + "-" + String.format("%02d", Integer.valueOf(runNumber));
    }
}
