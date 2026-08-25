package com.demo.inventory_service.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Body for the order-scoped operations: release and confirm.
 *
 * <p>Both act on <em>every</em> reservation belonging to an order, which is the whole point
 * of the {@code Reservation} entity -- Saga compensation needs to undo an entire order, not
 * one line at a time.
 */
@Data
public class OrderReferenceRequest {

    @NotBlank(message = "Order ID is required")
    private String orderId;
}
