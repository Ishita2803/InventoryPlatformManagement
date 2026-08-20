package com.demo.inventory_service.repository;

import com.demo.inventory_service.models.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product,Long> {
    Optional<Product> findBySku(String sku);
}

// findByID
//findALl
//delte
//save
