package com.demo.notification_service.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Duplicated from order-service's event of the same name and shape -- see KafkaTopics
 * for why events are duplicated per service rather than shared. Carries everything a
 * presentable invoice email needs (line items, weight surcharge, carrier), not just the
 * total -- see {@code InvoiceEventListener}. */
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
        /** Resolved by order-service from customer-service at invoice time -- null for a
         * demo/legacy customerId with no real onboarded customer behind it. */
        String recipientEmail
) {
    public record Line(String skuNumber, Integer quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
    }
}
