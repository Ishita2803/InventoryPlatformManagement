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

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;

    /**
     * Persists a new order as {@link OrderStatus#PENDING}.
     *
     * <p>Deliberately makes <em>no</em> call to inventory-service. Checking stock
     * synchronously here would put an order's acceptance at the mercy of another service
     * being up, which is exactly the coupling the event-driven design exists to remove.
     * Phase 3 publishes {@code OrderPlaced} and lets inventory answer asynchronously; until
     * then, PENDING is simply where orders stop.
     */
    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {

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

    /**
     * Moves an order through its lifecycle, refusing illegal transitions.
     *
     * <p>Not reachable over HTTP yet -- nothing should be able to force an order into
     * CONFIRMED from outside. It exists for the Kafka consumers in Phase 3, which is why it
     * is tested now rather than written later.
     */
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
