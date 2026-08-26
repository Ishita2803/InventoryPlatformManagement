package com.demo.inventory_service.events;

import java.time.Instant;

/** Payment succeeded: the reservation becomes a shipment. */
public record OrderConfirmedEvent(
        String eventId,
        String orderId,
        String paymentId,
        Instant occurredAt
) {
}
