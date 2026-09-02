package com.demo.order_service.events;

import java.time.Instant;

/** Consumed by inventory-service, which resolves {@code skuNumber} to its own internal
 * productId and increases stock in {@code warehouseId} by {@code quantity} -- the same
 * additive {@code addInventory} machinery Phase 1 built for the admin stock-add endpoint. */
public record PurchaseOrderFulfilledEvent(
        String eventId,
        String purchaseOrderId,
        String skuNumber,
        Integer quantity,
        String warehouseId,
        Instant occurredAt
) {
}
