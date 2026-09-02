package com.demo.order_service.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Two shapes share this one class, distinguished by which fields are set -- validated in
 * {@code SalesOrderService}, not with bean-validation annotations, because the rule is
 * "one or the other," not "all of the above":
 *
 * <ul>
 *   <li><strong>Legacy demo line</strong> (Phase 2): {@code productId}, {@code warehouseId},
 *       {@code unitPrice} all supplied by the client.</li>
 *   <li><strong>Phase D7 sales-order line</strong>: only {@code skuNumber} supplied --
 *       {@code productId}/{@code warehouseId}/{@code unitPrice} are resolved server-side by
 *       the fulfillment search, the same "don't trust the client with pricing" fix Phase D5
 *       already made for admin's own catalog.</li>
 * </ul>
 */
@Data
public class OrderItemRequest {

    private Long productId;

    private String warehouseId;

    private String skuNumber;

    @NotNull(message = "Quantity is required")
    @Min(value = 1, message = "Quantity must be at least 1")
    private Integer quantity;

    @DecimalMin(value = "0.00", message = "Unit price cannot be negative")
    @Digits(integer = 17, fraction = 2, message = "Unit price must have at most 2 decimal places")
    private BigDecimal unitPrice;
}
