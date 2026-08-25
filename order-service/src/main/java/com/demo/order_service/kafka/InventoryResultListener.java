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

        apply(event.eventId(), "InventoryReserved", event.orderId(),
                OrderStatus.INVENTORY_RESERVED);
    }

    @KafkaListener(topics = KafkaTopics.INVENTORY_FAILED, groupId = "order-service")
    public void onInventoryFailed(InventoryFailedEvent event) {

        log.info("Received InventoryFailed eventId={} orderId={} reason={}",
                event.eventId(), event.orderId(), event.reason());

        apply(event.eventId(), "InventoryFailed", event.orderId(),
                OrderStatus.INVENTORY_FAILED);
    }

    /**
     * Applies a status change exactly once.
     *
     * <p>Duplicates are caught by the {@code processed_event} row, which is written in the
     * same transaction as the status change. The two exceptions below are then genuinely
     * exceptional rather than routine:
     *
     * <ul>
     *   <li>{@link InvalidOrderStateTransitionException} — a <em>different</em> event tried
     *       an illegal move, for example a stale InventoryReserved arriving after the order
     *       was cancelled. Nothing to retry; the order's current state is the right one.</li>
     *   <li>{@link OrderNotFoundException} — inventory answered about an order this service
     *       never wrote. Retrying cannot conjure it up.</li>
     * </ul>
     *
     * <p>Both are swallowed deliberately: rethrowing would hand them to the error handler,
     * which would retry three times and then dead-letter a message that is not actually
     * broken. Anything else — a database outage, say — <em>does</em> propagate, and that is
     * what the retry and DLT exist for.
     */
    private void apply(String eventId, String eventType, String orderId, OrderStatus target) {

        try {
            boolean applied = orderTxService.applyInventoryResult(
                    eventId, eventType, orderId, target);

            if (!applied) {
                log.info("Duplicate delivery of event {} for order {} — ignored",
                        eventId, orderId);
            }

        } catch (InvalidOrderStateTransitionException illegalMove) {
            log.warn("Ignoring event {} for order {}: {}",
                    eventId, orderId, illegalMove.getMessage());

        } catch (OrderNotFoundException unknown) {
            log.error("Received an inventory result for unknown order {}", orderId, unknown);
        }
    }
}
