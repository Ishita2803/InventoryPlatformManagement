package com.demo.order_service.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreatePurchaseOrderRequest {

    @NotBlank(message = "SKU number is required")
    private String skuNumber;

    @NotNull(message = "Quantity is required")
    @Min(value = 1, message = "Quantity must be at least 1")
    private Integer quantity;

    /**
     * Required for a {@code STOCKING} or {@code BACKORDER} purchase order -- not annotated
     * {@code @NotBlank} because Phase D9's {@code DIRECT} purchase orders never have a
     * warehouse at all (a direct order ships straight from vendor to customer). The
     * admin-facing {@code POST /api/purchase-orders} endpoint only ever creates
     * {@code STOCKING} orders, so its own caller always supplies one in practice.
     */
    private String warehouseId;
}
