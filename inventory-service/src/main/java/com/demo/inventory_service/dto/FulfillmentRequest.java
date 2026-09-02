package com.demo.inventory_service.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * order-service's Phase D7 ask: "find whatever stock exists for this sku, preferring the
 * customer's own region, and hold it against this order." One call per sales-order line,
 * synchronous -- see {@code FulfillmentService} for why this line, unlike a purchase-order
 * placement, needs a real-time answer rather than a denormalized snapshot.
 */
@Data
public class FulfillmentRequest {

    @NotBlank(message = "Sku number is required")
    private String skuNumber;

    @NotBlank(message = "Region is required")
    private String region;

    @NotNull(message = "Quantity is required")
    @Min(value = 1, message = "Quantity must be at least 1")
    private Integer quantity;

    @NotBlank(message = "Order ID is required")
    private String orderId;
}
