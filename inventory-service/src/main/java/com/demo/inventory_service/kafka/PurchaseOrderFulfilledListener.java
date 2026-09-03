package com.demo.inventory_service.kafka;

import com.demo.inventory_service.dto.InventoryRequest;
import com.demo.inventory_service.events.KafkaTopics;
import com.demo.inventory_service.events.PurchaseOrderFulfilledEvent;
import com.demo.inventory_service.exception.ProductNotFoundException;
import com.demo.inventory_service.models.Product;
import com.demo.inventory_service.repository.ProductRepository;
import com.demo.inventory_service.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Turns a fulfilled purchase order into stock. Resolves {@code skuNumber} to this
 * service's own internal {@code Product.id} (the sku is the only identifier the two
 * services agree on -- inventory-service's surrogate product id is not vendor-service's
 * concern), then reuses the exact additive {@code addInventory} logic Phase 1 built for
 * the admin stock-add endpoint. This is the same warehouse stock a customer's sales order
 * (Phase D7) will later reserve from.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PurchaseOrderFulfilledListener {

    private final ProductRepository productRepository;
    private final InventoryService inventoryService;

    @KafkaListener(topics = KafkaTopics.PURCHASE_ORDER_FULFILLED, groupId = "inventory-service")
    public void onPurchaseOrderFulfilled(
            PurchaseOrderFulfilledEvent event,
            @Header(name = "X-Correlation-Id", required = false) String correlationId) {

        if (correlationId != null) {
            MDC.put("correlationId", correlationId);
        }
        try {
            log.info("Received PurchaseOrderFulfilled eventId={} sku={} qty={} warehouse={} purpose={}",
                    event.eventId(), event.skuNumber(), event.quantity(), event.warehouseId(), event.purpose());

            if ("DIRECT".equals(event.purpose())) {
                // Phase D9: a direct order ships straight from the vendor to the customer --
                // it never touches a warehouse, so there is nothing for this service to do.
                log.info("Purchase order {} is DIRECT -- skipping stock, no warehouse involved",
                        event.purchaseOrderId());
                return;
            }

            Product product = productRepository.findBySku(event.skuNumber())
                    .orElseThrow(() -> new ProductNotFoundException(
                            "No inventory-service product registered for sku=" + event.skuNumber()));

            InventoryRequest request = new InventoryRequest();
            request.setProductId(product.getId());
            request.setWarehouseId(event.warehouseId());
            request.setQuantity(event.quantity());

            inventoryService.addInventory(request);

            log.info("Stocked {} units of sku {} in warehouse {}",
                    event.quantity(), event.skuNumber(), event.warehouseId());

        } finally {
            MDC.remove("correlationId");
        }
    }
}
