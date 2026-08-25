package com.demo.order_service.service;

import com.demo.order_service.dto.CreateOrderRequest;
import com.demo.order_service.dto.OrderResponse;
import com.demo.order_service.exception.OrderNotFoundException;
import com.demo.order_service.mapper.OrderMapper;
import com.demo.order_service.models.Order;
import com.demo.order_service.models.OrderStatus;
import com.demo.order_service.repository.OrderRepository;
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

    private Order loadOrder(String orderId) {
        return orderRepository.findByOrderId(orderId)
                .orElseThrow(() -> new OrderNotFoundException(
                        "Order not found: " + orderId
                ));
    }
}
