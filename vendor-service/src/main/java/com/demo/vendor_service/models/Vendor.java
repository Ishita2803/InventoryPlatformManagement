package com.demo.vendor_service.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code vendorId} is a server-minted UUID, the same reasoning as {@code Order.orderId}
 * back in Phase 2: it is a cross-service business identifier (it becomes the JWT
 * {@code businessId} claim, and other services will reference it), so it must not be an
 * auto-increment surrogate key another service could collide with or infer sequence from.
 */
@Entity
@Table(name = "vendor")
@Getter
@Setter
@NoArgsConstructor
public class Vendor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vendor_id", nullable = false, unique = true, length = 64, updatable = false)
    private String vendorId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Vendor(String name) {
        this.vendorId = UUID.randomUUID().toString();
        this.name = name;
        this.createdAt = Instant.now();
    }
}
