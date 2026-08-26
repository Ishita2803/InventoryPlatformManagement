package com.demo.order_service.repository;

import com.demo.order_service.models.Order;
import com.demo.order_service.models.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
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

    /**
     * Orders that have sat in one status longer than they should have.
     *
     * <p>Returns identifiers rather than entities on purpose. The reconciler only needs the
     * id — it re-reads each order inside its own transaction — and selecting whole aggregates
     * would drag in every line item for rows that are usually going to be skipped.
     *
     * <p>Oldest first, so the orders that have been holding stock longest are freed first,
     * and a backlog larger than one batch still drains in a sensible order.
     */
    @Query("SELECT o.orderId FROM Order o WHERE o.status = :status "
            + "AND o.updatedAt < :threshold ORDER BY o.updatedAt ASC")
    List<String> findStuckOrderIds(@Param("status") OrderStatus status,
                                   @Param("threshold") Instant threshold,
                                   Pageable pageable);

    /** How many orders are stalled in a status, for reporting rather than for acting on. */
    long countByStatusAndUpdatedAtBefore(OrderStatus status, Instant threshold);
}
