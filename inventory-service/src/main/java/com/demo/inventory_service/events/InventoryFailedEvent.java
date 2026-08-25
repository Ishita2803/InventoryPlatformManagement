package com.demo.inventory_service.events;

import java.time.Instant;

/**
 * Consumed from {@code inventory.failed}: the order could not be stocked.
 *
 * <p>By the time this is published, inventory has already released anything it managed to
 * reserve for the order, so the order can be failed without further compensation.
 */
public record InventoryFailedEvent(
        String eventId,
        String orderId,
        String reason,
        Instant occurredAt
) {
}
