package com.demo.order_service.outbox;

import com.demo.order_service.dto.OrderResponse;
import com.demo.order_service.events.KafkaTopics;
import com.demo.order_service.events.OrderPlacedEvent;
import com.demo.order_service.models.OutboxEvent;
import com.demo.order_service.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

        outboxEventRepository.save(new OutboxEvent(
                event.eventId(),
                "Order",
                order.orderId(),          // Kafka key: one order, one partition, ordered
                KafkaTopics.ORDER_PLACED,
                serialize(event)));

        log.debug("Queued OrderPlaced eventId={} for order {} in the outbox",
                event.eventId(), order.orderId());
    }

    private String serialize(OrderPlacedEvent event) {
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
