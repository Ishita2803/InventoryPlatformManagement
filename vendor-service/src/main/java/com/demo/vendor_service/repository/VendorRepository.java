package com.demo.vendor_service.repository;

import com.demo.vendor_service.models.Vendor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VendorRepository extends JpaRepository<Vendor, Long> {

    Optional<Vendor> findByVendorId(String vendorId);
}
