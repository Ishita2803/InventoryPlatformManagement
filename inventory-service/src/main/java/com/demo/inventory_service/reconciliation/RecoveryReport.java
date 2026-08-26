package com.demo.inventory_service.reconciliation;

/**
 * What one dead-letter recovery sweep did.
 *
 * @param examined dead-lettered records read in this sweep
 * @param confirmed reservation lines confirmed by replaying {@code order.confirmed.DLT}
 * @param released reservation lines released by replaying {@code order.cancelled.DLT}
 * @param failed records that failed again and were deliberately left for the next sweep
 */
public record RecoveryReport(int examined, int confirmed, int released, int failed) {

    public static RecoveryReport empty() {
        return new RecoveryReport(0, 0, 0, 0);
    }

    /**
     * True when a record failed again. Worth alerting on: a dead letter that will not replay
     * is no longer transient, and needs a human rather than another sweep.
     */
    public boolean needsAttention() {
        return failed > 0;
    }

    @Override
    public String toString() {
        return "examined=" + examined
                + " confirmed=" + confirmed
                + " released=" + released
                + " failed=" + failed;
    }
}
