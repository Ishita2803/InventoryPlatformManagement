package com.demo.order_service.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Phase D8: published once payment-service has computed a sales order's invoice.
 * notification-service is a pure subscriber -- it never calls back for the line items or
 * customer record, the same choreography Phase 6's InventoryReserved/InventoryFailed
 * notifications use. Carries everything a presentable invoice email needs to render
 * (line items, weight surcharge, carrier) rather than just the total, so the only thing
 * notification-service is ever missing is a recipient it genuinely has no address for. */
public record InvoiceGeneratedEvent(
        String eventId,
        String orderId,
        String customerId,
        String invoiceId,
        String carrierCode,
        List<Line> items,
        BigDecimal lineTotal,
        BigDecimal weightSurcharge,
        BigDecimal totalAmount,
        Instant occurredAt,
        String recipientEmail
) {
    public record Line(String skuNumber, Integer quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
    }
}
