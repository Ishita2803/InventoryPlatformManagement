package com.demo.order_service.dto;

import java.math.BigDecimal;
import java.util.List;

/** Phase D11: {@code Σ (salePrice - costPrice) × quantitySold}, one line per sku that has
 * ever actually sold (shipped from a warehouse or completed as a direct order), plus the
 * grand total across every line. */
public record ProfitReportResponse(
        List<SkuProfitLine> lines,
        BigDecimal totalProfit
) {
}
