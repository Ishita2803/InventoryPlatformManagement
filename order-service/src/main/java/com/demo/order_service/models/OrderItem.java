package com.demo.order_service.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "order_item")
@Getter
@Setter
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id_fk", nullable = false)
    private Order order;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    /**
     * Which warehouse this line is to be fulfilled from.
     *
     * <p>Carried on the order rather than decided later because inventory-service keys its
     * reservations on (orderId, productId, warehouseId). Without it, the
     * {@code OrderPlaced} event in Phase 3 would not contain enough information to reserve
     * anything.
     */
    @Column(name = "warehouse_id", nullable = false, length = 64)
    private String warehouseId;

    @Column(nullable = false)
    private Integer quantity;

    /**
     * Price per unit at the time the order was placed.
     *
     * <p><strong>Simplification:</strong> this is accepted from the client. A real system
     * would look it up from a catalogue service, because a client that sets its own price
     * can set it to zero. Called out rather than hidden -- it is a deliberate scope cut, not
     * an oversight.
     */
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal unitPrice;

    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
