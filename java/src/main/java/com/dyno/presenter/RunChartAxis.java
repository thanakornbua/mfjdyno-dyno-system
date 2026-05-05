package com.dyno.presenter;

import com.dyno.model.FrameMessage;

public enum RunChartAxis {
    ENGINE_RPM("Engine RPM", "RPM", true, true, "#7CFFCB") {
        @Override
        public Double xValue(FrameMessage frame, Double runStartTs) {
            return frame != null ? frame.getEngineRpm() : null;
        }

        @Override
        public Double yValue(FrameMessage frame) {
            return frame != null ? frame.getEngineRpm() : null;
        }
    },
    TIME("Time", "s", true, false, "#69B7FF") {
        @Override
        public Double xValue(FrameMessage frame, Double runStartTs) {
            if (frame == null || frame.getTs() == null || runStartTs == null) {
                return null;
            }
            return Double.valueOf(frame.getTs().doubleValue() - runStartTs.doubleValue());
        }

        @Override
        public Double yValue(FrameMessage frame) {
            return null;
        }
    },
    SPEED("Speed", "km/h", true, false, "#B4F36C") {
        @Override
        public Double xValue(FrameMessage frame, Double runStartTs) {
            return frame != null ? frame.getSpeedKmh() : null;
        }

        @Override
        public Double yValue(FrameMessage frame) {
            return null;
        }
    },
    POWER("Power", "HP", false, true, "#2EC4FF") {
        @Override
        public Double xValue(FrameMessage frame, Double runStartTs) {
            return null;
        }

        @Override
        public Double yValue(FrameMessage frame) {
            if (frame == null) {
                return null;
            }
            return frame.getPowerCorrHp() != null ? frame.getPowerCorrHp() : frame.getPowerHp();
        }

        @Override
        public boolean shouldPlot(Double value) {
            return value != null && value.doubleValue() >= 0.0d;
        }
    },
    TORQUE("Torque", "Nm", false, true, "#FFB547") {
        @Override
        public Double xValue(FrameMessage frame, Double runStartTs) {
            return null;
        }

        @Override
        public Double yValue(FrameMessage frame) {
            if (frame == null) {
                return null;
            }
            return frame.getTorqueCorrNm() != null ? frame.getTorqueCorrNm() : frame.getTorqueNm();
        }

        @Override
        public boolean shouldPlot(Double value) {
            return value != null && value.doubleValue() >= 0.0d;
        }
    },
    AFR("AFR", "AFR", false, true, "#F973B0") {
        @Override
        public Double xValue(FrameMessage frame, Double runStartTs) {
            return null;
        }

        @Override
        public Double yValue(FrameMessage frame) {
            return frame != null ? frame.getAfr() : null;
        }
    },
    LAMBDA("Lambda", "λ", false, true, "#A78BFA") {
        @Override
        public Double xValue(FrameMessage frame, Double runStartTs) {
            return null;
        }

        @Override
        public Double yValue(FrameMessage frame) {
            if (frame == null) {
                return null;
            }
            if (frame.getLambda() != null) {
                return frame.getLambda();
            }
            if (frame.getAfr() != null) {
                return Double.valueOf(frame.getAfr().doubleValue() / 14.7d);
            }
            return null;
        }
    };

    private final String label;
    private final String unit;
    private final boolean xAxis;
    private final boolean yAxis;
    private final String colorHex;

    RunChartAxis(String label, String unit, boolean xAxis, boolean yAxis, String colorHex) {
        this.label = label;
        this.unit = unit;
        this.xAxis = xAxis;
        this.yAxis = yAxis;
        this.colorHex = colorHex;
    }

    public abstract Double xValue(FrameMessage frame, Double runStartTs);

    public abstract Double yValue(FrameMessage frame);

    public boolean supportsXAxis() {
        return xAxis;
    }

    public boolean supportsYAxis() {
        return yAxis;
    }

    public boolean shouldPlot(Double value) {
        return value != null;
    }

    public String getLabel() {
        return label;
    }

    public String getUnit() {
        return unit;
    }

    public String getColorHex() {
        return colorHex;
    }

    public String axisLabel() {
        return unit == null || unit.trim().isEmpty()
            ? label
            : label + " (" + unit + ")";
    }

    public String legendLabel() {
        return axisLabel();
    }

    @Override
    public String toString() {
        return label;
    }
}
