package com.dyno.presenter;

import com.dyno.presenter.RunPageDirector.Page;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class RunPageDirectorTest {

    @Test
    public void entersRunPageOnRecordingWhenOperatorStarted() {
        RunPageDirector director = new RunPageDirector();
        assertNull(director.onState("IDLE", false, Page.DASHBOARD));
        assertEquals(Page.RUN, director.onState("RECORDING", true, Page.DASHBOARD));
    }

    @Test
    public void doesNotEnterWhenNotOperatorStarted() {
        RunPageDirector director = new RunPageDirector();
        assertNull(director.onState("IDLE", false, Page.DASHBOARD));
        assertNull(director.onState("RECORDING", false, Page.DASHBOARD));
    }

    @Test
    public void exitsToDashboardWhenRunEnds() {
        RunPageDirector director = new RunPageDirector();
        director.onState("RECORDING", true, Page.RUN);
        assertNull(director.onState("STOPPING", true, Page.RUN));
        assertEquals(Page.DASHBOARD, director.onState("IDLE", true, Page.RUN));
    }

    @Test
    public void exitsOnFaultToo() {
        RunPageDirector director = new RunPageDirector();
        director.onState("RECORDING", true, Page.RUN);
        assertEquals(Page.DASHBOARD, director.onState("FAULT", true, Page.RUN));
    }

    @Test
    public void firstStateAndRepeatsAreNoOps() {
        RunPageDirector director = new RunPageDirector();
        assertNull(director.onState("RECORDING", true, Page.DASHBOARD));
        assertNull(director.onState("RECORDING", true, Page.DASHBOARD));
        assertNull(director.onState(null, true, Page.DASHBOARD));
    }

    @Test
    public void noExitWhenAlreadyOnDashboard() {
        RunPageDirector director = new RunPageDirector();
        director.onState("RECORDING", false, Page.DASHBOARD);
        assertNull(director.onState("IDLE", false, Page.DASHBOARD));
    }
}
