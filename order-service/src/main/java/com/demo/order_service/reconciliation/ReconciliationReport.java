package com.demo.order_service.reconciliation;

/**
 * What one reconciliation sweep did.
 *
 * <p>Deliberately more than a count. "Processed 12 orders" hides the only thing an operator
 * cares about: twelve {@code confirmed} is a healthy recovery from a transient fault, twelve
 * {@code cancelled} means payment has been rejecting for a while, twelve {@code waiting} means
 * the provider is down right now, and twelve {@code skipped} means the threshold is too tight
 * and this job is racing the Kafka listener over live orders. Four very different situations
 * that a single number would render identical.
 *
 * @param examined     stalled orders picked up in this sweep, capped by the batch size
 * @param confirmed    resumed and paid
 * @param cancelled    resumed, and cancelled — stock released back to available
 * @param waiting      left alone because payment was unreachable; retried next sweep
 * @param skipped      already terminal, or lost a race to another writer. Both are fine.
 * @param pendingStuck orders stalled at PENDING. <strong>Reported only, never acted on</strong>
 *                     — whether stock is held is inventory's answer to give, not this
 *                     service's to guess.
 */
public record ReconciliationReport(
        int examined,
        int confirmed,
        int cancelled,
        int waiting,
        int skipped,
        long pendingStuck
) {

    /** Orders this sweep actually drove to a terminal status. */
    public int resumed() {
        return confirmed + cancelled;
    }

    /** True when the sweep found nothing to do, which is the healthy steady state. */
    public boolean isQuiet() {
        return examined == 0 && pendingStuck == 0;
    }

    @Override
    public String toString() {
        return "examined=" + examined
                + " confirmed=" + confirmed
                + " cancelled=" + cancelled
                + " waiting=" + waiting
                + " skipped=" + skipped
                + " pendingStuck=" + pendingStuck;
    }
}
