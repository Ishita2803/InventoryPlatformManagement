package com.demo.inventory_service.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReserveInventoryRequest {

    /**
     * Idempotency key. Two requests carrying the same orderId for the same product and
     * warehouse reserve stock exactly once, however many times they arrive.
     */
    @NotBlank(message = "Order ID is required")
    private String orderId;

    @NotNull(message = "Product ID is required")
    private Long productId;

    @NotBlank(message = "Warehouse ID is required")
    private String warehouseId;

    @NotNull(message = "Quantity is required")
    @Min(value = 1, message = "Quantity must be at least 1")
    private Integer quantity;
}