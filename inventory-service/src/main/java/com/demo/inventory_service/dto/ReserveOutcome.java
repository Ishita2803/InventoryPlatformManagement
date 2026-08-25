package com.demo.inventory_service.dto;

/**
 * What happened when a whole order was reserved.
 *
 * <p>A returned value rather than an exception, because "out of stock" is a normal business
 * answer that the caller must publish downstream — not an error. Using an exception would
 * also roll back the transaction, taking the {@code processed_event} row with it.
 */
public record ReserveOutcome(Status status, String reason) {

    public enum Status {
        /** Stock moved; publish InventoryReserved. */
        RESERVED,
        /** This exact event was handled before; publish nothing. */
        ALREADY_PROCESSED,
        /** Not enough stock, or no such inventory; publish InventoryFailed. */
        FAILED
    }

    public static ReserveOutcome reserved() {
        return new ReserveOutcome(Status.RESERVED, null);
    }

    public static ReserveOutcome alreadyProcessed() {
        return new ReserveOutcome(Status.ALREADY_PROCESSED, null);
    }

    public static ReserveOutcome failed(String reason) {
        return new ReserveOutcome(Status.FAILED, reason);
    }
}
