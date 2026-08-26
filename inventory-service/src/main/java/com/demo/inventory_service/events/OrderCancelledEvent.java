package com.demo.inventory_service.events;

import java.time.Instant;

/**
 * The order will not proceed: payment declined, timed out, or the circuit was open.
 *
 * <p>This is the compensation trigger. Inventory releases everything the order was holding,
 * which is what stops stock being leaked for an order that never completes.
 */
public record OrderCancelledEvent(
        String eventId,
        String orderId,
        String reason,
        Instant occurredAt
) {
}
