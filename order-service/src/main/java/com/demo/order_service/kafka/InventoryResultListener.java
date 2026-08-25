package com.demo.order_service.kafka;

import com.demo.order_service.events.InventoryFailedEvent;
import com.demo.order_service.events.InventoryReservedEvent;
import com.demo.order_service.events.KafkaTopics;
import com.demo.order_service.exception.InvalidOrderStateTransitionException;
import com.demo.order_service.exception.OrderNotFoundException;
import com.demo.order_service.models.OrderStatus;
import com.demo.order_service.service.OrderTxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Closes the loop: applies inventory's answer to the order.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryResultListener {

    private final OrderTxService orderTxService;

    @KafkaListener(topics = KafkaTopics.INVENTORY_RESERVED, groupId = "order-service")
    public void onInventoryReserved(InventoryReservedEvent event) {

        log.info("Received InventoryReserved eventId={} orderId={}",
                event.eventId(), event.orderId());

        apply(event.orderId(), OrderStatus.INVENTORY_RESERVED);
    }

    @KafkaListener(topics = KafkaTopics.INVENTORY_FAILED, groupId = "order-service")
    public void onInventoryFailed(InventoryFailedEvent event) {

        log.info("Received InventoryFailed eventId={} orderId={} reason={}",
                event.eventId(), event.orderId(), event.reason());

        apply(event.orderId(), OrderStatus.INVENTORY_FAILED);
    }

    /**
     * Applies a status change, treating "already applied" as success.
     *
     * <p>Kafka delivery is at-least-once, so the same event can arrive twice. The second
     * delivery finds the order already in the target state and
     * {@link InvalidOrderStateTransitionException} is thrown by the lifecycle guard. That is
     * not a failure worth retrying — the work is done — so it is logged and acknowledged.
     * Retrying it would loop forever and eventually poison the partition.
     *
     * <p>This is duplicate <em>tolerance</em>, not real idempotency: it works because the
     * transitions happen to be one-way. Phase 4 adds a {@code processed_events} table so
     * duplicates are recognised by {@code eventId} rather than inferred from the outcome.
     */
    private void apply(String orderId, OrderStatus target) {

        try {
            orderTxService.transitionOrder(orderId, target);

        } catch (InvalidOrderStateTransitionException alreadyApplied) {
            log.warn("Ignoring event for order {}: {}", orderId, alreadyApplied.getMessage());

        } catch (OrderNotFoundException unknown) {
            // Genuinely unexpected: inventory answered about an order this service never
            // wrote. Logged loudly rather than retried, since retrying cannot conjure it up.
            log.error("Received an inventory result for unknown order {}", orderId, unknown);
        }
    }
}
