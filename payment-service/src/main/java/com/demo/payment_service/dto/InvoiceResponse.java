package com.demo.payment_service.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record InvoiceResponse(
        String invoiceId,
        String orderId,
        BigDecimal lineTotal,
        BigDecimal totalWeight,
        BigDecimal weightSurcharge,
        BigDecimal totalAmount,
        Instant generatedAt
) {
}
