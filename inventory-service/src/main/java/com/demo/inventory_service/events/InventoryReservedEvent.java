package com.demo.inventory_service.events;

import java.time.Instant;

/** Published to {@code inventory.reserved}: every line of the order is now held. */
public record InventoryReservedEvent(
        String eventId,
        String orderId,
        Instant occurredAt
) {
}
