package com.demo.order_service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class CreateOrderRequest {

    @NotBlank(message = "Customer ID is required")
    private String customerId;

    /**
     * {@code @Valid} on the collection is what makes the per-item constraints run at all.
     * Without it an item with quantity -5 sails straight through.
     */
    @NotEmpty(message = "An order must contain at least one item")
    @Valid
    private List<OrderItemRequest> items;
}
