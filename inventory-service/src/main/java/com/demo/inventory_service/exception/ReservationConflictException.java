package com.demo.inventory_service.exception;

/**
 * Thrown when a reservation could not be completed because another transaction kept winning
 * the race, and the bounded retry budget was exhausted.
 *
 * <p>This is a 409, not a 500. Nothing is broken -- the caller is simply being told that
 * contention on this stock row was too high right now and the request should be retried.
 * Surfacing it as a 500 would hide a normal, expected outcome of optimistic locking behind
 * what looks like a server fault.
 */
public class ReservationConflictException extends RuntimeException {

    public ReservationConflictException(String message) {
        super(message);
    }

    public ReservationConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
