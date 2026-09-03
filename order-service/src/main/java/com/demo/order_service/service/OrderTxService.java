package com.demo.order_service.service;

import com.demo.order_service.dto.CreateOrderRequest;
import com.demo.order_service.dto.OrderResponse;
import com.demo.order_service.exception.OrderNotFoundException;
import com.demo.order_service.mapper.OrderMapper;
import com.demo.order_service.outbox.OutboxWriter;
import com.demo.order_service.models.Order;
import com.demo.order_service.models.ProcessedEvent;
import com.demo.order_service.models.OrderStatus;
import com.demo.order_service.repository.OrderRepository;
import com.demo.order_service.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The transactional unit of work for orders.
 *
 * <p>Separate from {@link OrderService} so that publishing to Kafka happens strictly
 * <em>after</em> the database transaction commits. Publishing inside the transaction would
 * mean an event could be sent for an order that then rolls back — inventory would reserve
 * stock for an order that does not exist.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderTxService {

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final OutboxWriter outboxWriter;

    /**
     * Persists the order and queues its event, in one transaction.
     *
     * <p>Nothing here talks to Kafka. The order row and the outbox row go to the same
     * database in the same commit, so they are atomic by construction — there is no window
     * in which one exists without the other. {@code OutboxPublisher} moves the event to the
     * broker afterwards, and can retry for as long as it takes.
     */
    @Transactional
    public OrderResponse create(CreateOrderRequest request) {

        Order order = orderMapper.toNewOrder(request);
        Order saved = orderRepository.save(order);
        OrderResponse response = orderMapper.toResponse(saved);

        outboxWriter.writeOrderPlaced(response);

        log.info("Created order {} for customer {} with {} item(s), total {}",
                saved.getOrderId(), saved.getCustomerId(),
                saved.getItems().size(), saved.getTotalAmount());

        return response;
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(String orderId) {
        return orderMapper.toResponse(loadOrder(orderId));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> listOrders(Pageable pageable) {
        return orderRepository.findAllBy(pageable)
                .map(orderMapper::toResponse)
                .getContent();
    }

    /** Phase D10: a carrier's own assigned-orders view. */
    @Transactional(readOnly = true)
    public List<OrderResponse> listOrdersForCarrier(String carrierCode, Pageable pageable) {
        return orderRepository.findByCarrierCode(carrierCode, pageable)
                .map(orderMapper::toResponse)
                .getContent();
    }

    @Transactional
    public OrderResponse transitionOrder(String orderId, OrderStatus target) {

        Order order = loadOrder(orderId);
        order.transitionTo(target);

        log.info("Order {} moved to {}", orderId, target);

        return orderMapper.toResponse(order);
    }

    /**
     * Applies an inventory result to an order, exactly once.
     *
     * <p>The {@code processed_event} row and the status change are written in the <em>same
     * transaction</em>. That is the entire trick: they commit together or not at all, so a
     * crash can never leave the event marked handled while the order stayed put, nor the
     * order advanced with no record that the event was consumed. Redelivery then finds the
     * row and does nothing.
     *
     * @return true if this delivery did the work, false if it was a duplicate
     */
    @Transactional
    public boolean applyInventoryResult(String eventId, String eventType,
                                        String orderId, OrderStatus target) {

        if (processedEventRepository.existsById(eventId)) {
            log.info("Event {} already applied to order {} -- skipping", eventId, orderId);
            return false;
        }

        processedEventRepository.save(new ProcessedEvent(eventId, eventType));

        Order order = loadOrder(orderId);
        order.transitionTo(target);

        log.info("Order {} moved to {} by event {}", orderId, target, eventId);

        return true;
    }

    /**
     * Applies payment's answer and queues the matching settlement event, in one transaction.
     *
     * <p>The status change and the event that tells inventory about it commit together, so
     * an order can never be CANCELLED without the release being queued, nor CONFIRMED
     * without inventory being told to confirm. That is the outbox doing the same job for
     * compensation that it already does for OrderPlaced.
     */
    @Transactional
    public OrderResponse settleOrder(String orderId, boolean approved,
                                     String paymentId, String reason) {

        Order order = loadOrder(orderId);

        if (order.getStatus().isTerminal()) {
            // A late duplicate. Nothing to do, and nothing to publish.
            log.info("Order {} is already {} — not settling again", orderId, order.getStatus());
            return orderMapper.toResponse(order);
        }

        if (approved) {
            order.transitionTo(OrderStatus.CONFIRMED);
            outboxWriter.writeOrderConfirmed(orderId, paymentId);
            log.info("Order {} CONFIRMED (payment {})", orderId, paymentId);
        } else {
            order.transitionTo(OrderStatus.CANCELLED);
            outboxWriter.writeOrderCancelled(orderId, reason);
            log.info("Order {} CANCELLED: {}", orderId, reason);
        }

        return orderMapper.toResponse(order);
    }

    private Order loadOrder(String orderId) {
        return orderRepository.findByOrderId(orderId)
                .orElseThrow(() -> new OrderNotFoundException(
                        "Order not found: " + orderId
                ));
    }
}
