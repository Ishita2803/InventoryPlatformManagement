package com.demo.order_service.service;

import com.demo.order_service.client.InventoryServiceClient;
import com.demo.order_service.client.VendorServiceClient;
import com.demo.order_service.dto.ProfitReportResponse;
import com.demo.order_service.dto.SkuProfitLine;
import com.demo.order_service.exception.CatalogItemNotFoundException;
import com.demo.order_service.exception.VendorSkuNotFoundException;
import com.demo.order_service.repository.OrderItemRepository;
import com.demo.order_service.repository.SkuShippedQuantity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase D11: {@code Σ (salePrice - costPrice) × quantitySold}, admin-facing.
 *
 * <p>Deliberately a read computed on request, not a maintained running total -- this
 * project's whole design bias is toward deriving numbers from the events/rows that
 * already exist rather than a second place that could drift from them. A sku's price
 * changes over time (Phase D5 lets admin re-price a catalog item in place), so this
 * report is honest about only ever reflecting *today's* prices against all-time
 * quantity sold, not a true historical margin -- a real analytics system would need a
 * price history table to do better, which is out of scope for a portfolio project.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProfitReportService {

    private final OrderItemRepository orderItemRepository;
    private final VendorServiceClient vendorServiceClient;
    private final InventoryServiceClient inventoryServiceClient;

    public ProfitReportResponse generate() {

        List<SkuProfitLine> lines = new ArrayList<>();
        BigDecimal totalProfit = BigDecimal.ZERO;

        for (SkuShippedQuantity sold : orderItemRepository.totalShippedQuantityBySku()) {

            try {
                VendorServiceClient.VendorProduct vendorProduct =
                        vendorServiceClient.getProductBySku(sold.skuNumber());
                InventoryServiceClient.CatalogItem catalogItem =
                        inventoryServiceClient.getCatalogItem(sold.skuNumber());

                BigDecimal profitPerUnit = catalogItem.salePrice().subtract(vendorProduct.costPrice());
                BigDecimal lineProfit = profitPerUnit.multiply(BigDecimal.valueOf(sold.totalQuantity()));

                lines.add(new SkuProfitLine(
                        sold.skuNumber(), sold.totalQuantity(), catalogItem.salePrice(),
                        vendorProduct.costPrice(), profitPerUnit, lineProfit));

                totalProfit = totalProfit.add(lineProfit);

            } catch (VendorSkuNotFoundException | CatalogItemNotFoundException missing) {
                // A sku that sold in the past but no longer has a vendor or catalog entry
                // (removed since) -- skip it rather than fail the whole report. Its
                // quantity is real; its current price is not knowable.
                log.warn("Skipping sku {} in profit report: {}", sold.skuNumber(), missing.getMessage());
            }
        }

        return new ProfitReportResponse(lines, totalProfit);
    }
}
