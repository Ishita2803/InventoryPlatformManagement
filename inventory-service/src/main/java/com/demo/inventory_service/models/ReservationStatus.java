package com.demo.inventory_service.models;

/**
 * Lifecycle of a single {@link Reservation}.
 *
 * <p>RESERVED  -> stock has been moved from available to reserved, but not yet shipped.
 * <p>RELEASED  -> the reservation was compensated; stock went back to available.
 * <p>CONFIRMED -> the order completed; the stock has genuinely left the warehouse and is
 *                 removed from reserved without returning to available.
 *
 * <p>RELEASED and CONFIRMED are both terminal. A reservation never returns to RESERVED,
 * which is what makes redelivery of an event safe to ignore.
 */
public enum ReservationStatus {
    RESERVED,
    RELEASED,
    CONFIRMED
}
