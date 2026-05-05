package com.dyno.presenter;

import com.dyno.model.FrameMessage;
import com.dyno.state.ConnectionPhase;
import com.dyno.state.LiveTelemetryState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class LiveDynoChartPresenterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void negativeSamplesAreSkippedWithoutEndingCollection() throws Exception {
        LiveTelemetryState telemetryState = new LiveTelemetryState();
        LiveDynoChartPresenter presenter = new LiveDynoChartPresenter(telemetryState);
        RunAxisSelection powerOnlyAxes = new RunAxisSelection(
            RunChartAxis.ENGINE_RPM,
            RunChartAxis.POWER,
            RunChartAxis.TORQUE,
            RunChartAxis.POWER
        );

        telemetryState.updateConnection(ConnectionPhase.CONNECTED, "Connected");
        presenter.updateRunControl(true, true, "NEG-123-01", powerOnlyAxes);

        telemetryState.updateFrame(frame("RECORDING", 3000.0, -20.0, -80.0));

        LiveDynoChartModel model = presenter.getViewModel();
        assertTrue(model.isCollecting());
        assertFalse(model.hasPlottedData());
        assertEquals("Collecting recording points.", model.getStatusText());

        telemetryState.updateFrame(frame("IDLE", 3000.0, -20.0, -80.0));

        model = presenter.getViewModel();
        assertFalse(model.isCollecting());
        assertFalse(model.hasPlottedData());
        assertEquals("Run finished without any plottable dyno chart samples.", model.getStatusText());
    }

    @Test
    public void positiveSamplesAfterNegativeOnesStillPlotUntilStateEndsRun() throws Exception {
        LiveTelemetryState telemetryState = new LiveTelemetryState();
        LiveDynoChartPresenter presenter = new LiveDynoChartPresenter(telemetryState);

        telemetryState.updateConnection(ConnectionPhase.CONNECTED, "Connected");
        presenter.updateRunControl(true, true, "NEG-123-01", RunAxisSelection.defaults());

        telemetryState.updateFrame(frame("RECORDING", 3000.0, -20.0, -80.0));
        telemetryState.updateFrame(frame("RECORDING", 3200.0, 40.0, 120.0));

        LiveDynoChartModel model = presenter.getViewModel();
        ChartSeriesModel powerSeries = model.getSeries().get(0);
        ChartSeriesModel torqueSeries = model.getSeries().get(1);
        assertTrue(model.isCollecting());
        assertEquals(1, powerSeries.getPoints().size());
        assertEquals(1, torqueSeries.getPoints().size());
        assertEquals(3200.0, powerSeries.getPoints().get(0).getX(), 0.0001);
        assertEquals(40.0, powerSeries.getPoints().get(0).getY(), 0.0001);
        assertEquals(120.0, torqueSeries.getPoints().get(0).getY(), 0.0001);

        telemetryState.updateFrame(frame("IDLE", 3200.0, 40.0, 120.0));

        model = presenter.getViewModel();
        assertFalse(model.isCollecting());
        assertTrue(model.hasPlottedData());
        assertEquals("Run complete. Chart frozen until the next run starts.", model.getStatusText());
    }

    @Test
    public void configuredAxesDriveChartSeriesAndTimeXAxis() throws Exception {
        LiveTelemetryState telemetryState = new LiveTelemetryState();
        LiveDynoChartPresenter presenter = new LiveDynoChartPresenter(telemetryState);
        RunAxisSelection selection = new RunAxisSelection(
            RunChartAxis.TIME,
            RunChartAxis.AFR,
            RunChartAxis.ENGINE_RPM,
            RunChartAxis.POWER
        );

        telemetryState.updateConnection(ConnectionPhase.CONNECTED, "Connected");
        presenter.updateRunControl(true, true, "AXIS-001", selection);

        telemetryState.updateFrame(frame("RECORDING", 3000.0, 45.0, 120.0, 12.6, 10.0));
        telemetryState.updateFrame(frame("RECORDING", 3200.0, 50.0, 125.0, 12.8, 10.2));

        LiveDynoChartModel model = presenter.getViewModel();
        assertEquals("Time vs AFR / Engine RPM / Power", model.getChartCaption());
        assertEquals("Time (s)", model.getXAxisLabel());
        assertEquals(3, model.getSeries().size());
        assertEquals(0.2, model.getSeries().get(0).getPoints().get(1).getX(), 0.0001);
        assertEquals(12.8, model.getSeries().get(0).getPoints().get(1).getY(), 0.0001);
        assertEquals(3200.0, model.getSeries().get(1).getPoints().get(1).getY(), 0.0001);
        assertEquals(50.0, model.getSeries().get(2).getPoints().get(1).getY(), 0.0001);
    }

    @Test
    public void backendReplayStyleSweepProducesOrderedChartSeries() throws Exception {
        LiveTelemetryState telemetryState = new LiveTelemetryState();
        LiveDynoChartPresenter presenter = new LiveDynoChartPresenter(telemetryState);

        telemetryState.updateConnection(ConnectionPhase.CONNECTED, "Connected");
        presenter.updateRunControl(true, true, "SIM-001", RunAxisSelection.defaults());

        telemetryState.updateFrame(backendFrame(1000, "armed", 1800.0, null, null, 0.91, 13.4));
        telemetryState.updateFrame(backendFrame(1100, "recording", 2200.0, null, null, 0.88, 12.9));
        telemetryState.updateFrame(backendFrame(1200, "recording", 3100.0, 46.0, 112.0, 0.89, 13.1));
        telemetryState.updateFrame(backendFrame(1300, "recording", 4200.0, 78.0, 131.0, 0.91, 13.4));
        telemetryState.updateFrame(backendFrame(1400, "recording", 5400.0, 103.0, 134.0, 0.94, 13.8));
        telemetryState.updateFrame(backendFrame(1500, "idle", 1500.0, null, null, 0.97, 14.2));

        LiveDynoChartModel model = presenter.getViewModel();
        ChartSeriesModel powerSeries = model.getSeries().get(0);
        ChartSeriesModel torqueSeries = model.getSeries().get(1);
        ChartSeriesModel afrSeries = model.getSeries().get(2);

        assertFalse(model.isCollecting());
        assertTrue(model.hasPlottedData());
        assertEquals("Run complete. Chart frozen until the next run starts.", model.getStatusText());
        assertEquals(3, powerSeries.getPoints().size());
        assertEquals(3, torqueSeries.getPoints().size());
        assertEquals(4, afrSeries.getPoints().size());
        assertEquals(3100.0, powerSeries.getPoints().get(0).getX(), 0.0001);
        assertEquals(46.0, powerSeries.getPoints().get(0).getY(), 0.0001);
        assertEquals(2200.0, afrSeries.getPoints().get(0).getX(), 0.0001);
        assertEquals(12.9, afrSeries.getPoints().get(0).getY(), 0.0001);
        assertEquals(103.0, powerSeries.getPoints().get(2).getY(), 0.0001);
        assertEquals(134.0, torqueSeries.getPoints().get(2).getY(), 0.0001);
        assertEquals(13.8, afrSeries.getPoints().get(3).getY(), 0.0001);
    }

    private static FrameMessage frame(
        String state,
        double engineRpm,
        double powerHp,
        double torqueNm
    ) throws Exception {
        return frame(state, engineRpm, powerHp, torqueNm, 12.7, 10.0);
    }

    private static FrameMessage frame(
        String state,
        double engineRpm,
        double powerHp,
        double torqueNm,
        double afr,
        double ts
    ) throws Exception {
        return MAPPER.readValue(
            "{"
                + "\"state\":\"" + state + "\","
                + "\"ts\":" + ts + ","
                + "\"engine_rpm\":" + engineRpm + ","
                + "\"power_hp\":" + powerHp + ","
                + "\"torque_nm\":" + torqueNm + ","
                + "\"afr\":" + afr
                + "}",
            FrameMessage.class
        );
    }

    private static FrameMessage backendFrame(
        long tsMs,
        String runState,
        double engineRpm,
        Double powerHp,
        Double torqueNm,
        double lambda,
        double afr
    ) throws Exception {
        String powerJson = powerHp == null ? "null" : powerHp.toString();
        String torqueJson = torqueNm == null ? "null" : torqueNm.toString();
        return MAPPER.readValue(
            "{"
                + "\"ts_ms\":" + tsMs + ","
                + "\"run_state\":\"" + runState + "\","
                + "\"engine_rpm\":" + engineRpm + ","
                + "\"power_hp\":" + powerJson + ","
                + "\"torque_nm\":" + torqueJson + ","
                + "\"lambda\":" + lambda + ","
                + "\"afr\":" + afr
                + "}",
            FrameMessage.class
        );
    }
}
