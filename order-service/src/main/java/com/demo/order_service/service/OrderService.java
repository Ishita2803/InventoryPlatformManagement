package com.demo.order_service.service;

import com.demo.order_service.dto.CreateOrderRequest;
import com.demo.order_service.dto.OrderResponse;
import com.demo.order_service.kafka.OrderEventPublisher;
import com.demo.order_service.models.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The public API of the order domain.
 *
 * <p>Not transactional itself: it commits the order through {@link OrderTxService} and only
 * then publishes, so Kafka never learns about an order the database rolled back.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderTxService tx;
    private final OrderEventPublisher publisher;

    /**
     * Persists the order as {@code PENDING}, then announces it.
     *
     * <p><strong>This is a dual write, and it is knowingly unsafe.</strong> The commit and
     * the publish are two separate operations with no shared transaction: if the process
     * dies between them, or the broker is unreachable, the order exists in MySQL as PENDING
     * and no consumer ever hears about it. It will sit there forever.
     *
     * <p>The window is deliberately left open in this phase rather than papered over with a
     * retry that would only narrow it. Phase 5's transactional outbox closes it properly, by
     * writing the event to the same database in the same transaction as the order and
     * draining it to Kafka afterwards.
     *
     * <p>Ordering the operations the other way round — publish, then commit — would be
     * strictly worse: inventory could reserve stock for an order that never came to exist.
     */
    public OrderResponse createOrder(CreateOrderRequest request) {

        OrderResponse response = tx.create(request);
        publisher.publishOrderPlaced(response);

        return response;
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
