package com.demo.order_service.repository;

import com.demo.order_service.models.PurchaseOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    Optional<PurchaseOrder> findByPurchaseOrderId(String purchaseOrderId);

    List<PurchaseOrder> findByVendorIdOrderByCreatedAtDesc(String vendorId);

    List<PurchaseOrder> findAllByOrderByCreatedAtDesc();
}
