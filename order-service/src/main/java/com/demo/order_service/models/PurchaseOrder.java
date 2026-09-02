package com.demo.order_service.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * An order Impulse places with a vendor -- the mirror image of a customer's sales order,
 * and deliberately living in the SAME service rather than a separate
 * {@code purchase-order-service}: both are "an order fulfilled via events," the same
 * outbox/idempotent-consumer machinery this service already has, not a genuinely
 * different bounded context the way vendor/customer/carrier master data is.
 */
@Entity
@Table(name = "purchase_order")
@Getter
@Setter
@NoArgsConstructor
public class PurchaseOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "purchase_order_id", nullable = false, unique = true, length = 64, updatable = false)
    private String purchaseOrderId;

    @Column(name = "vendor_id", nullable = false, length = 64, updatable = false)
    private String vendorId;

    @Column(name = "sku_number", nullable = false, length = 64, updatable = false)
    private String skuNumber;

    @Column(nullable = false, updatable = false)
    private Integer quantity;

    /** Null for a {@code DIRECT} purchase order (Phase D9) -- a direct order never
     * reserves or touches warehouse stock at all, so it has nowhere to be delivered to
     * within Impulse. */
    @Column(name = "warehouse_id", length = 64, updatable = false)
    private String warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private PurchaseOrderPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PurchaseOrderStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    public PurchaseOrder(String vendorId, String skuNumber, Integer quantity,
                          String warehouseId, PurchaseOrderPurpose purpose) {
        this.purchaseOrderId = UUID.randomUUID().toString();
        this.vendorId = vendorId;
        this.skuNumber = skuNumber;
        this.quantity = quantity;
        this.warehouseId = warehouseId;
        this.purpose = purpose;
        this.status = PurchaseOrderStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public void markFulfilled() {
        this.status = PurchaseOrderStatus.FULFILLED;
        this.fulfilledAt = Instant.now();
    }
}
