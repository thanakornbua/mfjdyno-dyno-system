package com.dyno.presenter;

public final class GaugeThresholdProfile {
    public static final class Assessment {
        private final OperatorViewModel.Tone tone;
        private final String stateText;

        public Assessment(OperatorViewModel.Tone tone, String stateText) {
            this.tone = tone;
            this.stateText = stateText;
        }

        public OperatorViewModel.Tone getTone() {
            return tone;
        }

        public String getStateText() {
            return stateText;
        }
    }

    public interface Evaluator {
        Assessment evaluate(double value);
    }

    private final Evaluator evaluator;

    private GaugeThresholdProfile(Evaluator evaluator) {
        this.evaluator = evaluator;
    }

    public Assessment evaluate(double value) {
        return evaluator.evaluate(value);
    }

    public static GaugeThresholdProfile lambdaProfile() {
        return new GaugeThresholdProfile(value -> {
            if (value < 0.85d) {
                return new Assessment(OperatorViewModel.Tone.ALERT, "RICH");
            }
            if (value < 0.97d) {
                return new Assessment(OperatorViewModel.Tone.CAUTION, "RICH");
            }
            if (value <= 1.03d) {
                return new Assessment(OperatorViewModel.Tone.NORMAL, "NORMAL");
            }
            if (value <= 1.10d) {
                return new Assessment(OperatorViewModel.Tone.CAUTION, "LEAN");
            }
            return new Assessment(OperatorViewModel.Tone.ALERT, "LEAN");
        });
    }

    public static GaugeThresholdProfile ambientTempProfile() {
        return new GaugeThresholdProfile(value -> {
            if (value >= 45.0d) {
                return new Assessment(OperatorViewModel.Tone.ALERT, "HOT");
            }
            if (value >= 35.0d) {
                return new Assessment(OperatorViewModel.Tone.CAUTION, "WARM");
            }
            return new Assessment(OperatorViewModel.Tone.NORMAL, "NORMAL");
        });
    }
}
