package com.demo.order_service.dto;

import com.demo.order_service.models.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Note what is absent: the internal {@code id}. Clients only ever see {@code orderId}, the
 * UUID, so the surrogate key stays an implementation detail this service is free to change.
 */
public record OrderResponse(
        String orderId,
        String customerId,
        OrderStatus status,
        BigDecimal totalAmount,
        List<OrderItemResponse> items,
        Instant createdAt,
        Instant updatedAt,
        String deliveryRegion,
        String carrierCode
) {
}
