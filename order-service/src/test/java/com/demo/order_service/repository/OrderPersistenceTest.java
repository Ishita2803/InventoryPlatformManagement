package com.demo.order_service.repository;

import com.demo.order_service.models.Order;
import com.demo.order_service.models.OrderItem;
import com.demo.order_service.models.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mapping-level checks against a real database.
 *
 * <p>These catch the failures unit tests structurally cannot: a cascade that does not
 * cascade, a constraint that was never created, a money column that silently truncates.
 * They are also why the {@code orders} table name is not left to Hibernate -- {@code ORDER}
 * is reserved in SQL, and this test would be the first thing to fail if that regressed.
 */
@DataJpaTest
class OrderPersistenceTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    @DisplayName("saving an order cascades its items, and findByOrderId reads them back")
    void cascadePersistsItems() {

        Order order = orderWith("CUST-1", 2);
        String orderId = order.getOrderId();

        orderRepository.save(order);
        entityManager.flush();
        entityManager.clear();   // force a genuine read rather than a first-level cache hit

        Optional<Order> reloaded = orderRepository.findByOrderId(orderId);

        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getItems()).hasSize(2);
        assertThat(reloaded.get().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(reloaded.get().getItems())
                .allSatisfy(item -> assertThat(item.getOrder().getOrderId()).isEqualTo(orderId));
    }

    @Test
    @DisplayName("orderId is unique: two orders cannot share a cross-service identifier")
    void orderIdIsUnique() {

        String duplicate = UUID.randomUUID().toString();

        Order first = orderWith("CUST-1", 1);
        first.setOrderId(duplicate);
        orderRepository.save(first);
        entityManager.flush();

        Order second = orderWith("CUST-2", 1);
        second.setOrderId(duplicate);

        assertThatThrownBy(() -> {
            orderRepository.save(second);
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("money round-trips at two decimal places without truncation")
    void moneyRoundTripsExactly() {

        Order order = orderWith("CUST-1", 1);
        order.getItems().getFirst().setUnitPrice(new BigDecimal("19.99"));
        order.setTotalAmount(new BigDecimal("19.99"));
        String orderId = order.getOrderId();

        orderRepository.save(order);
        entityManager.flush();
        entityManager.clear();

        Order reloaded = orderRepository.findByOrderId(orderId).orElseThrow();

        assertThat(reloaded.getTotalAmount()).isEqualByComparingTo(new BigDecimal("19.99"));
        assertThat(reloaded.getItems().getFirst().getUnitPrice())
                .isEqualByComparingTo(new BigDecimal("19.99"));
    }

    @Test
    @DisplayName("timestamps are populated by the lifecycle callbacks")
    void timestampsArePopulated() {

        Order order = orderWith("CUST-1", 1);

        orderRepository.save(order);
        entityManager.flush();

        assertThat(order.getCreatedAt()).isNotNull();
        assertThat(order.getUpdatedAt()).isNotNull();
    }

    private Order orderWith(String customerId, int itemCount) {

        Order order = new Order();
        order.setOrderId(UUID.randomUUID().toString());
        order.setCustomerId(customerId);
        order.setStatus(OrderStatus.PENDING);
        order.setTotalAmount(new BigDecimal("10.00"));

        for (int i = 0; i < itemCount; i++) {
            OrderItem item = new OrderItem();
            item.setProductId((long) (i + 1));
            item.setWarehouseId("WH-1");
            item.setQuantity(1);
            item.setUnitPrice(new BigDecimal("10.00"));
            order.addItem(item);
        }

        return order;
    }
}
