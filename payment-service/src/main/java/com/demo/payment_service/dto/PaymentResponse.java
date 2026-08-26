package com.demo.payment_service.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(
        String paymentId,
        String orderId,
        BigDecimal amount,
        PaymentStatus status,
        String message,
        Instant processedAt
) {
}
