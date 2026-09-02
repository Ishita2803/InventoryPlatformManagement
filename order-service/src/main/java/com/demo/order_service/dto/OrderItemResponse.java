package com.demo.order_service.dto;

import java.math.BigDecimal;

public record OrderItemResponse(
        Long productId,
        String warehouseId,
        String skuNumber,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal
) {
}
