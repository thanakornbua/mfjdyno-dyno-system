package com.dyno.fx;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class FirstBootSetupDialogRetryMessageTest {
    @Test
    public void firstAttemptShowsPlainCheckingMessage() {
        assertEquals(
            "Checking dependencies...",
            FirstBootSetupDialog.dependencyRetryStatusMessage(1, 3)
        );
    }

    @Test
    public void laterAttemptsShowRetryCount() {
        assertEquals(
            "Still checking — retrying (attempt 2 of 3)...",
            FirstBootSetupDialog.dependencyRetryStatusMessage(2, 3)
        );
        assertEquals(
            "Still checking — retrying (attempt 3 of 3)...",
            FirstBootSetupDialog.dependencyRetryStatusMessage(3, 3)
        );
    }
}
