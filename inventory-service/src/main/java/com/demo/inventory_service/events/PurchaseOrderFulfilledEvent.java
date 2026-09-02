package com.demo.inventory_service.events;

import java.time.Instant;

/** Duplicated from order-service's event of the same name and shape -- see
 * KafkaTopics for why events are duplicated per service rather than shared. */
public record PurchaseOrderFulfilledEvent(
        String eventId,
        String purchaseOrderId,
        String skuNumber,
        Integer quantity,
        String warehouseId,
        Instant occurredAt
) {
}
