package com.demo.order_service.dto;

import com.demo.order_service.models.PurchaseOrderPurpose;
import com.demo.order_service.models.PurchaseOrderStatus;

import java.time.Instant;

public record PurchaseOrderResponse(
        String purchaseOrderId,
        String vendorId,
        String skuNumber,
        Integer quantity,
        String warehouseId,
        PurchaseOrderPurpose purpose,
        PurchaseOrderStatus status,
        Instant createdAt,
        Instant fulfilledAt
) {
}
