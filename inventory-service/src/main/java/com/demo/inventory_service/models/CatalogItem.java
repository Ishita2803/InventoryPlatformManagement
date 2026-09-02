package com.demo.inventory_service.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What WE charge for a vendor's sku -- deliberately separate from vendor-service's
 * {@code Product.costPrice} (what we pay the vendor), the same way the two prices are two
 * different actors' decisions in real life. {@code vendorId} and {@code unitWeight} are
 * denormalized copies, fetched from vendor-service at the moment a sale price is set (see
 * {@code VendorServiceClient}) -- not because inventory-service owns that data, but so a
 * sales order (Phase D7) can compute an order's total weight without a synchronous call
 * back to vendor-service on every order.
 */
@Entity
@Table(name = "catalog_item")
@Getter
@Setter
@NoArgsConstructor
public class CatalogItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sku_number", nullable = false, unique = true, length = 64, updatable = false)
    private String skuNumber;

    @Column(name = "vendor_id", nullable = false, length = 64)
    private String vendorId;

    @Column(name = "unit_weight", nullable = false, precision = 10, scale = 3)
    private BigDecimal unitWeight;

    @Column(name = "sale_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal salePrice;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public CatalogItem(String skuNumber, String vendorId, BigDecimal unitWeight, BigDecimal salePrice) {
        this.skuNumber = skuNumber;
        this.vendorId = vendorId;
        this.unitWeight = unitWeight;
        this.salePrice = salePrice;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void updatePrice(BigDecimal salePrice) {
        this.salePrice = salePrice;
        this.updatedAt = Instant.now();
    }
}
