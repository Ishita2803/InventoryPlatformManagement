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
     * Only required for a Phase D7 sales order (any item carrying a {@code skuNumber}) --
     * enforced in {@code SalesOrderService}, not here, for the same "one shape or the
     * other" reason {@link OrderItemRequest} isn't annotated per-field either.
     */
    private String deliveryRegion;

    /**
     * Only required for a Phase D7 sales order -- Phase D8's invoice needs it to price the
     * weight surcharge. Same "one shape or the other" reasoning as {@link #deliveryRegion}.
     */
    private String carrierCode;

    /**
     * {@code @Valid} on the collection is what makes the per-item constraints run at all.
     * Without it an item with quantity -5 sails straight through.
     */
    @NotEmpty(message = "An order must contain at least one item")
    @Valid
    private List<OrderItemRequest> items;
}
