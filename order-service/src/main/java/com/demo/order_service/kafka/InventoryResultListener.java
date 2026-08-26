package com.demo.order_service.kafka;

import com.demo.order_service.dto.OrderResponse;
import com.demo.order_service.events.InventoryFailedEvent;
import com.demo.order_service.events.InventoryReservedEvent;
import com.demo.order_service.events.KafkaTopics;
import com.demo.order_service.exception.InvalidOrderStateTransitionException;
import com.demo.order_service.exception.OrderNotFoundException;
import com.demo.order_service.models.OrderStatus;
import com.demo.order_service.payment.PaymentClient;
import com.demo.order_service.payment.PaymentResult;
import com.demo.order_service.service.OrderTxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Applies inventory's answer, and — when stock was reserved — drives the payment step.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryResultListener {

    private final OrderTxService orderTxService;
    private final PaymentClient paymentClient;

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
    public void onInventoryReserved(InventoryReservedEvent event) {

        log.info("Received InventoryReserved eventId={} orderId={}",
                event.eventId(), event.orderId());

        boolean applied = apply(event.eventId(), "InventoryReserved", event.orderId(),
                OrderStatus.INVENTORY_RESERVED);

        if (!applied) {
            // Duplicate delivery: the first one already reserved and paid.
            return;
        }

        settle(event.orderId());
    }

    @KafkaListener(topics = KafkaTopics.INVENTORY_FAILED, groupId = "order-service")
    public void onInventoryFailed(InventoryFailedEvent event) {

        log.info("Received InventoryFailed eventId={} orderId={} reason={}",
                event.eventId(), event.orderId(), event.reason());

        // No payment attempt: there is nothing to pay for, and nothing reserved to release.
        apply(event.eventId(), "InventoryFailed", event.orderId(), OrderStatus.INVENTORY_FAILED);
    }

    /**
     * Charges for the order and settles it either way.
     *
     * <p>Payment never throws here — {@code PaymentClient} has a fallback — so there are only
     * three outcomes, and two of them cancel. Cancelling queues {@code OrderCancelled}, which
     * is what makes inventory release the stock: that is the Saga compensating.
     */
    private void settle(String orderId) {

        OrderResponse order;
        try {
            order = orderTxService.getOrder(orderId);
        } catch (OrderNotFoundException unknown) {
            log.error("Cannot settle unknown order {}", orderId, unknown);
            return;
        }

        PaymentResult result = paymentClient.pay(orderId, order.totalAmount());

        try {
            switch (result.outcome()) {
                case APPROVED -> orderTxService.settleOrder(orderId, true, result.paymentId(), null);
                case DECLINED -> orderTxService.settleOrder(orderId, false, null,
                        "Payment declined: " + result.reason());
                // Distinct from DECLINED so an outage is not buried among ordinary declines.
                case UNAVAILABLE -> orderTxService.settleOrder(orderId, false, null,
                        "Payment unavailable: " + result.reason());
            }
        } catch (InvalidOrderStateTransitionException alreadySettled) {
            log.warn("Order {} was already settled: {}", orderId, alreadySettled.getMessage());
        }
    }

    /**
     * Applies a status change exactly once, via the {@code processed_event} row written in
     * the same transaction.
     *
     * <p><strong>Known gap.</strong> If this process dies after the event is marked
     * processed but before payment settles, redelivery skips the event and the order is left
     * at INVENTORY_RESERVED with stock held and nothing charged. Payment is idempotent by
     * orderId, so resuming is safe — what is missing is the thing that resumes it: a
     * reconciliation job over orders sitting in INVENTORY_RESERVED past a threshold. Not
     * built.
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
