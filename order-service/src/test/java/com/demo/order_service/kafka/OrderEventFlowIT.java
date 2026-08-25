package com.demo.order_service.kafka;

import com.demo.order_service.dto.CreateOrderRequest;
import com.demo.order_service.dto.OrderItemRequest;
import com.demo.order_service.dto.OrderResponse;
import com.demo.order_service.events.InventoryFailedEvent;
import com.demo.order_service.events.InventoryReservedEvent;
import com.demo.order_service.events.KafkaTopics;
import com.demo.order_service.models.OrderStatus;
import com.demo.order_service.repository.OrderRepository;
import com.demo.order_service.service.OrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Order-service's half of the async flow, against a real broker.
 *
 * <p>Two directions are covered: that placing an order actually puts an {@code OrderPlaced}
 * on the wire, and that inventory's answer moves the order on. inventory-service is stood in
 * for by a plain producer, since the two services are separate Maven modules.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.listener.auto-startup=true"
})
@EmbeddedKafka(
        partitions = 1,
        topics = {KafkaTopics.ORDER_PLACED, KafkaTopics.INVENTORY_RESERVED, KafkaTopics.INVENTORY_FAILED}
)
class OrderEventFlowIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker broker;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Consumer<String, String> orderPlacedConsumer;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                broker.getBrokersAsString(), "test-observer-" + UUID.randomUUID(), "true");
        orderPlacedConsumer = new org.apache.kafka.clients.consumer.KafkaConsumer<>(
                consumerProps,
                new org.apache.kafka.common.serialization.StringDeserializer(),
                new org.apache.kafka.common.serialization.StringDeserializer());
        broker.consumeFromEmbeddedTopics(orderPlacedConsumer, KafkaTopics.ORDER_PLACED);
    }

    @AfterEach
    void tearDown() {
        if (orderPlacedConsumer != null) {
            orderPlacedConsumer.close();
        }
    }

    @Test
    @DisplayName("placing an order publishes OrderPlaced carrying everything inventory needs")
    void placingAnOrderPublishesTheEvent() {

        OrderResponse order = orderService.createOrder(request());

        JsonNode event = awaitOrderPlaced(order.orderId());

        assertThat(event.get("orderId").asText()).isEqualTo(order.orderId());
        assertThat(event.get("customerId").asText()).isEqualTo("CUST-1");
        assertThat(event.get("eventId").asText()).isNotBlank();

        // Without productId, warehouseId and quantity the consumer cannot reserve anything.
        JsonNode line = event.get("lines").get(0);
        assertThat(line.get("productId").asLong()).isEqualTo(1L);
        assertThat(line.get("warehouseId").asText()).isEqualTo("WH-1");
        assertThat(line.get("quantity").asInt()).isEqualTo(2);

        // No Java type header on the wire: the contract is plain JSON, not a class name.
        assertThat(event.has("@class")).isFalse();
    }

    @Test
    @DisplayName("InventoryReserved moves the order out of PENDING")
    void inventoryReservedAdvancesTheOrder() {

        OrderResponse order = orderService.createOrder(request());
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);

        kafkaTemplate.send(KafkaTopics.INVENTORY_RESERVED, order.orderId(),
                new InventoryReservedEvent(UUID.randomUUID().toString(), order.orderId(), Instant.now()));

        awaitStatus(order.orderId(), OrderStatus.INVENTORY_RESERVED);
    }

    @Test
    @DisplayName("InventoryFailed fails the order")
    void inventoryFailedFailsTheOrder() {

        OrderResponse order = orderService.createOrder(request());

        kafkaTemplate.send(KafkaTopics.INVENTORY_FAILED, order.orderId(),
                new InventoryFailedEvent(UUID.randomUUID().toString(), order.orderId(),
                        "Insufficient inventory. Available=0, requested=2", Instant.now()));

        awaitStatus(order.orderId(), OrderStatus.INVENTORY_FAILED);
    }

    @Test
    @DisplayName("a redelivered InventoryReserved is tolerated, not treated as a failure")
    void redeliveredResultIsTolerated() {

        OrderResponse order = orderService.createOrder(request());

        InventoryReservedEvent event = new InventoryReservedEvent(
                UUID.randomUUID().toString(), order.orderId(), Instant.now());

        kafkaTemplate.send(KafkaTopics.INVENTORY_RESERVED, order.orderId(), event);
        kafkaTemplate.send(KafkaTopics.INVENTORY_RESERVED, order.orderId(), event);

        awaitStatus(order.orderId(), OrderStatus.INVENTORY_RESERVED);

        // The second delivery hits the lifecycle guard, which the listener swallows. If it
        // rethrew instead, the container would retry forever and stall the partition.
        await().during(Duration.ofSeconds(2)).atMost(TIMEOUT).untilAsserted(() ->
                assertThat(orderRepository.findByOrderId(order.orderId()).orElseThrow().getStatus())
                        .isEqualTo(OrderStatus.INVENTORY_RESERVED));
    }

    private void awaitStatus(String orderId, OrderStatus expected) {
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(orderRepository.findByOrderId(orderId).orElseThrow().getStatus())
                        .isEqualTo(expected));
    }

    private JsonNode awaitOrderPlaced(String orderId) {

        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();

        while (System.currentTimeMillis() < deadline) {
            var records = orderPlacedConsumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                try {
                    JsonNode node = objectMapper.readTree(record.value());
                    if (orderId.equals(node.path("orderId").asText())) {
                        // Keyed by orderId so everything about one order stays ordered.
                        assertThat(record.key()).isEqualTo(orderId);
                        return node;
                    }
                } catch (Exception parseFailure) {
                    throw new AssertionError("Unparseable event: " + record.value(), parseFailure);
                }
            }
        }

        throw new AssertionError("No OrderPlaced published for " + orderId + " within " + TIMEOUT);
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
