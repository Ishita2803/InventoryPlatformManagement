package com.demo.inventory_service.models;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * A stock reservation held on behalf of one order line.
 *
 * <p>This entity is the reason the platform can be made idempotent. Before it existed,
 * reserving was quantity-only -- {@code reserve(productId, warehouseId, quantity)} -- which
 * meant that when Kafka redelivered an {@code OrderPlaced} event (at-least-once is the
 * default), the same order reserved stock twice with no way to tell. It also meant Saga
 * compensation had nothing to look up: there was no record of what a given order had
 * reserved, so "release everything belonging to order X" was unanswerable.
 *
 * <p>The unique constraint on (orderId, productId, warehouseId) is the idempotency key. It
 * is enforced by the database rather than by application logic on purpose, because two
 * concurrent consumers can both pass an application-level "does it already exist?" check.
 */
@Entity
@Table(
        name = "reservation",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reservation_order_product_warehouse",
                columnNames = {"order_id", "product_id", "warehouse_id"}
        )
)
@Data
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The order this reservation belongs to.
     *
     * <p>Deliberately a String (a UUID), not a Long. It is a cross-service business
     * identifier that travels inside Kafka events, so it must not be order-service's
     * database surrogate key -- that would couple inventory to another service's
     * auto-increment sequence and break the moment order data is migrated or resharded.
     */
    @Column(name = "order_id", nullable = false, length = 64, updatable = false)
    private String orderId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private String warehouseId;

    @Column(nullable = false, updatable = false)
    private Integer quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
