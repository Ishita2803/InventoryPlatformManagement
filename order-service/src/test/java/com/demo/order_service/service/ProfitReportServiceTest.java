package com.demo.order_service.service;

import com.demo.order_service.client.InventoryServiceClient;
import com.demo.order_service.client.VendorServiceClient;
import com.demo.order_service.dto.ProfitReportResponse;
import com.demo.order_service.exception.CatalogItemNotFoundException;
import com.demo.order_service.repository.OrderItemRepository;
import com.demo.order_service.repository.SkuShippedQuantity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Phase D11's whole point: {@code Σ (salePrice - costPrice) × quantitySold}, computed
 * from what actually sold, not double-counted from a backordered row.
 */
@ExtendWith(MockitoExtension.class)
class ProfitReportServiceTest {

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private VendorServiceClient vendorServiceClient;

    @Mock
    private InventoryServiceClient inventoryServiceClient;

    private ProfitReportService profitReportService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        profitReportService = new ProfitReportService(
                orderItemRepository, vendorServiceClient, inventoryServiceClient);
    }

    @Test
    @DisplayName("profit per sku is (salePrice - costPrice) x quantitySold, summed into a grand total")
    void computesProfitPerSkuAndGrandTotal() {

        when(orderItemRepository.totalShippedQuantityBySku()).thenReturn(List.of(
                new SkuShippedQuantity("SKU-1", 10L),
                new SkuShippedQuantity("SKU-2", 5L)));

        when(vendorServiceClient.getProductBySku("SKU-1"))
                .thenReturn(new VendorServiceClient.VendorProduct("VENDOR-1", new BigDecimal("1.500"), new BigDecimal("40.00")));
        when(inventoryServiceClient.getCatalogItem("SKU-1"))
                .thenReturn(new InventoryServiceClient.CatalogItem("SKU-1", new BigDecimal("1.500"), new BigDecimal("70.00")));

        when(vendorServiceClient.getProductBySku("SKU-2"))
                .thenReturn(new VendorServiceClient.VendorProduct("VENDOR-2", new BigDecimal("0.500"), new BigDecimal("5.00")));
        when(inventoryServiceClient.getCatalogItem("SKU-2"))
                .thenReturn(new InventoryServiceClient.CatalogItem("SKU-2", new BigDecimal("0.500"), new BigDecimal("10.00")));

        ProfitReportResponse report = profitReportService.generate();

        assertThat(report.lines()).hasSize(2);

        var sku1 = report.lines().stream().filter(l -> l.skuNumber().equals("SKU-1")).findFirst().orElseThrow();
        assertThat(sku1.profitPerUnit()).isEqualByComparingTo("30.00");
        assertThat(sku1.totalProfit()).isEqualByComparingTo("300.00");

        var sku2 = report.lines().stream().filter(l -> l.skuNumber().equals("SKU-2")).findFirst().orElseThrow();
        assertThat(sku2.profitPerUnit()).isEqualByComparingTo("5.00");
        assertThat(sku2.totalProfit()).isEqualByComparingTo("25.00");

        assertThat(report.totalProfit()).isEqualByComparingTo("325.00");
    }

    @Test
    @DisplayName("a sku with a real quantity sold but no current catalog price is skipped, not fatal to the report")
    void skipsASkuMissingFromTheCurrentCatalogRatherThanFailing() {

        when(orderItemRepository.totalShippedQuantityBySku()).thenReturn(List.of(
                new SkuShippedQuantity("SKU-GONE", 3L),
                new SkuShippedQuantity("SKU-1", 2L)));

        when(vendorServiceClient.getProductBySku("SKU-GONE"))
                .thenReturn(new VendorServiceClient.VendorProduct("VENDOR-1", new BigDecimal("1.000"), new BigDecimal("10.00")));
        when(inventoryServiceClient.getCatalogItem("SKU-GONE"))
                .thenThrow(new CatalogItemNotFoundException("SKU-GONE"));

        when(vendorServiceClient.getProductBySku("SKU-1"))
                .thenReturn(new VendorServiceClient.VendorProduct("VENDOR-1", new BigDecimal("1.500"), new BigDecimal("40.00")));
        when(inventoryServiceClient.getCatalogItem("SKU-1"))
                .thenReturn(new InventoryServiceClient.CatalogItem("SKU-1", new BigDecimal("1.500"), new BigDecimal("70.00")));

        ProfitReportResponse report = profitReportService.generate();

        assertThat(report.lines()).hasSize(1);
        assertThat(report.lines().getFirst().skuNumber()).isEqualTo("SKU-1");
        assertThat(report.totalProfit()).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("nothing sold at all: an empty report, not an error")
    void noSalesYieldsAnEmptyReport() {

        when(orderItemRepository.totalShippedQuantityBySku()).thenReturn(List.of());

        ProfitReportResponse report = profitReportService.generate();

        assertThat(report.lines()).isEmpty();
        assertThat(report.totalProfit()).isEqualByComparingTo("0");
    }
}
