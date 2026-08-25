package com.demo.order_service.service;

import com.demo.order_service.dto.CreateOrderRequest;
import com.demo.order_service.dto.OrderResponse;
import com.demo.order_service.models.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The public API of the order domain.
 *
 * <p>Thin since Phase 5: publishing moved into the outbox, so this no longer coordinates a
 * commit and a send. {@link OrderTxService} owns the transaction, and
 * {@code OutboxPublisher} delivers to Kafka out of band.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderTxService tx;

    /**
     * Persists the order. The event is queued in the outbox by the same transaction.
     *
     * <p>The dual write that used to live here is gone. Previously this committed the order
     * and then published to Kafka — two systems, no shared transaction — so a crash in
     * between left an order stuck PENDING that no consumer ever heard about. Now the event
     * is written to the outbox table in the same commit as the order, and
     * {@code OutboxPublisher} delivers it separately.
     *
     * <p>What that buys: the order and the intent to publish are atomic. What it costs: the
     * event is published a moment later, and possibly more than once — which is harmless,
     * because the consumers are idempotent.
     */
    public OrderResponse createOrder(CreateOrderRequest request) {
        return tx.create(request);
    }

    public OrderResponse getOrder(String orderId) {
        return tx.getOrder(orderId);
    }

    public List<OrderResponse> listOrders(Pageable pageable) {
        return tx.listOrders(pageable);
    }

    public OrderResponse transitionOrder(String orderId, OrderStatus target) {
        return tx.transitionOrder(orderId, target);
    }
}
