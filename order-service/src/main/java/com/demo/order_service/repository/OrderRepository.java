package com.demo.order_service.repository;

import com.demo.order_service.models.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * Lookup by the public UUID, not the surrogate key. {@code items} is fetched eagerly for
     * this query specifically: the response includes them, and leaving it lazy means a
     * second query per order.
     */
    @EntityGraph(attributePaths = "items")
    Optional<Order> findByOrderId(String orderId);

    @EntityGraph(attributePaths = "items")
    Page<Order> findAllBy(Pageable pageable);
}
