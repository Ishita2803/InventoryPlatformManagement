package com.demo.order_service.outbox;

import com.demo.order_service.dto.OrderResponse;
import com.demo.order_service.events.KafkaTopics;
import com.demo.order_service.events.OrderCancelledEvent;
import com.demo.order_service.events.OrderConfirmedEvent;
import com.demo.order_service.events.InvoiceGeneratedEvent;
import com.demo.order_service.events.OrderPlacedEvent;
import com.demo.order_service.events.PurchaseOrderFulfilledEvent;
import com.demo.order_service.events.PurchaseOrderPlacedEvent;
import com.demo.order_service.models.OutboxEvent;
import com.demo.order_service.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Turns a domain event into an outbox row.
 *
 * <p>Has no transaction of its own by design — it is called from inside
 * {@code OrderTxService.create}, and must join that transaction so the order and its event
 * commit together. Giving this a {@code @Transactional(REQUIRES_NEW)} would quietly
 * reintroduce the exact dual-write problem the outbox exists to remove.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxWriter {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void writeOrderPlaced(OrderResponse order) {

        List<OrderPlacedEvent.Line> lines = order.items().stream()
                .map(item -> new OrderPlacedEvent.Line(
                        item.productId(),
                        item.warehouseId(),
                        item.quantity()))
                .toList();

        OrderPlacedEvent event = new OrderPlacedEvent(
                UUID.randomUUID().toString(),
                order.orderId(),
                order.customerId(),
                lines,
                Instant.now());

        OutboxEvent outboxEvent = new OutboxEvent(
                event.eventId(),
                "Order",
                order.orderId(),          // Kafka key: one order, one partition, ordered
                KafkaTopics.ORDER_PLACED,
                serializeOrderPlaced(event));
        outboxEvent.setCorrelationId(MDC.get("correlationId"));
        outboxEventRepository.save(outboxEvent);

        log.debug("Queued OrderPlaced eventId={} for order {} in the outbox",
                event.eventId(), order.orderId());
    }

    /** Payment succeeded: tell inventory the reservation has become a shipment. */
    public void writeOrderConfirmed(String orderId, String paymentId) {

        OrderConfirmedEvent event = new OrderConfirmedEvent(
                UUID.randomUUID().toString(), orderId, paymentId, Instant.now());

        write(event.eventId(), orderId, KafkaTopics.ORDER_CONFIRMED, event);
    }

    /**
     * The compensation trigger. Written to the outbox in the same transaction as the
     * cancellation, so stock is released even if this service dies immediately afterwards —
     * which is the whole reason compensation goes through the outbox rather than a direct
     * call to inventory.
     */
    public void writeOrderCancelled(String orderId, String reason) {

        // Bounded at the source. The reason often carries an exception message, whose length
        // is not something this service controls.
        String boundedReason = reason == null
                ? "unspecified"
                : reason.substring(0, Math.min(reason.length(), 500));

        OrderCancelledEvent event = new OrderCancelledEvent(
                UUID.randomUUID().toString(), orderId, boundedReason, Instant.now());

        write(event.eventId(), orderId, KafkaTopics.ORDER_CANCELLED, event);
    }

    /** Phase D6: admin places a stocking purchase order against a vendor for a sku. */
    public void writePurchaseOrderPlaced(String purchaseOrderId, String vendorId,
                                          String skuNumber, Integer quantity, String warehouseId) {

        PurchaseOrderPlacedEvent event = new PurchaseOrderPlacedEvent(
                UUID.randomUUID().toString(), purchaseOrderId, vendorId, skuNumber,
                quantity, warehouseId, Instant.now());

        write(event.eventId(), "PurchaseOrder", purchaseOrderId, KafkaTopics.PURCHASE_ORDER_PLACED, event);
    }

    /** The mock vendor fulfilling its own purchase order -- see
     * {@code PurchaseOrderPlacedListener}, which publishes this immediately since no real
     * vendor system exists to wait on. */
    public void writePurchaseOrderFulfilled(String purchaseOrderId, String skuNumber,
                                             Integer quantity, String warehouseId, String purpose) {

        PurchaseOrderFulfilledEvent event = new PurchaseOrderFulfilledEvent(
                UUID.randomUUID().toString(), purchaseOrderId, skuNumber, quantity,
                warehouseId, purpose, Instant.now());

        write(event.eventId(), "PurchaseOrder", purchaseOrderId, KafkaTopics.PURCHASE_ORDER_FULFILLED, event);
    }

    /** Phase D8: queues the invoice notification once payment-service has computed the
     * amount -- same outbox shape as every other event this service publishes, so a crash
     * between computing the invoice and this write cannot lose the notification. */
    public void writeInvoiceGenerated(String orderId, String customerId,
                                       String invoiceId, java.math.BigDecimal totalAmount) {

        InvoiceGeneratedEvent event = new InvoiceGeneratedEvent(
                UUID.randomUUID().toString(), orderId, customerId, invoiceId, totalAmount, Instant.now());

        write(event.eventId(), orderId, KafkaTopics.INVOICE_GENERATED, event);
    }

    private void write(String eventId, String orderId, String topic, Object payload) {
        write(eventId, "Order", orderId, topic, payload);
    }

    private void write(String eventId, String aggregateType, String aggregateId, String topic, Object payload) {

        OutboxEvent outboxEvent = new OutboxEvent(
                eventId, aggregateType, aggregateId, topic, serialize(payload));
        outboxEvent.setCorrelationId(MDC.get("correlationId"));
        outboxEventRepository.save(outboxEvent);

        log.debug("Queued {} eventId={} for {} {} in the outbox", topic, eventId, aggregateType, aggregateId);
    }

    private String serialize(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Could not serialize outbox payload " + event.getClass().getSimpleName(), failure);
        }
    }

    private String serializeOrderPlaced(OrderPlacedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException failure) {
            // Unserializable means the event is malformed, which is a programming error.
            // Failing here rolls back the order too, which is correct: better to reject the
            // order than to accept one whose event can never be sent.
            throw new IllegalStateException(
                    "Could not serialize OrderPlaced for order " + event.orderId(), failure);
        }
    }
}
