package com.dyno.presenter;

import com.dyno.model.RunPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class OperatorViewModel {
    public enum Tone {
        NORMAL,
        CAUTION,
        ALERT,
        FAULT,
        UNAVAILABLE,
        ACCENT
    }

    public static final class BannerModel {
        private final String title;
        private final String message;
        private final Tone tone;

        public BannerModel(String title, String message, Tone tone) {
            this.title = title;
            this.message = message;
            this.tone = tone;
        }

        public String getTitle() {
            return title;
        }

        public String getMessage() {
            return message;
        }

        public Tone getTone() {
            return tone;
        }
    }

    public static final class GaugeModel {
        private final String label;
        private final String valueText;
        private final String unitText;
        private final String stateText;
        private final Tone tone;

        public GaugeModel(String label, String valueText, String unitText, String stateText, Tone tone) {
            this.label = label;
            this.valueText = valueText;
            this.unitText = unitText;
            this.stateText = stateText;
            this.tone = tone;
        }

        public String getLabel() {
            return label;
        }

        public String getValueText() {
            return valueText;
        }

        public String getUnitText() {
            return unitText;
        }

        public String getStateText() {
            return stateText;
        }

        public Tone getTone() {
            return tone;
        }
    }

    public static final class MetricTileModel {
        private final String label;
        private final String valueText;
        private final String unitText;
        private final String footerText;
        private final Tone tone;

        public MetricTileModel(String label, String valueText, String unitText, String footerText, Tone tone) {
            this.label = label;
            this.valueText = valueText;
            this.unitText = unitText;
            this.footerText = footerText;
            this.tone = tone;
        }

        public String getLabel() {
            return label;
        }

        public String getValueText() {
            return valueText;
        }

        public String getUnitText() {
            return unitText;
        }

        public String getFooterText() {
            return footerText;
        }

        public Tone getTone() {
            return tone;
        }
    }

    public static final class SecondaryMetricModel {
        private final String label;
        private final String valueText;
        private final String unitText;
        private final Tone tone;

        public SecondaryMetricModel(String label, String valueText, String unitText, Tone tone) {
            this.label = label;
            this.valueText = valueText;
            this.unitText = unitText;
            this.tone = tone;
        }

        public String getLabel() {
            return label;
        }

        public String getValueText() {
            return valueText;
        }

        public String getUnitText() {
            return unitText;
        }

        public Tone getTone() {
            return tone;
        }
    }

    private final String connectionText;
    private final String stateText;
    private final String runLabel;
    private final String plateText;
    private final String chartCaption;
    private final String peakPowerText;
    private final String peakTorqueText;
    private final boolean recording;
    private final boolean startEnabled;
    private final boolean stopEnabled;
    private final boolean runModeEnabled;
    private final boolean printEnabled;
    private final BannerModel banner;
    private final GaugeModel lambdaGauge;
    private final GaugeModel tempGauge;
    private final GaugeModel o2Gauge;
    private final MetricTileModel powerTile;
    private final MetricTileModel torqueTile;
    private final MetricTileModel rpmTile;
    private final MetricTileModel afrTile;
    private final List<SecondaryMetricModel> secondaryMetrics;
    private final List<RunPoint> chartPoints;
    private DialValues dialValues;

    /** Raw numeric telemetry for dial gauges (formatted tile text is lossy). */
    public static final class DialValues {
        private final Double engineRpm;
        private final Double speedKmh;
        private final Double powerHp;
        private final Double torqueNm;
        private final Double afr;

        public DialValues(Double engineRpm, Double speedKmh, Double powerHp, Double torqueNm, Double afr) {
            this.engineRpm = engineRpm;
            this.speedKmh = speedKmh;
            this.powerHp = powerHp;
            this.torqueNm = torqueNm;
            this.afr = afr;
        }

        public Double getEngineRpm() {
            return engineRpm;
        }

        public Double getSpeedKmh() {
            return speedKmh;
        }

        public Double getPowerHp() {
            return powerHp;
        }

        public Double getTorqueNm() {
            return torqueNm;
        }

        public Double getAfr() {
            return afr;
        }
    }

    /** Fluent attach used by TelemetryPresenter right after construction. */
    public OperatorViewModel withDialValues(DialValues values) {
        this.dialValues = values;
        return this;
    }

    /** May be null (older constructors / tests). */
    public DialValues getDialValues() {
        return dialValues;
    }

    public OperatorViewModel(
        String connectionText,
        String stateText,
        String runLabel,
        String plateText,
        String chartCaption,
        String peakPowerText,
        String peakTorqueText,
        boolean recording,
        boolean startEnabled,
        boolean stopEnabled,
        boolean runModeEnabled,
        boolean printEnabled,
        BannerModel banner,
        GaugeModel lambdaGauge,
        GaugeModel tempGauge,
        GaugeModel o2Gauge,
        MetricTileModel powerTile,
        MetricTileModel torqueTile,
        MetricTileModel rpmTile,
        MetricTileModel afrTile,
        List<SecondaryMetricModel> secondaryMetrics,
        List<RunPoint> chartPoints
    ) {
        this.connectionText = connectionText;
        this.stateText = stateText;
        this.runLabel = runLabel;
        this.plateText = plateText;
        this.chartCaption = chartCaption;
        this.peakPowerText = peakPowerText;
        this.peakTorqueText = peakTorqueText;
        this.recording = recording;
        this.startEnabled = startEnabled;
        this.stopEnabled = stopEnabled;
        this.runModeEnabled = runModeEnabled;
        this.printEnabled = printEnabled;
        this.banner = banner;
        this.lambdaGauge = lambdaGauge;
        this.tempGauge = tempGauge;
        this.o2Gauge = o2Gauge;
        this.powerTile = powerTile;
        this.torqueTile = torqueTile;
        this.rpmTile = rpmTile;
        this.afrTile = afrTile;
        this.secondaryMetrics = Collections.unmodifiableList(new ArrayList<SecondaryMetricModel>(secondaryMetrics));
        this.chartPoints = Collections.unmodifiableList(new ArrayList<RunPoint>(chartPoints));
    }

    // Backward-compatible constructor used by older tests that still pass a legacy status text.
    public OperatorViewModel(
        String connectionText,
        String stateText,
        String runLabel,
        String plateText,
        String chartCaption,
        String legacyStatusText,
        String peakPowerText,
        String peakTorqueText,
        boolean recording,
        boolean startEnabled,
        boolean stopEnabled,
        boolean runModeEnabled,
        boolean printEnabled,
        BannerModel banner,
        GaugeModel lambdaGauge,
        GaugeModel tempGauge,
        GaugeModel o2Gauge,
        MetricTileModel powerTile,
        MetricTileModel torqueTile,
        MetricTileModel rpmTile,
        MetricTileModel afrTile,
        List<SecondaryMetricModel> secondaryMetrics,
        List<RunPoint> chartPoints
    ) {
        this(
            connectionText,
            stateText,
            runLabel,
            plateText,
            chartCaption,
            peakPowerText,
            peakTorqueText,
            recording,
            startEnabled,
            stopEnabled,
            runModeEnabled,
            printEnabled,
            banner,
            lambdaGauge,
            tempGauge,
            o2Gauge,
            powerTile,
            torqueTile,
            rpmTile,
            afrTile,
            secondaryMetrics,
            chartPoints
        );
    }

    public String getConnectionText() {
        return connectionText;
    }

    public String getStateText() {
        return stateText;
    }

    public String getRunLabel() {
        return runLabel;
    }

    public String getPlateText() {
        return plateText;
    }

    public String getChartCaption() {
        return chartCaption;
    }

    public String getPeakPowerText() {
        return peakPowerText;
    }

    public String getPeakTorqueText() {
        return peakTorqueText;
    }

    public boolean isRecording() {
        return recording;
    }

    public boolean isStartEnabled() {
        return startEnabled;
    }

    public boolean isStopEnabled() {
        return stopEnabled;
    }

    public boolean isRunModeEnabled() {
        return runModeEnabled;
    }

    public boolean isPrintEnabled() {
        return printEnabled;
    }

    public BannerModel getBanner() {
        return banner;
    }

    public GaugeModel getLambdaGauge() {
        return lambdaGauge;
    }

    public GaugeModel getTempGauge() {
        return tempGauge;
    }

    public GaugeModel getO2Gauge() {
        return o2Gauge;
    }

    public MetricTileModel getPowerTile() {
        return powerTile;
    }

    public MetricTileModel getTorqueTile() {
        return torqueTile;
    }

    public MetricTileModel getRpmTile() {
        return rpmTile;
    }

    public MetricTileModel getAfrTile() {
        return afrTile;
    }

    public List<SecondaryMetricModel> getSecondaryMetrics() {
        return secondaryMetrics;
    }

    public List<RunPoint> getChartPoints() {
        return chartPoints;
    }
}
