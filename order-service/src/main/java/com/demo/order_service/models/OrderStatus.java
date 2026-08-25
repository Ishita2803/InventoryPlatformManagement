package com.demo.order_service.models;

import java.util.EnumSet;
import java.util.Set;

/**
 * The order lifecycle, with the legal transitions encoded rather than left to convention.
 *
 * <pre>
 *   PENDING ──► INVENTORY_RESERVED ──► CONFIRMED
 *      │                  │
 *      │                  └──────────► CANCELLED     (payment failed: stock released)
 *      │
 *      ├──────► INVENTORY_FAILED                     (out of stock)
 *      └──────► CANCELLED                            (cancelled before reservation)
 * </pre>
 *
 * <p>The guard exists because from Phase 3 onward status changes arrive as Kafka events,
 * and at-least-once delivery means they can arrive late, twice, or out of order. A stale
 * {@code InventoryReserved} must not drag an already-CANCELLED order back to
 * INVENTORY_RESERVED. Encoding the rule here means every caller gets it right, instead of
 * each consumer re-implementing an if-statement.
 */
public enum OrderStatus {

    PENDING,
    INVENTORY_RESERVED,
    INVENTORY_FAILED,
    CONFIRMED,
    CANCELLED;

    /**
     * Terminal states accept no further transitions. This is what makes replaying an old
     * event harmless rather than corrupting.
     */
    public boolean isTerminal() {
        return this == CONFIRMED || this == INVENTORY_FAILED || this == CANCELLED;
    }

    public boolean canTransitionTo(OrderStatus target) {
        return allowedNextStates().contains(target);
    }

    public Set<OrderStatus> allowedNextStates() {
        return switch (this) {
            case PENDING -> EnumSet.of(INVENTORY_RESERVED, INVENTORY_FAILED, CANCELLED);
            case INVENTORY_RESERVED -> EnumSet.of(CONFIRMED, CANCELLED);
            case CONFIRMED, INVENTORY_FAILED, CANCELLED -> EnumSet.noneOf(OrderStatus.class);
        };
    }
}
