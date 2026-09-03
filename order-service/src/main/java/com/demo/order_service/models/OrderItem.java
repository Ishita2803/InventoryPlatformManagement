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

    /**
     * Nullable since Phase D7: a sales-order line only knows its {@code skuNumber} at
     * request time (the customer doesn't know inventory-service's internal product id) --
     * this is filled in from the fulfillment search's answer, same as {@link #warehouseId}.
     */
    @Column(name = "product_id")
    private Long productId;

    /**
     * Which warehouse this line is to be fulfilled from.
     *
     * <p>Carried on the order rather than decided later because inventory-service keys its
     * reservations on (orderId, productId, warehouseId). Without it, the
     * {@code OrderPlaced} event in Phase 3 would not contain enough information to reserve
     * anything.
     *
     * <p>Nullable since Phase D7: a sales-order line's shortfall is persisted as its own
     * row with {@code warehouseId == null}, meaning "not shipped from anywhere -- backordered"
     * -- distinct from a shipped row, which always has a real warehouse.
     */
    @Column(name = "warehouse_id", length = 64)
    private String warehouseId;

    /**
     * Nullable since Phase D7: a sales-order line resolves its own sku at fulfillment time,
     * server-side, so the customer never supplies a raw {@code productId} the way the
     * legacy demo flow does.
     */
    @Column(name = "sku_number", length = 64)
    private String skuNumber;

    /**
     * For a legacy demo line, the quantity reserved (and requested -- the two are always
     * equal there). For a Phase D7 sales-order line, the quantity <em>this specific row</em>
     * accounts for: either shipped from {@link #warehouseId}, or backordered if
     * {@code warehouseId == null}. One customer-requested (sku, quantity) line can therefore
     * become several persisted rows.
     */
    @Column(nullable = false)
    private Integer quantity;

    /** Phase D8: denormalized from inventory-service's catalog at fulfillment time (Phase
     * D7), so invoicing can compute an order's total weight without a second synchronous
     * call back to inventory-service. Null for a legacy demo line -- that flow has no
     * weight-based invoicing at all. */
    @Column(name = "unit_weight", precision = 10, scale = 3)
    private java.math.BigDecimal unitWeight;

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
