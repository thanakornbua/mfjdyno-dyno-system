package com.dyno.export;

import com.dyno.history.RunHistoryFrameDto;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits an RPM sweep into fixed-width RPM bands and computes per-band
 * statistics that are meaningful to a mechanic: average/peak power and
 * torque, AFR behaviour, and plain-language flags (lean, rich, torque dip).
 *
 * Pure and side-effect free so it can be unit tested without PDF plumbing.
 */
public final class RpmRangeAnalyzer {
    /** Default band width in RPM. */
    public static final double DEFAULT_BAND_WIDTH_RPM = 500.0;
    /** AFR below this at load is flagged rich (petrol WOT context). */
    public static final double AFR_RICH_LIMIT = 12.0;
    /** AFR above this at load is flagged lean (petrol WOT context). */
    public static final double AFR_LEAN_LIMIT = 14.7;
    /** Torque drop vs the previous band (before the power peak) flagged as a dip. */
    public static final double TORQUE_DIP_FRACTION = 0.10;

    public static final class Band {
        public final double rpmFrom;
        public final double rpmTo;
        public final int sampleCount;
        public final Double avgPowerHp;
        public final Double peakPowerHp;
        public final Double peakPowerRpm;
        public final Double avgTorqueNm;
        public final Double peakTorqueNm;
        public final Double avgAfr;
        public final Double minAfr;
        public final Double maxAfr;
        public final List<String> flags;

        Band(double rpmFrom, double rpmTo, int sampleCount,
             Double avgPowerHp, Double peakPowerHp, Double peakPowerRpm,
             Double avgTorqueNm, Double peakTorqueNm,
             Double avgAfr, Double minAfr, Double maxAfr,
             List<String> flags) {
            this.rpmFrom = rpmFrom;
            this.rpmTo = rpmTo;
            this.sampleCount = sampleCount;
            this.avgPowerHp = avgPowerHp;
            this.peakPowerHp = peakPowerHp;
            this.peakPowerRpm = peakPowerRpm;
            this.avgTorqueNm = avgTorqueNm;
            this.peakTorqueNm = peakTorqueNm;
            this.avgAfr = avgAfr;
            this.minAfr = minAfr;
            this.maxAfr = maxAfr;
            this.flags = flags;
        }

        public String rangeLabel() {
            return ((int) rpmFrom) + "–" + ((int) rpmTo) + " rpm";
        }
    }

    public static final class Analysis {
        public final List<Band> bands;
        public final Double peakPowerHp;
        public final Double peakPowerRpm;
        public final Double peakTorqueNm;
        public final Double peakTorqueRpm;
        /** Plain-language notes for the mechanic, one per flagged band. */
        public final List<String> notes;

        Analysis(List<Band> bands, Double peakPowerHp, Double peakPowerRpm,
                 Double peakTorqueNm, Double peakTorqueRpm, List<String> notes) {
            this.bands = bands;
            this.peakPowerHp = peakPowerHp;
            this.peakPowerRpm = peakPowerRpm;
            this.peakTorqueNm = peakTorqueNm;
            this.peakTorqueRpm = peakTorqueRpm;
            this.notes = notes;
        }

        public boolean isEmpty() {
            return bands.isEmpty();
        }
    }

    private RpmRangeAnalyzer() {
    }

    public static Analysis analyze(List<RunHistoryFrameDto> frames) {
        return analyze(frames, DEFAULT_BAND_WIDTH_RPM);
    }

    public static Analysis analyze(List<RunHistoryFrameDto> frames, double bandWidthRpm) {
        List<RunHistoryFrameDto> usable = new ArrayList<RunHistoryFrameDto>();
        double minRpm = Double.MAX_VALUE;
        double maxRpm = -Double.MAX_VALUE;
        if (frames != null) {
            for (RunHistoryFrameDto f : frames) {
                if (f == null || f.getEngineRpm() == null || f.getEngineRpm().doubleValue() <= 0) {
                    continue;
                }
                double rpm = f.getEngineRpm().doubleValue();
                usable.add(f);
                if (rpm < minRpm) minRpm = rpm;
                if (rpm > maxRpm) maxRpm = rpm;
            }
        }
        if (usable.isEmpty() || bandWidthRpm <= 0) {
            return new Analysis(new ArrayList<Band>(), null, null, null, null, new ArrayList<String>());
        }

        // Align band edges to the band width so ranges read naturally
        // (e.g. 2000-2500 rather than 2130-2630).
        double firstEdge = Math.floor(minRpm / bandWidthRpm) * bandWidthRpm;
        int bandCount = (int) (Math.floor((maxRpm - firstEdge) / bandWidthRpm)) + 1;

        Double peakPowerHp = null;
        Double peakPowerRpm = null;
        Double peakTorqueNm = null;
        Double peakTorqueRpm = null;

        List<List<RunHistoryFrameDto>> buckets = new ArrayList<List<RunHistoryFrameDto>>(bandCount);
        for (int i = 0; i < bandCount; i++) {
            buckets.add(new ArrayList<RunHistoryFrameDto>());
        }
        for (RunHistoryFrameDto f : usable) {
            double rpm = f.getEngineRpm().doubleValue();
            int index = (int) ((rpm - firstEdge) / bandWidthRpm);
            if (index < 0) index = 0;
            if (index >= bandCount) index = bandCount - 1;
            buckets.get(index).add(f);
            if (f.getPowerHp() != null
                && (peakPowerHp == null || f.getPowerHp().doubleValue() > peakPowerHp.doubleValue())) {
                peakPowerHp = f.getPowerHp();
                peakPowerRpm = f.getEngineRpm();
            }
            if (f.getTorqueNm() != null
                && (peakTorqueNm == null || f.getTorqueNm().doubleValue() > peakTorqueNm.doubleValue())) {
                peakTorqueNm = f.getTorqueNm();
                peakTorqueRpm = f.getEngineRpm();
            }
        }

        List<Band> bands = new ArrayList<Band>();
        List<String> notes = new ArrayList<String>();
        Double previousAvgTorque = null;
        for (int i = 0; i < bandCount; i++) {
            List<RunHistoryFrameDto> bucket = buckets.get(i);
            if (bucket.isEmpty()) {
                previousAvgTorque = null;
                continue;
            }
            double from = firstEdge + i * bandWidthRpm;
            double to = from + bandWidthRpm;
            Band band = summarizeBand(from, to, bucket, previousAvgTorque, peakPowerRpm);
            bands.add(band);
            for (String flag : band.flags) {
                notes.add(band.rangeLabel() + ": " + flagNote(flag, band));
            }
            previousAvgTorque = band.avgTorqueNm;
        }

        return new Analysis(bands, peakPowerHp, peakPowerRpm, peakTorqueNm, peakTorqueRpm, notes);
    }

