package com.demo.order_service.dto;

import java.math.BigDecimal;

public record SkuProfitLine(
        String skuNumber,
        Long quantitySold,
        BigDecimal salePrice,
        BigDecimal costPrice,
        BigDecimal profitPerUnit,
        BigDecimal totalProfit
) {
}
