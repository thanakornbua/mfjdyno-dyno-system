package com.dyno.export;

import com.dyno.history.RunHistoryFrameDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class RpmRangeAnalyzerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static RunHistoryFrameDto frame(double rpm, Double hp, Double nm, Double afr) throws Exception {
        StringBuilder json = new StringBuilder("{\"engine_rpm\":").append(rpm);
        if (hp != null) json.append(",\"power_hp\":").append(hp);
        if (nm != null) json.append(",\"torque_nm\":").append(nm);
        if (afr != null) json.append(",\"afr\":").append(afr);
        json.append("}");
        return MAPPER.readValue(json.toString(), RunHistoryFrameDto.class);
    }

    @Test
    public void bandsAlignToBandWidthAndComputeStats() throws Exception {
        List<RunHistoryFrameDto> frames = new ArrayList<RunHistoryFrameDto>();
        frames.add(frame(2100, 40.0, 130.0, 13.0));
        frames.add(frame(2400, 50.0, 140.0, 13.2));
        frames.add(frame(2600, 60.0, 150.0, 13.1));
        frames.add(frame(2900, 70.0, 160.0, 13.0));

        RpmRangeAnalyzer.Analysis analysis = RpmRangeAnalyzer.analyze(frames, 500.0);
        assertEquals(2, analysis.bands.size());

        RpmRangeAnalyzer.Band first = analysis.bands.get(0);
        assertEquals(2000, (int) first.rpmFrom);
        assertEquals(2500, (int) first.rpmTo);
        assertEquals(2, first.sampleCount);
        assertEquals(45.0, first.avgPowerHp.doubleValue(), 1e-9);
        assertEquals(50.0, first.peakPowerHp.doubleValue(), 1e-9);
        assertEquals(140.0, first.peakTorqueNm.doubleValue(), 1e-9);
        assertTrue(first.flags.isEmpty());

        assertEquals(70.0, analysis.peakPowerHp.doubleValue(), 1e-9);
        assertEquals(2900.0, analysis.peakPowerRpm.doubleValue(), 1e-9);
    }

    @Test
    public void flagsLeanAndRichBands() throws Exception {
        List<RunHistoryFrameDto> frames = new ArrayList<RunHistoryFrameDto>();
        frames.add(frame(3100, 50.0, 130.0, 15.4));
        frames.add(frame(3200, 52.0, 131.0, 15.2));
        frames.add(frame(3600, 55.0, 133.0, 11.2));

        RpmRangeAnalyzer.Analysis analysis = RpmRangeAnalyzer.analyze(frames, 500.0);
        assertEquals(2, analysis.bands.size());
        assertTrue(analysis.bands.get(0).flags.contains("LEAN"));
        assertTrue(analysis.bands.get(1).flags.contains("RICH"));
        assertEquals(2, analysis.notes.size());
        assertTrue(analysis.notes.get(0).contains("running lean"));
    }

    @Test
    public void flagsTorqueDipBeforePowerPeak() throws Exception {
        List<RunHistoryFrameDto> frames = new ArrayList<RunHistoryFrameDto>();
        frames.add(frame(2200, 40.0, 160.0, 13.0));
        frames.add(frame(2700, 45.0, 120.0, 13.0)); // >10% torque drop, before peak power
        frames.add(frame(3200, 80.0, 170.0, 13.0)); // power peak here

        RpmRangeAnalyzer.Analysis analysis = RpmRangeAnalyzer.analyze(frames, 500.0);
        assertTrue(analysis.bands.get(1).flags.contains("TORQUE DIP"));
    }

    @Test
    public void handlesEmptyAndNullRpmFrames() throws Exception {
        assertTrue(RpmRangeAnalyzer.analyze(Collections.<RunHistoryFrameDto>emptyList()).isEmpty());
        assertTrue(RpmRangeAnalyzer.analyze(null).isEmpty());

        List<RunHistoryFrameDto> frames = new ArrayList<RunHistoryFrameDto>();
        frames.add(MAPPER.readValue("{\"ts_ms\":1}", RunHistoryFrameDto.class));
        assertTrue(RpmRangeAnalyzer.analyze(frames).isEmpty());
    }
}
