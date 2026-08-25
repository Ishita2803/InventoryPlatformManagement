package com.demo.order_service.service;

import com.demo.order_service.dto.CreateOrderRequest;
import com.demo.order_service.dto.OrderItemResponse;
import com.demo.order_service.dto.OrderResponse;
import com.demo.order_service.kafka.OrderEventPublisher;
import com.demo.order_service.models.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ordering contract between committing an order and announcing it.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderTxService tx;

    @Mock
    private OrderEventPublisher publisher;

    @InjectMocks
    private OrderService orderService;

    @Test
    @DisplayName("the order is committed BEFORE it is published, never the other way round")
    void commitHappensBeforePublish() {

        OrderResponse saved = sampleResponse();
        when(tx.create(any())).thenReturn(saved);

        orderService.createOrder(new CreateOrderRequest());

        // Publishing first would let inventory reserve stock for an order that then rolls
        // back and never exists.
        InOrder sequence = inOrder(tx, publisher);
        sequence.verify(tx).create(any());
        sequence.verify(publisher).publishOrderPlaced(saved);
    }

    @Test
    @DisplayName("nothing is published if the order fails to persist")
    void noEventWhenPersistFails() {

        when(tx.create(any())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> orderService.createOrder(new CreateOrderRequest()))
                .isInstanceOf(IllegalStateException.class);

        verify(publisher, never()).publishOrderPlaced(any());
    }

    @Test
    @DisplayName("the caller gets the persisted order back, not the publish result")
    void returnsThePersistedOrder() {

        OrderResponse saved = sampleResponse();
        when(tx.create(any())).thenReturn(saved);

        OrderResponse returned = orderService.createOrder(new CreateOrderRequest());

        assertThat(returned).isSameAs(saved);
        assertThat(returned.status()).isEqualTo(OrderStatus.PENDING);
    }

    private OrderResponse sampleResponse() {
        return new OrderResponse(
                "44444444-4444-4444-4444-444444444444",
                "CUST-1",
                OrderStatus.PENDING,
                new BigDecimal("21.00"),
                List.of(new OrderItemResponse(
                        1L, "WH-1", 2, new BigDecimal("10.50"), new BigDecimal("21.00"))),
                Instant.parse("2026-08-26T10:00:00Z"),
                Instant.parse("2026-08-26T10:00:00Z")
        );
    }
}
