package com.demo.order_service.events;

import java.math.BigDecimal;
import java.time.Instant;

/** Phase D8: published once payment-service has computed a sales order's invoice.
 * notification-service is a pure subscriber -- it never calls back for the line items,
 * the same choreography Phase 6's InventoryReserved/InventoryFailed notifications use. */
public record InvoiceGeneratedEvent(
        String eventId,
        String orderId,
        String customerId,
        String invoiceId,
        BigDecimal totalAmount,
        Instant occurredAt
) {
}
