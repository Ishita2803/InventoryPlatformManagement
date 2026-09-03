package com.demo.notification_service.events;

import java.math.BigDecimal;
import java.time.Instant;

/** Duplicated from order-service's event of the same name and shape -- see KafkaTopics
 * for why events are duplicated per service rather than shared. */
public record InvoiceGeneratedEvent(
        String eventId,
        String orderId,
        String customerId,
        String invoiceId,
        BigDecimal totalAmount,
        Instant occurredAt
) {
}
