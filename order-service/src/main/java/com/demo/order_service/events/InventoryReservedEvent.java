package com.demo.order_service.events;

import java.time.Instant;

/** Consumed from {@code inventory.reserved}: every line of the order is now held. */
public record InventoryReservedEvent(
        String eventId,
        String orderId,
        Instant occurredAt
) {
}
