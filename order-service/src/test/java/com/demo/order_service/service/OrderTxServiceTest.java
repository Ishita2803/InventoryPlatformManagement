package com.demo.order_service.service;

import com.demo.order_service.dto.CreateOrderRequest;
import com.demo.order_service.dto.OrderItemRequest;
import com.demo.order_service.dto.OrderResponse;
import com.demo.order_service.exception.InvalidOrderStateTransitionException;
import com.demo.order_service.exception.OrderNotFoundException;
import com.demo.order_service.mapper.OrderMapper;
import com.demo.order_service.models.Order;
import com.demo.order_service.models.OrderItem;
import com.demo.order_service.models.OrderStatus;
import com.demo.order_service.models.ProcessedEvent;
import com.demo.order_service.repository.OrderRepository;
import com.demo.order_service.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderTxServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    private OrderTxService orderTxService;

    @BeforeEach
    void setUp() {
        // The real mapper, not a mock: it owns the UUID minting and the money arithmetic,
        // which are exactly the behaviours these tests are about.
        orderTxService = new OrderTxService(orderRepository, new OrderMapper(), processedEventRepository);
    }

    @Test
    @DisplayName("a new order is persisted as PENDING with a server-minted UUID")
    void createOrderPersistsPending() {

        when(orderRepository.save(any(Order.class))).thenAnswer(call -> call.getArgument(0));

        OrderResponse response = orderTxService.create(request());

        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.customerId()).isEqualTo("CUST-1");
        assertThat(response.items()).hasSize(2);

        // Server-minted, and a real UUID rather than anything client-supplied.
        assertThat(response.orderId()).isNotBlank();
        assertThat(UUID.fromString(response.orderId())).isNotNull();
    }

    @Test
    @DisplayName("the order total is the sum of the line totals, in BigDecimal")
    void createOrderComputesTotal() {

        when(orderRepository.save(any(Order.class))).thenAnswer(call -> call.getArgument(0));

        OrderResponse response = orderTxService.create(request());

        // (2 x 10.50) + (1 x 5.25)
        assertThat(response.totalAmount()).isEqualByComparingTo(new BigDecimal("26.25"));
        assertThat(response.items().getFirst().lineTotal())
                .isEqualByComparingTo(new BigDecimal("21.00"));
    }

    @Test
    @DisplayName("both sides of the order/item association are wired, so items are not orphaned")
    void createOrderLinksItemsBackToTheOrder() {

        when(orderRepository.save(any(Order.class))).thenAnswer(call -> call.getArgument(0));

        orderTxService.create(request());

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());

        Order saved = captor.getValue();
        assertThat(saved.getItems())
                .allSatisfy(item -> assertThat(item.getOrder()).isSameAs(saved));
    }

    @Test
    @DisplayName("fetching an unknown order fails cleanly with OrderNotFound")
    void getUnknownOrderFailsCleanly() {

        when(orderRepository.findByOrderId("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderTxService.getOrder("missing"))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining("missing");
    }

    @Test
    @DisplayName("a legal transition moves the order")
    void legalTransitionSucceeds() {

        Order order = existingOrder(OrderStatus.PENDING);
        when(orderRepository.findByOrderId(order.getOrderId())).thenReturn(Optional.of(order));

        OrderResponse response =
                orderTxService.transitionOrder(order.getOrderId(), OrderStatus.INVENTORY_RESERVED);

        assertThat(response.status()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
    }

    @Test
    @DisplayName("a replayed event cannot drag a terminal order back to life")
    void illegalTransitionIsRejected() {

        Order order = existingOrder(OrderStatus.CANCELLED);
        when(orderRepository.findByOrderId(order.getOrderId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() ->
                orderTxService.transitionOrder(order.getOrderId(), OrderStatus.INVENTORY_RESERVED))
                .isInstanceOf(InvalidOrderStateTransitionException.class)
                .hasMessageContaining("CANCELLED");

        assertThat(order.getStatus())
                .as("a rejected transition must leave the order untouched")
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("an inventory result is applied once and the event recorded in the same go")
    void inventoryResultIsAppliedAndRecorded() {

        Order order = existingOrder(OrderStatus.PENDING);
        when(processedEventRepository.existsById("evt-1")).thenReturn(false);
        when(orderRepository.findByOrderId(order.getOrderId())).thenReturn(Optional.of(order));

        boolean applied = orderTxService.applyInventoryResult(
                "evt-1", "InventoryReserved", order.getOrderId(), OrderStatus.INVENTORY_RESERVED);

        assertThat(applied).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("a replayed event is skipped: the order is not touched a second time")
    void replayedEventIsSkipped() {

        when(processedEventRepository.existsById("evt-1")).thenReturn(true);

        boolean applied = orderTxService.applyInventoryResult(
                "evt-1", "InventoryReserved", "any-order", OrderStatus.INVENTORY_RESERVED);

        assertThat(applied).isFalse();
        // Never even loads the order, let alone changes it.
        verify(orderRepository, never()).findByOrderId(anyString());
        verify(processedEventRepository, never()).save(any(ProcessedEvent.class));
    }

    private CreateOrderRequest request() {

        OrderItemRequest first = new OrderItemRequest();
        first.setProductId(1L);
        first.setWarehouseId("WH-1");
        first.setQuantity(2);
        first.setUnitPrice(new BigDecimal("10.50"));

        OrderItemRequest second = new OrderItemRequest();
        second.setProductId(2L);
        second.setWarehouseId("WH-1");
        second.setQuantity(1);
        second.setUnitPrice(new BigDecimal("5.25"));

        CreateOrderRequest request = new CreateOrderRequest();
        request.setCustomerId("CUST-1");
        request.setItems(List.of(first, second));
        return request;
    }

    private Order existingOrder(OrderStatus status) {

        Order order = new Order();
        order.setOrderId(UUID.randomUUID().toString());
        order.setCustomerId("CUST-1");
        order.setStatus(status);
        order.setTotalAmount(new BigDecimal("10.00"));

        OrderItem item = new OrderItem();
        item.setProductId(1L);
        item.setWarehouseId("WH-1");
        item.setQuantity(1);
        item.setUnitPrice(new BigDecimal("10.00"));
        order.addItem(item);

        return order;
    }
}
