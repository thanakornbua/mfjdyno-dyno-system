package com.dyno.presenter;

/**
 * Decides automatic page transitions between the dashboard/graph page and the
 * run page based on machine run-state changes.
 *
 * Rules:
 * - Auto-ENTER the run page when the state transitions into RECORDING, but
 *   only when the operator actually started the run (replay mode cycles run
 *   states forever and must not bounce the UI).
 * - Auto-EXIT to the dashboard (graph) page when a run-page state
 *   (RECORDING/STOPPING/ARMED) transitions to IDLE or FAULT.
 * - Repeated identical states and the first observed state are no-ops.
 */
public final class RunPageDirector {
    public enum Page {
        DASHBOARD,
        RUN
    }

    private String lastState;

    /**
     * Feed the current machine state; returns the page the UI should show,
     * or null when no transition is required.
     */
    public Page onState(String state, boolean operatorStarted, Page currentPage) {
        if (state == null) {
            return null;
        }
        String previous = lastState;
        lastState = state;
        if (previous == null || previous.equals(state)) {
            return null;
        }
        if ("RECORDING".equals(state) && operatorStarted && currentPage != Page.RUN) {
            return Page.RUN;
        }
        boolean wasRunPageState =
            "RECORDING".equals(previous) || "STOPPING".equals(previous) || "ARMED".equals(previous);
        boolean nowEnded = "IDLE".equals(state) || "FAULT".equals(state);
        if (wasRunPageState && nowEnded && currentPage == Page.RUN) {
            return Page.DASHBOARD;
        }
        return null;
    }
}
