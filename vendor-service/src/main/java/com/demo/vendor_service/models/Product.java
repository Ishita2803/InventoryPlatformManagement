package com.demo.vendor_service.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The vendor's own catalog entry -- what they sell us, and at what cost. This is
 * deliberately NOT the same {@code Product} inventory-service already has (id/sku/name
 * only): that one is Impulse's internal stock record; this one is the vendor's master
 * data, including {@code costPrice}, which inventory-service has no business knowing.
 * `salePrice` (what WE charge) lives with inventory-service's catalog instead (Phase D5) --
 * two different actors set two different prices, so they belong to two different services.
 */
@Entity
@Table(
        name = "product",
        indexes = @Index(name = "uk_product_sku", columnList = "sku_number", unique = true)
)
@Getter
@Setter
@NoArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long productId;

    @Column(name = "vendor_id", nullable = false, length = 64, updatable = false)
    private String vendorId;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "sku_number", nullable = false, length = 64, updatable = false)
    private String skuNumber;

    @Column(length = 1000)
    private String description;

    /** Kilograms. Used later (Phase D8) to compute a carrier's weight-tier surcharge. */
    @Column(name = "unit_weight", nullable = false, precision = 10, scale = 3)
    private BigDecimal unitWeight;

    /** What we pay the vendor per unit. Never exposed to customer-facing endpoints. */
    @Column(name = "cost_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal costPrice;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Product(String vendorId, String productName, String skuNumber,
                   String description, BigDecimal unitWeight, BigDecimal costPrice) {
        this.vendorId = vendorId;
        this.productName = productName;
        this.skuNumber = skuNumber;
        this.description = description;
        this.unitWeight = unitWeight;
        this.costPrice = costPrice;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void applyUpdate(String productName, String description,
                            BigDecimal unitWeight, BigDecimal costPrice) {
        this.productName = productName;
        this.description = description;
        this.unitWeight = unitWeight;
        this.costPrice = costPrice;
        this.updatedAt = Instant.now();
    }
}
