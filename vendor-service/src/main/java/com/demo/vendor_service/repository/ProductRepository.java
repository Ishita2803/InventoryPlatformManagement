package com.demo.vendor_service.repository;

import com.demo.vendor_service.models.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByVendorId(String vendorId);

    Optional<Product> findBySkuNumber(String skuNumber);

    boolean existsBySkuNumber(String skuNumber);

    Optional<Product> findByProductIdAndVendorId(Long productId, String vendorId);
}
