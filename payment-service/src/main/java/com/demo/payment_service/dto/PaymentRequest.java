package com.demo.payment_service.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * @param orderId doubles as the idempotency key — see {@code PaymentService}.
 */
public record PaymentRequest(

        @NotBlank(message = "Order ID is required")
        String orderId,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.00", message = "Amount cannot be negative")
        BigDecimal amount
) {
}
