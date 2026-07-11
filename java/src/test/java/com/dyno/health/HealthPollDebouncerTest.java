package com.dyno.health;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class HealthPollDebouncerTest {
    private static OperatorStatusModel readyModel() {
        return new OperatorStatusModel(
            OperatorStatusModel.OverallState.READY,
            true,
            "live",
            true,
            false,
            false,
            "Backend ready",
            "Health checks passing",
            null,
            Collections.<String>emptyList()
        );
    }

    @Test
    public void toleratesIsolatedFailuresAfterSuccess() {
        HealthPollDebouncer debouncer = new HealthPollDebouncer(3);
        debouncer.onSuccess(readyModel());

        OperatorStatusModel afterFirstFailure = debouncer.onFailure();
        assertEquals(OperatorStatusModel.OverallState.READY, afterFirstFailure.getOverallState());
        assertTrue(afterFirstFailure.getSecondaryMessage().contains("retrying"));

        OperatorStatusModel afterSecondFailure = debouncer.onFailure();
        assertEquals(OperatorStatusModel.OverallState.READY, afterSecondFailure.getOverallState());
    }

    @Test
    public void flipsToUnavailableAfterThresholdConsecutiveFailures() {
        HealthPollDebouncer debouncer = new HealthPollDebouncer(3);
        debouncer.onSuccess(readyModel());

        debouncer.onFailure();
        debouncer.onFailure();
        OperatorStatusModel afterThirdFailure = debouncer.onFailure();

        assertEquals(OperatorStatusModel.OverallState.UNAVAILABLE, afterThirdFailure.getOverallState());
    }

    @Test
    public void failureBeforeAnySuccessIsImmediatelyUnavailable() {
        HealthPollDebouncer debouncer = new HealthPollDebouncer(3);

        OperatorStatusModel result = debouncer.onFailure();

        assertEquals(OperatorStatusModel.OverallState.UNAVAILABLE, result.getOverallState());
    }

    @Test
    public void successAfterFailuresResetsTolerance() {
        HealthPollDebouncer debouncer = new HealthPollDebouncer(3);
        debouncer.onSuccess(readyModel());
        debouncer.onFailure();
        debouncer.onFailure();
        debouncer.onSuccess(readyModel());

        OperatorStatusModel afterSingleFailure = debouncer.onFailure();

        assertEquals(OperatorStatusModel.OverallState.READY, afterSingleFailure.getOverallState());
    }
}
