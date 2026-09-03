package com.demo.order_service.mapper;

import com.demo.order_service.dto.CreateOrderRequest;
import com.demo.order_service.dto.OrderItemRequest;
import com.demo.order_service.dto.OrderItemResponse;
import com.demo.order_service.dto.OrderResponse;
import com.demo.order_service.models.Order;
import com.demo.order_service.models.OrderItem;
import com.demo.order_service.models.OrderStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Entity/DTO translation, hand-written rather than generated.
 *
 * <p>MapStruct or ModelMapper would save perhaps twenty lines here and cost either an
 * annotation processor or runtime reflection, plus a mapping layer nobody can set a
 * breakpoint in. At this size, explicit wins.
 */
@Component
public class OrderMapper {

    /**
     * Builds a new order in {@link OrderStatus#PENDING}.
     *
     * <p>The UUID is minted here, server-side. Letting the client supply it would make the
     * cross-service identifier attacker-controlled and allow one client to collide with
     * another's reservations.
     */
    public Order toNewOrder(CreateOrderRequest request) {

        Order order = new Order();
        order.setOrderId(UUID.randomUUID().toString());
        order.setCustomerId(request.getCustomerId());
        order.setStatus(OrderStatus.PENDING);

        for (OrderItemRequest itemRequest : request.getItems()) {
            order.addItem(toItem(itemRequest));
        }

        order.setTotalAmount(totalOf(order));

        return order;
    }

    public OrderResponse toResponse(Order order) {

        List<OrderItemResponse> items = order.getItems().stream()
                .map(this::toItemResponse)
                .toList();

        return new OrderResponse(
                order.getOrderId(),
                order.getCustomerId(),
                order.getStatus(),
                order.getTotalAmount(),
                items,
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getDeliveryRegion(),
                order.getCarrierCode()
        );
    }

    private OrderItem toItem(OrderItemRequest request) {

        OrderItem item = new OrderItem();
        item.setProductId(request.getProductId());
        item.setWarehouseId(request.getWarehouseId());
        item.setQuantity(request.getQuantity());
        item.setUnitPrice(request.getUnitPrice());

        return item;
    }

    private OrderItemResponse toItemResponse(OrderItem item) {
        return new OrderItemResponse(
                item.getProductId(),
                item.getWarehouseId(),
                item.getSkuNumber(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.lineTotal(),
                item.getUnitWeight()
        );
    }

    /**
     * Sums the lines with {@link BigDecimal}, never double. Money in floating point
     * accumulates rounding error, and an order total that is off by a cent is the kind of
     * bug that surfaces in production reconciliation rather than in tests.
     */
    private BigDecimal totalOf(Order order) {
        return order.getItems().stream()
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
