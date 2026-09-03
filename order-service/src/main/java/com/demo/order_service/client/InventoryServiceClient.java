package com.demo.order_service.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

/**
 * The synchronous, internal, never-through-the-gateway call {@code SalesOrderService}
 * makes at order-creation time to search and hold stock for a sales-order line. Unlike
 * {@code VendorServiceClient}'s rare price-setting call, this happens on every sales
 * order -- the same "every order" frequency that earned {@code PaymentClient} its
 * Resilience4j circuit breaker in Phase 8. No breaker here yet; called out as a scope cut
 * for a portfolio project, not an oversight.
 */
@Component
public class InventoryServiceClient {

    private final RestClient restClient;

    public InventoryServiceClient(@Value("${inventory.base-url:http://localhost:8082}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public FulfillmentResult fulfill(String skuNumber, String region, Integer quantity, String orderId) {

        FulfillmentRequest request = new FulfillmentRequest(skuNumber, region, quantity, orderId);

        return restClient.post()
                .uri("/api/inventory/fulfillment")
                .body(request)
                .retrieve()
                .body(FulfillmentResult.class);
    }

    /** Compensation: undo every reservation this order holds, if persisting the order
     * itself fails after inventory-service already reserved stock for it. */
    public void release(String orderId) {
        restClient.post()
                .uri("/api/inventory/release")
                .body(new OrderReference(orderId))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Phase D9: a direct order still charges Impulse's own sale price for the sku (not
     * the vendor's cost price) -- it just skips reserving warehouse stock for it. Reuses
     * the same catalog lookup Phase D5 built for admin pricing, read-only here.
     */
    public CatalogItem getCatalogItem(String skuNumber) {
        return restClient.get()
                .uri("/api/inventory/catalog/{sku}", skuNumber)
                .retrieve()
                .body(CatalogItem.class);
    }

    private record FulfillmentRequest(String skuNumber, String region, Integer quantity, String orderId) {
    }

    private record OrderReference(String orderId) {
    }

    /** Only the fields this service actually needs -- vendorId is vendor-service's own
     * business, not ours. */
    public record CatalogItem(String skuNumber, BigDecimal unitWeight, BigDecimal salePrice) {
    }

    public record FulfillmentResult(
            Long productId,
            BigDecimal unitPrice,
            BigDecimal unitWeight,
            Integer shipQuantity,
            Integer shortfall,
            List<Allocation> allocations,
            String backorderWarehouseId
    ) {
        public record Allocation(String warehouseId, Integer quantity) {
        }
    }
}
