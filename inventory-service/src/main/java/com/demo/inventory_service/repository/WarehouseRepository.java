package com.demo.inventory_service.repository;

import com.demo.inventory_service.models.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {

    Optional<Warehouse> findByWarehouseId(String warehouseId);

    boolean existsByWarehouseId(String warehouseId);
}
