package com.demo.order_service.outbox;

import com.demo.order_service.dto.CreateOrderRequest;
import com.demo.order_service.dto.OrderItemRequest;
import com.demo.order_service.dto.OrderResponse;
import com.demo.order_service.models.OrderStatus;
import com.demo.order_service.models.OutboxEvent;
import com.demo.order_service.models.OutboxStatus;
import com.demo.order_service.repository.OrderRepository;
import com.demo.order_service.repository.OutboxEventRepository;
import com.demo.order_service.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5's exit criterion, with no broker at all.
 *
 * <p>There is deliberately no {@code @EmbeddedKafka} here: the bootstrap address points at a
 * port with nothing behind it. This is the scenario the outbox exists for — before it, an
 * unreachable broker meant the order committed and its event vanished, leaving an order
 * stuck at PENDING that no consumer would ever hear about.
 *
 * <p>Producer timeouts are squeezed right down so a failed send takes a second rather than
 * the two minutes Kafka defaults to.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.producer.properties.max.block.ms=1000",
        "spring.kafka.producer.properties.request.timeout.ms=1000",
        "spring.kafka.producer.properties.delivery.timeout.ms=2000",
        "outbox.send-timeout-seconds=5",
        "outbox.max-attempts=3"
})
class OutboxBrokerDownIT {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    @DisplayName("with Kafka down the order still commits, and its event waits in the outbox")
    void orderSurvivesABrokerOutage() {

        // The whole point: this does not throw, does not hang, and does not need a broker.
        OrderResponse order = orderService.createOrder(request());

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);

        assertThat(orderRepository.findByOrderId(order.orderId()))
                .as("the order must be durably committed even with no broker")
                .isPresent();

        assertThat(outboxEventRepository.findAll())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
                    assertThat(row.getAggregateId()).isEqualTo(order.orderId());
                });
    }

    @Test
    @DisplayName("a failed drain records the attempt and leaves the event PENDING to retry")
    void failedDrainKeepsTheEventForLater() {

        OrderResponse order = orderService.createOrder(request());

        outboxPublisher.drainOutbox();

        OutboxEvent row = outboxEventRepository.findAll().getFirst();

        assertThat(row.getStatus())
                .as("still pending: the event is not lost just because the broker is down")
                .isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).isNotBlank();
        assertThat(row.getPublishedAt()).isNull();

        // And the order is untouched by the failure.
        assertThat(orderRepository.findByOrderId(order.orderId())).isPresent();
    }

    @Test
    @DisplayName("after the attempt budget runs out the event is quarantined, not retried forever")
    void exhaustedAttemptsMarkTheEventFailed() {

        orderService.createOrder(request());

        // max-attempts is 3 for this test.
        outboxPublisher.drainOutbox();
        outboxPublisher.drainOutbox();
        outboxPublisher.drainOutbox();

        OutboxEvent row = outboxEventRepository.findAll().getFirst();
        assertThat(row.getAttempts()).isEqualTo(3);
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.FAILED);

        // FAILED is terminal, so a further drain ignores it entirely -- otherwise one
        // undeliverable row would be retried on every poll forever and delay everything
        // queued behind it.
        outboxPublisher.drainOutbox();
        assertThat(outboxEventRepository.findAll().getFirst().getAttempts())
                .as("a quarantined event must not keep consuming attempts")
                .isEqualTo(3);
    }

    private CreateOrderRequest request() {

        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(1L);
        item.setWarehouseId("WH-1");
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("10.50"));

        CreateOrderRequest request = new CreateOrderRequest();
        request.setCustomerId("CUST-1");
        request.setItems(List.of(item));
        return request;
    }
}
