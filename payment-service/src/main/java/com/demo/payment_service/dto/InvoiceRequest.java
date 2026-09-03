package com.demo.payment_service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * Phase D8: order-service's ask, once a sales order (Phase D7) has resolved what actually
 * shipped -- compute what it costs, weight surcharge included. Only shipped lines are ever
 * included; a backordered line isn't invoiced until it ships, which Phase D7's own
 * {@code shippedTotal} already treats the same way.
 */
public record InvoiceRequest(

        @NotBlank(message = "Order ID is required")
        String orderId,

        @NotBlank(message = "Carrier code is required")
        String carrierCode,

        @NotEmpty(message = "An invoice must contain at least one line")
        @Valid
        List<Line> lines
) {

    public record Line(

            @NotBlank(message = "Sku number is required")
            String skuNumber,

            @NotNull(message = "Quantity is required")
            Integer quantity,

            @NotNull(message = "Unit price is required")
            BigDecimal unitPrice,

            @NotNull(message = "Unit weight is required")
            BigDecimal unitWeight
    ) {
    }
}
