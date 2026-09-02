package com.demo.order_service.kafka;

import com.demo.order_service.events.KafkaTopics;
import com.demo.order_service.events.PurchaseOrderPlacedEvent;
import com.demo.order_service.service.PurchaseOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * The mock vendor. Real vendor fulfillment would be asynchronous and could fail; this one
 * mock-fulfills immediately and always succeeds, because no real vendor system exists to
 * call -- see {@code PurchaseOrderStatus}'s missing REJECTED state for the same honesty.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PurchaseOrderPlacedListener {

    private final PurchaseOrderService purchaseOrderService;

    @KafkaListener(topics = KafkaTopics.PURCHASE_ORDER_PLACED, groupId = "order-service")
    public void onPurchaseOrderPlaced(
            PurchaseOrderPlacedEvent event,
            @Header(name = "X-Correlation-Id", required = false) String correlationId) {

        if (correlationId != null) {
            MDC.put("correlationId", correlationId);
        }
        try {
            log.info("Received PurchaseOrderPlaced eventId={} purchaseOrderId={} vendor={} sku={} qty={}",
                    event.eventId(), event.purchaseOrderId(), event.vendorId(), event.skuNumber(), event.quantity());

            purchaseOrderService.fulfill(event.eventId(), event.purchaseOrderId());

        } finally {
            MDC.remove("correlationId");
        }
    }
}
