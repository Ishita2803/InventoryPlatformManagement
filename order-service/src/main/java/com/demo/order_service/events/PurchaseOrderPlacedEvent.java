package com.demo.order_service.events;

import java.time.Instant;

/** Placed against a vendor for a sku, quantity, and destination warehouse. Consumed by
 * this same service's {@code PurchaseOrderFulfillmentListener}, which simulates vendor
 * fulfillment -- no real vendor system exists to call. */
public record PurchaseOrderPlacedEvent(
        String eventId,
        String purchaseOrderId,
        String vendorId,
        String skuNumber,
        Integer quantity,
        String warehouseId,
        Instant occurredAt
) {
}
