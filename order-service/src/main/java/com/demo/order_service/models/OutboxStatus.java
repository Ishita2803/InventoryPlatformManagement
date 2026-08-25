package com.demo.order_service.models;

public enum OutboxStatus {

    /** Written, not yet on the broker. The poller will pick it up. */
    PENDING,

    /** Confirmed by the broker. Kept for audit rather than deleted immediately. */
    PUBLISHED,

    /**
     * Gave up after the attempt budget ran out.
     *
     * <p>A terminal state on purpose: without it, a row that can never publish is retried on
     * every poll forever, and it delays every row behind it. Same reasoning as the Kafka
     * dead-letter topic — quarantine it, alert on it, let a human decide.
     */
    FAILED
}
