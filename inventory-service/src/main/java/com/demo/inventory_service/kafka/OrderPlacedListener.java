package com.demo.inventory_service.kafka;

import com.demo.inventory_service.dto.ReserveInventoryRequest;
import com.demo.inventory_service.events.InventoryFailedEvent;
import com.demo.inventory_service.events.InventoryReservedEvent;
import com.demo.inventory_service.events.KafkaTopics;
import com.demo.inventory_service.events.OrderPlacedEvent;
import com.demo.inventory_service.exception.InsufficientInventoryException;
import com.demo.inventory_service.exception.InventoryNotFoundException;
import com.demo.inventory_service.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Reserves stock for an incoming order and reports the outcome.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderPlacedListener {

    private final InventoryService inventoryService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(topics = KafkaTopics.ORDER_PLACED, groupId = "inventory-service")
    public void onOrderPlaced(OrderPlacedEvent event) {

        log.info("Received OrderPlaced eventId={} orderId={} lines={}",
                event.eventId(), event.orderId(), event.lines().size());

        try {
            for (OrderPlacedEvent.Line line : event.lines()) {
                inventoryService.reserveInventory(toRequest(event.orderId(), line));
            }

            publishReserved(event.orderId());

        } catch (InsufficientInventoryException | InventoryNotFoundException failure) {

            // An order is all-or-nothing. If line three has no stock, lines one and two are
            // already reserved and must be handed back, or that stock is leaked until
            // someone notices. Release is order-scoped precisely so this is one call, and it
            // is a no-op when nothing was reserved.
            log.warn("Cannot reserve order {}: {}. Releasing anything already held.",
                    event.orderId(), failure.getMessage());

            inventoryService.releaseInventory(event.orderId());

            publishFailed(event.orderId(), failure.getMessage());
        }
    }

    private ReserveInventoryRequest toRequest(String orderId, OrderPlacedEvent.Line line) {

        ReserveInventoryRequest request = new ReserveInventoryRequest();
        request.setOrderId(orderId);
        request.setProductId(line.productId());
        request.setWarehouseId(line.warehouseId());
        request.setQuantity(line.quantity());

        return request;
    }

    private void publishReserved(String orderId) {

        InventoryReservedEvent event = new InventoryReservedEvent(
                UUID.randomUUID().toString(), orderId, Instant.now());

        kafkaTemplate.send(KafkaTopics.INVENTORY_RESERVED, orderId, event);

        log.info("Published InventoryReserved eventId={} orderId={}",
                event.eventId(), orderId);
    }

    private void publishFailed(String orderId, String reason) {

        InventoryFailedEvent event = new InventoryFailedEvent(
                UUID.randomUUID().toString(), orderId, reason, Instant.now());

        kafkaTemplate.send(KafkaTopics.INVENTORY_FAILED, orderId, event);

        log.info("Published InventoryFailed eventId={} orderId={}",
                event.eventId(), orderId);
    }
}
