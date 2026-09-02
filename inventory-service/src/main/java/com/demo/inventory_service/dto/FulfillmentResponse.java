package com.demo.inventory_service.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * What actually got reserved, warehouse by warehouse, plus the catalog data order-service
 * needs to price the line and (if {@code shortfall > 0}) place a backorder -- computed here
 * rather than with a second call back, the same "fetch once at the moment that needs it"
 * reasoning {@code CatalogService}'s own vendor lookup already follows.
 */
public record FulfillmentResponse(
        Long productId,
        BigDecimal unitPrice,
        BigDecimal unitWeight,
        Integer shipQuantity,
        Integer shortfall,
        List<Allocation> allocations,
        /**
         * Where a Phase D7 backorder for {@code shortfall} should be placed: the requested
         * region's own warehouse if one is registered, otherwise the oldest-registered
         * warehouse system-wide (the same fixed, deterministic fallback order the search
         * itself uses) -- {@code null} only if no warehouse exists at all.
         */
        String backorderWarehouseId
) {
    public record Allocation(String warehouseId, Integer quantity) {
    }
}