    private static Band summarizeBand(
        double from,
        double to,
        List<RunHistoryFrameDto> bucket,
        Double previousAvgTorque,
        Double peakPowerRpm
    ) {
        double powerSum = 0; int powerCount = 0;
        Double peakPower = null; Double peakPowerAtRpm = null;
        double torqueSum = 0; int torqueCount = 0;
        Double peakTorque = null;
        double afrSum = 0; int afrCount = 0;
        Double minAfr = null; Double maxAfr = null;

        for (RunHistoryFrameDto f : bucket) {
            if (f.getPowerHp() != null) {
                double p = f.getPowerHp().doubleValue();
                powerSum += p;
                powerCount++;
                if (peakPower == null || p > peakPower.doubleValue()) {
                    peakPower = f.getPowerHp();
                    peakPowerAtRpm = f.getEngineRpm();
                }
            }
            if (f.getTorqueNm() != null) {
                double tq = f.getTorqueNm().doubleValue();
                torqueSum += tq;
                torqueCount++;
                if (peakTorque == null || tq > peakTorque.doubleValue()) {
                    peakTorque = f.getTorqueNm();
                }
            }
            if (f.getAfr() != null) {
                double afr = f.getAfr().doubleValue();
                afrSum += afr;
                afrCount++;
                if (minAfr == null || afr < minAfr.doubleValue()) minAfr = f.getAfr();
                if (maxAfr == null || afr > maxAfr.doubleValue()) maxAfr = f.getAfr();
            }
        }

        Double avgPower = powerCount > 0 ? Double.valueOf(powerSum / powerCount) : null;
        Double avgTorque = torqueCount > 0 ? Double.valueOf(torqueSum / torqueCount) : null;
        Double avgAfr = afrCount > 0 ? Double.valueOf(afrSum / afrCount) : null;

        List<String> flags = new ArrayList<String>();
        if (avgAfr != null) {
            if (avgAfr.doubleValue() > AFR_LEAN_LIMIT) {
                flags.add("LEAN");
            } else if (avgAfr.doubleValue() < AFR_RICH_LIMIT) {
                flags.add("RICH");
            }
        }
        boolean beforePowerPeak = peakPowerRpm == null || to <= peakPowerRpm.doubleValue();
        if (beforePowerPeak
            && avgTorque != null
            && previousAvgTorque != null
            && previousAvgTorque.doubleValue() > 0
            && avgTorque.doubleValue() < previousAvgTorque.doubleValue() * (1.0 - TORQUE_DIP_FRACTION)) {
            flags.add("TORQUE DIP");
        }

        return new Band(from, to, bucket.size(), avgPower, peakPower, peakPowerAtRpm,
            avgTorque, peakTorque, avgAfr, minAfr, maxAfr, flags);
    }

    private static String flagNote(String flag, Band band) {
        if ("LEAN".equals(flag)) {
            return "running lean (AFR " + oneDecimal(band.avgAfr) + ") — check fueling in this range";
        }
        if ("RICH".equals(flag)) {
            return "running rich (AFR " + oneDecimal(band.avgAfr) + ") — check injectors/mapping in this range";
        }
        if ("TORQUE DIP".equals(flag)) {
            return "torque drops vs previous range — inspect ignition/fuel delivery here";
        }
        return flag;
    }

    private static String oneDecimal(Double value) {
        return value == null ? "—" : String.format(java.util.Locale.US, "%.1f", value.doubleValue());
    }
}
