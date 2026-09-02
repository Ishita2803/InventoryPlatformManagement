package com.demo.inventory_service.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record CatalogItemResponse(
        String skuNumber, String vendorId, BigDecimal unitWeight, BigDecimal salePrice,
        Instant createdAt, Instant updatedAt
) {
}
