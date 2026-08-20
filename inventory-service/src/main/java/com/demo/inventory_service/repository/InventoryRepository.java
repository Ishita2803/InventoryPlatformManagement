package com.demo.inventory_service.repository;

import com.demo.inventory_service.models.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InventoryRepository extends JpaRepository<Inventory,Long> {
    Optional<Inventory> findByProductIdAndWarehouseId(
            Long productId,
            String warehouseId
    );
}