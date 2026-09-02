package com.demo.order_service.service;

import com.demo.order_service.client.VendorServiceClient;
import com.demo.order_service.dto.CreatePurchaseOrderRequest;
import com.demo.order_service.dto.PurchaseOrderResponse;
import com.demo.order_service.exception.PurchaseOrderNotFoundException;
import com.demo.order_service.models.ProcessedEvent;
import com.demo.order_service.models.PurchaseOrder;
import com.demo.order_service.models.PurchaseOrderPurpose;
import com.demo.order_service.outbox.OutboxWriter;
import com.demo.order_service.repository.ProcessedEventRepository;
import com.demo.order_service.repository.PurchaseOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Admin stocking orders (Phase D6), and the shared mechanism Phase D7's auto-backorders
 * and Phase D9's direct orders will reuse. No {@code PurchaseOrderTxService} split the
 * way {@code Order}/{@code OrderTxService} needed one -- that split exists specifically
 * because {@code InventoryService}'s bounded retry has to wrap a whole transaction, and
 * nothing here retries.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final VendorServiceClient vendorServiceClient;
    private final OutboxWriter outboxWriter;

    /**
     * Resolves the vendor from the sku, persists the purchase order, and queues its
     * {@code PurchaseOrderPlaced} event -- all in one transaction, the same
     * write-then-publish-via-outbox shape {@code OrderTxService.create} already
     * established in Phase 5.
     */
    @Transactional
    public PurchaseOrderResponse create(CreatePurchaseOrderRequest request) {

        VendorServiceClient.VendorProduct vendorProduct =
                vendorServiceClient.getProductBySku(request.getSkuNumber());

        PurchaseOrder purchaseOrder = purchaseOrderRepository.save(new PurchaseOrder(
                vendorProduct.vendorId(), request.getSkuNumber(), request.getQuantity(),
                request.getWarehouseId(), PurchaseOrderPurpose.STOCKING));

        outboxWriter.writePurchaseOrderPlaced(
                purchaseOrder.getPurchaseOrderId(), purchaseOrder.getVendorId(),
                purchaseOrder.getSkuNumber(), purchaseOrder.getQuantity(), purchaseOrder.getWarehouseId());

        log.info("Placed purchase order {} for {} x {} against vendor {}",
                purchaseOrder.getPurchaseOrderId(), purchaseOrder.getQuantity(),
                purchaseOrder.getSkuNumber(), purchaseOrder.getVendorId());

        return toResponse(purchaseOrder);
    }

    /**
     * The mock vendor fulfilling its own purchase order -- called from
     * {@code PurchaseOrderPlacedListener}. Idempotent the same way
     * {@code OrderTxService.applyInventoryResult} is: the {@code processed_event} row and
     * the status change commit together, so redelivery is a safe no-op.
     */
    @Transactional
    public boolean fulfill(String eventId, String purchaseOrderId) {

        if (processedEventRepository.existsById(eventId)) {
            log.info("Event {} already applied to purchase order {} -- skipping", eventId, purchaseOrderId);
            return false;
        }

        processedEventRepository.save(new ProcessedEvent(eventId, "PurchaseOrderPlaced"));

        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByPurchaseOrderId(purchaseOrderId)
                .orElseThrow(() -> new PurchaseOrderNotFoundException(purchaseOrderId));

        purchaseOrder.markFulfilled();

        outboxWriter.writePurchaseOrderFulfilled(
                purchaseOrder.getPurchaseOrderId(), purchaseOrder.getSkuNumber(),
                purchaseOrder.getQuantity(), purchaseOrder.getWarehouseId());

        log.info("Vendor {} fulfilled purchase order {}", purchaseOrder.getVendorId(), purchaseOrderId);

        return true;
    }

    public List<PurchaseOrderResponse> listAll() {
        return purchaseOrderRepository.findAllByOrderByCreatedAtDesc().stream().map(this::toResponse).toList();
    }

    public List<PurchaseOrderResponse> listForVendor(String vendorId) {
        return purchaseOrderRepository.findByVendorIdOrderByCreatedAtDesc(vendorId).stream()
                .map(this::toResponse).toList();
    }

    private PurchaseOrderResponse toResponse(PurchaseOrder purchaseOrder) {
        return new PurchaseOrderResponse(
                purchaseOrder.getPurchaseOrderId(), purchaseOrder.getVendorId(), purchaseOrder.getSkuNumber(),
                purchaseOrder.getQuantity(), purchaseOrder.getWarehouseId(), purchaseOrder.getPurpose(),
                purchaseOrder.getStatus(), purchaseOrder.getCreatedAt(), purchaseOrder.getFulfilledAt());
    }
}
