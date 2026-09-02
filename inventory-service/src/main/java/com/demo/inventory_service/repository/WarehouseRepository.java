package com.demo.inventory_service.repository;

import com.demo.inventory_service.models.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {

    Optional<Warehouse> findByWarehouseId(String warehouseId);

    boolean existsByWarehouseId(String warehouseId);

    /**
     * Phase D7's whole "adjacent warehouse" fallback: every warehouse in the requested
     * region first, then every other warehouse in registration order. Two queries rather
     * than one "ORDER BY region = ?, created_at" so the simple region match stays a plain
     * equality check, not a database-specific trick.
     */
    List<Warehouse> findByRegionOrderByCreatedAtAsc(String region);

    List<Warehouse> findByRegionNotOrderByCreatedAtAsc(String region);

    List<Warehouse> findAllByOrderByCreatedAtAsc();
}
