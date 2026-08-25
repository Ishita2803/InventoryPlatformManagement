package com.demo.order_service.service;

import com.demo.order_service.dto.CreateOrderRequest;
import com.demo.order_service.dto.OrderResponse;
import com.demo.order_service.exception.OrderNotFoundException;
import com.demo.order_service.mapper.OrderMapper;
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

    @Transactional
    public OrderResponse create(CreateOrderRequest request) {

        Order order = orderMapper.toNewOrder(request);
        Order saved = orderRepository.save(order);

        log.info("Created order {} for customer {} with {} item(s), total {}",
                saved.getOrderId(), saved.getCustomerId(),
                saved.getItems().size(), saved.getTotalAmount());

        return orderMapper.toResponse(saved);
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

    private Order loadOrder(String orderId) {
        return orderRepository.findByOrderId(orderId)
                .orElseThrow(() -> new OrderNotFoundException(
                        "Order not found: " + orderId
                ));
    }
}
