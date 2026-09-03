package com.demo.order_service.service;

import com.demo.order_service.dto.CreateOrderRequest;
import com.demo.order_service.dto.OrderItemResponse;
import com.demo.order_service.dto.OrderResponse;
import com.demo.order_service.models.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ordering contract between committing an order and announcing it.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderTxService tx;

    @InjectMocks
    private OrderService orderService;

    @Test
    @DisplayName("createOrder simply delegates: publishing is the outbox's job now")
    void delegatesToTheTransactionalService() {

        OrderResponse saved = sampleResponse();
        when(tx.create(any())).thenReturn(saved);

        orderService.createOrder(new CreateOrderRequest());

        // Phase 5 removed the commit-then-publish dance from here entirely. The event is
        // written to the outbox inside tx.create(), so there is nothing to sequence.
        verify(tx).create(any());
    }

    @Test
    @DisplayName("a persistence failure propagates rather than being swallowed")
    void persistFailurePropagates() {

        when(tx.create(any())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> orderService.createOrder(new CreateOrderRequest()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the caller gets the persisted order back")
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
                        1L, "WH-1", null, 2, new BigDecimal("10.50"), new BigDecimal("21.00"), null)),
                Instant.parse("2026-08-26T10:00:00Z"),
                Instant.parse("2026-08-26T10:00:00Z"),
                null,
                null,
                null
        );
    }
}
