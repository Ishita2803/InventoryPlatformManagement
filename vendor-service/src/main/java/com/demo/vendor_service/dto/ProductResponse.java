package com.demo.vendor_service.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductResponse(
        Long productId,
        String vendorId,
        String productName,
        String skuNumber,
        String description,
        BigDecimal unitWeight,
        BigDecimal costPrice,
        Instant createdAt,
        Instant updatedAt
) {
}
