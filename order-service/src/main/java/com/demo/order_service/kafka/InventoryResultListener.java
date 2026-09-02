package com.demo.order_service.kafka;

import com.demo.order_service.events.InventoryFailedEvent;
import com.demo.order_service.events.InventoryReservedEvent;
import com.demo.order_service.events.KafkaTopics;
import com.demo.order_service.exception.InvalidOrderStateTransitionException;
import com.demo.order_service.exception.OrderNotFoundException;
import com.demo.order_service.models.OrderStatus;
import com.demo.order_service.service.OrderSettlementService;
import com.demo.order_service.service.OrderTxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Applies inventory's answer, and — when stock was reserved — drives the payment step.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryResultListener {

    private final OrderTxService orderTxService;
    private final OrderSettlementService orderSettlementService;

    /**
     * Stock is held. Move the order on, then charge for it.
     *
     * <p>Three separate steps on purpose, and the boundaries matter:
     *
     * <ol>
     *   <li>Record the reservation, in its own transaction.</li>
     *   <li>Call payment — <strong>outside</strong> any transaction. Holding a database
     *       transaction open across a network call to a third party is how connection pools
     *       get exhausted by one slow dependency.</li>
     *   <li>Apply the answer and queue the settlement event, in one transaction.</li>
     * </ol>
     */
    @KafkaListener(topics = KafkaTopics.INVENTORY_RESERVED, groupId = "order-service")
    public void onInventoryReserved(
            InventoryReservedEvent event,
            @Header(name = "X-Correlation-Id", required = false) String correlationId) {

        withCorrelationId(correlationId, () -> {
            log.info("Received InventoryReserved eventId={} orderId={}",
                    event.eventId(), event.orderId());

            boolean applied = apply(event.eventId(), "InventoryReserved", event.orderId(),
                    OrderStatus.INVENTORY_RESERVED);

            if (!applied) {
                // Duplicate delivery: the first one already reserved and paid.
                return;
            }

            // Cancels if payment cannot be reached: a customer is waiting on this one, and
            // holding stock for an order that cannot be charged helps nobody. The
            // reconciliation job uses the other entry point, because by the time it runs
            // nobody is waiting and an outage should not cancel a backlog.
            orderSettlementService.settleCancellingOnOutage(event.orderId());
        });
    }

    @KafkaListener(topics = KafkaTopics.INVENTORY_FAILED, groupId = "order-service")
    public void onInventoryFailed(
            InventoryFailedEvent event,
            @Header(name = "X-Correlation-Id", required = false) String correlationId) {

        withCorrelationId(correlationId, () -> {
            log.info("Received InventoryFailed eventId={} orderId={} reason={}",
                    event.eventId(), event.orderId(), event.reason());

            // No payment attempt: there is nothing to pay for, and nothing reserved to release.
            apply(event.eventId(), "InventoryFailed", event.orderId(), OrderStatus.INVENTORY_FAILED);
        });
    }

    /**
     * Puts the id from the Kafka header into MDC for the duration of processing, so every
     * log line here -- and every log line in {@code PaymentClient}'s synchronous call on
     * this same thread -- carries the same id the gateway logged for the original HTTP
     * request. Threads are pooled, so it must come back out afterwards.
     */
    private void withCorrelationId(String correlationId, Runnable work) {
        if (correlationId != null) {
            MDC.put("correlationId", correlationId);
        }
        try {
            work.run();
        } finally {
            MDC.remove("correlationId");
        }
    }

    /**
     * Applies a status change exactly once, via the {@code processed_event} row written in
     * the same transaction.
     *
     * <p><strong>The gap this leaves, and what closes it.</strong> If this process dies after
     * the event is marked processed but before payment settles, redelivery skips the event —
     * correctly — and the order is left at INVENTORY_RESERVED with stock held and nothing
     * charged. No consumer-side retry can help, because skipping is the right behaviour.
     * {@code OrderReconciliationService} closes it from the other direction, by sweeping
     * state rather than replaying messages.
     */
    private boolean apply(String eventId, String eventType, String orderId, OrderStatus target) {

        try {
            boolean applied = orderTxService.applyInventoryResult(
                    eventId, eventType, orderId, target);

            if (!applied) {
                log.info("Duplicate delivery of event {} for order {} — ignored", eventId, orderId);
            }
            return applied;

        } catch (InvalidOrderStateTransitionException illegalMove) {
            log.warn("Ignoring event {} for order {}: {}", eventId, orderId, illegalMove.getMessage());
            return false;

        } catch (OrderNotFoundException unknown) {
            log.error("Received an inventory result for unknown order {}", orderId, unknown);
            return false;
        }
    }
}
