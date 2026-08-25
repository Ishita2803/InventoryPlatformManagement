package com.demo.order_service.models;

import com.demo.order_service.exception.InvalidOrderStateTransitionException;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An order.
 *
 * <p>Note the table is named {@code orders}: {@code ORDER} is a reserved word in SQL, and
 * leaving Hibernate to derive the table name from the class produces
 * {@code create table order (...)}, which fails on MySQL with a syntax error that points at
 * the wrong thing entirely.
 */
@Entity
@Table(
        name = "orders",
        indexes = @Index(name = "uk_orders_order_id", columnList = "order_id", unique = true)
)
@Getter
@Setter
public class Order {

    /** Internal surrogate key. Never leaves this service. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The public, cross-service identifier, and the one that travels in Kafka events.
     *
     * <p>A UUID rather than {@link #id} on purpose: inventory-service keys its reservations
     * on this value, so it must not be an auto-increment number that is only meaningful
     * inside this service's database. See inventory-service's {@code Reservation}.
     */
    @Column(name = "order_id", nullable = false, unique = true, length = 64, updatable = false)
    private String orderId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @OneToMany(
            mappedBy = "order",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    private List<OrderItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Keeps both sides of the association in step. Setting only one side is the classic JPA
     * bug: the child is persisted with a null foreign key, or not at all.
     */
    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }

    /**
     * Moves the order to {@code target}, refusing transitions the lifecycle does not allow.
     *
     * @throws InvalidOrderStateTransitionException if the move is illegal
     */
    public void transitionTo(OrderStatus target) {

        if (!status.canTransitionTo(target)) {
            throw new InvalidOrderStateTransitionException(
                    "Cannot move order " + orderId + " from " + status + " to " + target
                            + ". Allowed: " + status.allowedNextStates()
            );
        }

        this.status = target;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
