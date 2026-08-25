package com.demo.order_service.events;

import java.time.Instant;
import java.util.List;

/**
 * Published when an order has been persisted and is awaiting stock.
 *
 * <p>A record, and deliberately <em>not</em> the {@code Order} JPA entity. Publishing an
 * entity would put Hibernate proxies, lazy collections and this service's schema on the
 * wire, so every column rename would become a breaking change for every consumer.
 *
 * <p>{@code eventId} is carried from the start even though nothing reads it yet: Phase 4's
 * {@code processed_events} table keys off it. Adding it later would mean either a migration
 * of in-flight messages or a period where idempotency cannot be enforced.
 */
public record OrderPlacedEvent(
        String eventId,
        String orderId,
        String customerId,
        List<Line> lines,
        Instant occurredAt
) {

    /**
     * One order line. Carries {@code warehouseId} because inventory keys its reservations on
     * (orderId, productId, warehouseId) — without it the consumer could not reserve.
     */
    public record Line(
            Long productId,
            String warehouseId,
            Integer quantity
    ) {
    }
}
