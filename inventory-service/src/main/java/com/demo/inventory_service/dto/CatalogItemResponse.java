package com.demo.inventory_service.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record CatalogItemResponse(
        String skuNumber, String vendorId, BigDecimal unitWeight, BigDecimal salePrice,
        Instant createdAt, Instant updatedAt,
        /** Denormalized from this service's own {@code Product} row (populated at the
         * moment admin first prices this sku -- see {@code CatalogService}), so a
         * storefront can render a catalog card with a real name in one call instead of
         * joining across services in the browser, and without ever seeing vendor-service's
         * cost price the way a raw vendor-product lookup would expose. */
        String productName
) {
}
