package com.demo.inventory_service.repository;

import com.demo.inventory_service.models.CatalogItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CatalogItemRepository extends JpaRepository<CatalogItem, Long> {

    Optional<CatalogItem> findBySkuNumber(String skuNumber);
}
