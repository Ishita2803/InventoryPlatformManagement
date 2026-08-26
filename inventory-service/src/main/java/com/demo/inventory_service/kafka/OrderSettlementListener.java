package com.demo.inventory_service.kafka;

import com.demo.inventory_service.events.KafkaTopics;
import com.demo.inventory_service.events.OrderCancelledEvent;
import com.demo.inventory_service.events.OrderConfirmedEvent;
import com.demo.inventory_service.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The end of the Saga: turns a reservation into a shipment, or gives the stock back.
 *
 * <p>Both operations were built in Phase 1 and have sat unused since — this is what finally
 * calls them. Both are order-scoped and idempotent, so a redelivered settlement event is
 * harmless: {@code confirmByOrderId} and {@code releaseByOrderId} only act on rows still in
 * RESERVED, and a second delivery finds none.
 *
 * <p><strong>Why compensation arrives as an event rather than a REST call.</strong>
 * order-service could have called {@code POST /api/inventory/release} directly, but then a
 * cancellation during an inventory-service restart would be lost and the stock leaked
 * forever. Published through order-service's outbox instead, the release is durable: it
 * waits in the topic until inventory is available to consume it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderSettlementListener {

    private final InventoryService inventoryService;

    /** Payment succeeded: reserved stock leaves the warehouse and does not return. */
    @KafkaListener(topics = KafkaTopics.ORDER_CONFIRMED, groupId = "inventory-service")
    public void onOrderConfirmed(OrderConfirmedEvent event) {

        log.info("Received OrderConfirmed eventId={} orderId={} paymentId={}",
                event.eventId(), event.orderId(), event.paymentId());

        int confirmed = inventoryService.confirmReservation(event.orderId()).size();

        log.info("Confirmed {} reservation line(s) for order {}", confirmed, event.orderId());
    }

    /**
     * The compensating action. Payment declined, timed out, or the circuit was open — give
     * the stock back so it is not held for an order that will never complete.
     */
    @KafkaListener(topics = KafkaTopics.ORDER_CANCELLED, groupId = "inventory-service")
    public void onOrderCancelled(OrderCancelledEvent event) {

        log.info("Received OrderCancelled eventId={} orderId={} reason={}",
                event.eventId(), event.orderId(), event.reason());

        int released = inventoryService.releaseInventory(event.orderId()).size();

        log.info("Released {} reservation line(s) for order {} — compensation complete",
                released, event.orderId());
    }
}
