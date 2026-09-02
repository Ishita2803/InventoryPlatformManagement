package com.demo.inventory_service.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Registered separately from {@link Inventory#getWarehouseId()}, which has been a bare,
 * unvalidated {@code String} since Phase 1 -- adding this table now does not retrofit a
 * foreign key onto existing stock rows (Phase A/B demo data keeps working unchanged).
 * What this table adds is {@code region}: the zone code Phase D7's fulfillment search
 * matches against a customer address's own region (Phase D3), which is the entire
 * mechanism behind "simple region matching, not real geodistance."
 */
@Entity
@Table(name = "warehouse")
@Getter
@Setter
@NoArgsConstructor
public class Warehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "warehouse_id", nullable = false, unique = true, length = 64, updatable = false)
    private String warehouseId;

    @Column(nullable = false, length = 500)
    private String location;

    @Column(nullable = false, length = 100)
    private String region;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Warehouse(String warehouseId, String location, String region) {
        this.warehouseId = warehouseId;
        this.location = location;
        this.region = region;
        this.createdAt = Instant.now();
    }
}
