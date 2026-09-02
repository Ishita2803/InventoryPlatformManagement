package com.demo.inventory_service.kafka;

import com.demo.inventory_service.dto.ReservationLine;
import com.demo.inventory_service.dto.ReserveOutcome;
import com.demo.inventory_service.events.InventoryFailedEvent;
import com.demo.inventory_service.events.InventoryReservedEvent;
import com.demo.inventory_service.events.KafkaTopics;
import com.demo.inventory_service.events.OrderPlacedEvent;
import com.demo.inventory_service.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reserves stock for an incoming order and reports the outcome.
 *
 * <p>The listener itself is thin on purpose: all the atomicity and idempotency lives in
 * {@code InventoryTxService.reserveOrder}, in one transaction. This method only decides
 * which event to publish.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderPlacedListener {

    private final InventoryService inventoryService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(topics = KafkaTopics.ORDER_PLACED, groupId = "inventory-service")
    public void onOrderPlaced(
            OrderPlacedEvent event,
            @Header(name = "X-Correlation-Id", required = false) String correlationId) {

        if (correlationId != null) {
            MDC.put("correlationId", correlationId);
        }
        try {
            log.info("Received OrderPlaced eventId={} orderId={} lines={}",
                    event.eventId(), event.orderId(), event.lines().size());

            List<ReservationLine> lines = event.lines().stream()
                    .map(line -> new ReservationLine(
                            line.productId(), line.warehouseId(), line.quantity()))
                    .toList();

            ReserveOutcome outcome =
                    inventoryService.reserveOrder(event.eventId(), event.orderId(), lines);

            switch (outcome.status()) {

                case RESERVED -> publishReserved(event.orderId(), correlationId);

                case FAILED -> {
                    // Nothing was reserved -- reserveOrder checks every line before applying
                    // any -- so there is nothing to compensate for here.
                    log.warn("Cannot reserve order {}: {}", event.orderId(), outcome.reason());
                    publishFailed(event.orderId(), outcome.reason(), correlationId);
                }

                case ALREADY_PROCESSED -> {
                    // A redelivery. The first delivery already published a result, so
                    // publishing again would only give downstream a duplicate to discard.
                    log.info("Skipping already-processed event {} for order {}",
                            event.eventId(), event.orderId());
                }
            }
        } finally {
            MDC.remove("correlationId");
        }
    }

    private void publishReserved(String orderId, String correlationId) {

        InventoryReservedEvent event = new InventoryReservedEvent(
                UUID.randomUUID().toString(), orderId, Instant.now());

        send(KafkaTopics.INVENTORY_RESERVED, orderId, event, correlationId);

        log.info("Published InventoryReserved eventId={} orderId={}",
                event.eventId(), orderId);
    }

    private void publishFailed(String orderId, String reason, String correlationId) {

        InventoryFailedEvent event = new InventoryFailedEvent(
                UUID.randomUUID().toString(), orderId, reason, Instant.now());

        send(KafkaTopics.INVENTORY_FAILED, orderId, event, correlationId);

        log.info("Published InventoryFailed eventId={} orderId={}",
                event.eventId(), orderId);
    }

    /** Carries the correlation id from the incoming message onto the outgoing one. */
    private void send(String topic, String orderId, Object payload, String correlationId) {

        List<org.apache.kafka.common.header.Header> headers = correlationId == null
                ? List.of()
                : List.of(new RecordHeader(
                        "X-Correlation-Id", correlationId.getBytes(StandardCharsets.UTF_8)));

        kafkaTemplate.send(new ProducerRecord<>(topic, null, orderId, payload, headers));
    }
}
