package com.dyno.health;

/**
 * Absorbs isolated health-poll failures so a single dropped request does not
 * flip the operator-facing status to UNAVAILABLE. Only touched from the
 * single-threaded status poll executor, so no synchronization is needed.
 */
public final class HealthPollDebouncer {
    private final int failureThreshold;
    private int consecutiveFailures;
    private OperatorStatusModel lastGood;

    public HealthPollDebouncer(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    public OperatorStatusModel onSuccess(OperatorStatusModel fresh) {
        consecutiveFailures = 0;
        lastGood = fresh;
        return fresh;
    }

    public OperatorStatusModel onFailure() {
        consecutiveFailures += 1;
        if (lastGood != null && consecutiveFailures < failureThreshold) {
            return OperatorStatusMapper.withSecondaryMessage(lastGood, "Connection hiccup — retrying...");
        }
        return OperatorStatusMapper.unavailable();
    }
}
