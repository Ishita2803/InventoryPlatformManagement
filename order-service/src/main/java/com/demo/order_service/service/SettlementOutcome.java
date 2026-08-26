package com.demo.order_service.service;

/**
 * What a settlement attempt actually did.
 *
 * <p>This exists because the reconciliation job needs to <em>report</em>, not just act. A
 * sweeper that logs "processed 12 orders" is nearly useless: 12 confirmed is a healthy
 * recovery, 12 cancelled means payment has been failing for an hour, and 12 already-settled
 * means the threshold is too aggressive and the job is fighting the Kafka listener. Those are
 * three completely different operational situations and they must not look the same.
 *
 * <p>An exception would be the wrong shape here. None of these are errors — they are the
 * expected results of a step that is allowed to find the world already changed.
 */
public enum SettlementOutcome {

    /** Payment approved; the order is CONFIRMED and inventory has been told to ship. */
    CONFIRMED,

    /** Payment gave a definitive no; the order is CANCELLED and the stock released. */
    CANCELLED,

    /**
     * Payment could not be reached. Deliberately <strong>not</strong> a cancellation.
     *
     * <p>The listener cancels on an outage, which is right when it happens once. Applying the
     * same rule in a sweep would let a five-minute provider outage cancel every stuck order at
     * once, turning a temporary failure into permanently lost business. The reconciler leaves
     * these for the next pass instead — and has a separate, much longer ceiling after which it
     * gives up and frees the stock anyway.
     */
    PAYMENT_UNAVAILABLE,

    /** The order reached a terminal status before this attempt got to it. Nothing to do. */
    ALREADY_SETTLED,

    /** Another writer settled it concurrently; the optimistic lock rejected this attempt. */
    RACED,

    /** No such order. Should be impossible, so it is reported rather than swallowed. */
    UNKNOWN_ORDER
}
