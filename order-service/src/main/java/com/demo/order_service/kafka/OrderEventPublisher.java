package com.demo.order_service.kafka;

import com.demo.order_service.dto.OrderResponse;
import com.demo.order_service.events.KafkaTopics;
import com.demo.order_service.events.OrderPlacedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Publishes {@code OrderPlaced}, keyed by {@code orderId}.
     *
     * <p>The key is not decoration: Kafka guarantees ordering only within a partition, and
     * keying by order guarantees that everything concerning one order lands on the same
     * partition and is therefore processed in order. Keying by anything else — or not at all
     * — allows a later event for an order to overtake an earlier one once there is more than
     * one partition.
     */
    public void publishOrderPlaced(OrderResponse order) {

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
                Instant.now()
        );

        kafkaTemplate.send(KafkaTopics.ORDER_PLACED, order.orderId(), event);

        log.info("Published OrderPlaced eventId={} orderId={} lines={}",
                event.eventId(), event.orderId(), lines.size());
    }
}
