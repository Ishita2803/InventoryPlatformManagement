package com.demo.order_service.exception;

/**
 * An attempt to move an order into a state its lifecycle does not allow.
 *
 * <p>From Phase 3 this will most often mean a late or duplicated Kafka event trying to
 * revive a terminal order, which the consumer should treat as "already handled" rather than
 * as a failure worth retrying.
 */
public class InvalidOrderStateTransitionException extends RuntimeException {

    public InvalidOrderStateTransitionException(String message) {
        super(message);
    }
}
