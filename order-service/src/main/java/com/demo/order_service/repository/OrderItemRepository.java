package com.demo.order_service.repository;

import com.demo.order_service.models.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /**
     * Phase D11's profit report: total quantity actually sold per sku, across every order
     * ever placed. "Sold" means either shipped from a warehouse (a Phase D7 sales-order
     * row with a real {@code warehouseId}) or a Phase D9 direct-order row (which never has
     * a warehouse at all, but the mock vendor always fulfills it) -- explicitly excluding a
     * sales-order row that's still backordered (no warehouse, not a direct order), which
     * hasn't actually sold anything yet.
     */
    @Query("SELECT new com.demo.order_service.repository.SkuShippedQuantity(oi.skuNumber, SUM(oi.quantity)) "
            + "FROM OrderItem oi "
            + "WHERE oi.skuNumber IS NOT NULL "
            + "AND (oi.warehouseId IS NOT NULL OR oi.order.direct = true) "
            + "GROUP BY oi.skuNumber")
    List<SkuShippedQuantity> totalShippedQuantityBySku();
}
