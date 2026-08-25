package com.demo.inventory_service.kafka;

import com.demo.inventory_service.events.KafkaTopics;
import com.demo.inventory_service.events.OrderPlacedEvent;
import com.demo.inventory_service.models.Inventory;
import com.demo.inventory_service.models.Product;
import com.demo.inventory_service.models.Reservation;
import com.demo.inventory_service.models.ReservationStatus;
import com.demo.inventory_service.repository.InventoryRepository;
import com.demo.inventory_service.repository.ProductRepository;
import com.demo.inventory_service.repository.ReservationRepository;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Inventory's half of the async flow, against a real broker.
 *
 * <p>Everything here is genuine: a real Kafka broker (embedded, KRaft), real serialization
 * over the wire, the real listener container, and a real database. The only thing simulated
 * is order-service, which is replaced by a plain producer — the two services live in
 * separate Maven modules, so no single test can host both. order-service's own IT covers
 * the other half.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.listener.auto-startup=true"
})
@EmbeddedKafka(
        partitions = 1,
        topics = {KafkaTopics.ORDER_PLACED, KafkaTopics.INVENTORY_RESERVED, KafkaTopics.INVENTORY_FAILED}
)
class OrderPlacedListenerIT {

    private static final String WAREHOUSE_ID = "WH-1";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Long productId;
    private Consumer<String, String> resultConsumer;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();
        inventoryRepository.deleteAll();
        productRepository.deleteAll();

        Product product = new Product();
        product.setSku("SKU-" + UUID.randomUUID());
        product.setName("Widget");
        productId = productRepository.save(product).getId();

        Inventory inventory = new Inventory();
        inventory.setProductId(productId);
        inventory.setWarehouseId(WAREHOUSE_ID);
        inventory.setAvailableQuantity(10);
        inventory.setReservedQuantity(0);
        inventoryRepository.save(inventory);

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                broker.getBrokersAsString(), "test-observer-" + UUID.randomUUID(), "true");
        resultConsumer = new org.apache.kafka.clients.consumer.KafkaConsumer<>(
                consumerProps,
                new org.apache.kafka.common.serialization.StringDeserializer(),
                new org.apache.kafka.common.serialization.StringDeserializer());
        broker.consumeFromEmbeddedTopics(
                resultConsumer, KafkaTopics.INVENTORY_RESERVED, KafkaTopics.INVENTORY_FAILED);
    }

    @AfterEach
    void tearDown() {
        if (resultConsumer != null) {
            resultConsumer.close();
        }
    }

    @Test
    @DisplayName("an OrderPlaced event reserves stock and answers with InventoryReserved")
    void reservesStockAndPublishesReserved() {

        String orderId = UUID.randomUUID().toString();

        kafkaTemplate.send(KafkaTopics.ORDER_PLACED, orderId, orderPlaced(orderId, 3));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            Inventory current = inventoryRepository
                    .findByProductIdAndWarehouseId(productId, WAREHOUSE_ID).orElseThrow();
            assertThat(current.getAvailableQuantity()).isEqualTo(7);
            assertThat(current.getReservedQuantity()).isEqualTo(3);
        });

        List<Reservation> reservations = reservationRepository.findByOrderId(orderId);
        assertThat(reservations).singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED));

        JsonNode result = awaitEventFor(orderId);
        assertThat(result.get("topic").asText()).isEqualTo(KafkaTopics.INVENTORY_RESERVED);
        assertThat(result.get("orderId").asText()).isEqualTo(orderId);
        assertThat(result.get("eventId").asText()).isNotBlank();
    }

    @Test
    @DisplayName("insufficient stock answers with InventoryFailed and reserves nothing")
    void publishesFailedWhenOutOfStock() {

        String orderId = UUID.randomUUID().toString();

        kafkaTemplate.send(KafkaTopics.ORDER_PLACED, orderId, orderPlaced(orderId, 99));

        JsonNode result = awaitEventFor(orderId);
        assertThat(result.get("topic").asText()).isEqualTo(KafkaTopics.INVENTORY_FAILED);
        assertThat(result.get("reason").asText()).contains("Insufficient inventory");

        Inventory current = inventoryRepository
                .findByProductIdAndWarehouseId(productId, WAREHOUSE_ID).orElseThrow();
        assertThat(current.getAvailableQuantity()).isEqualTo(10);
        assertThat(current.getReservedQuantity()).isZero();
    }

    @Test
    @DisplayName("a partly-fulfillable order reserves nothing: the good line is released again")
    void releasesEarlierLinesWhenALaterOneFails() {

        // Second product, deliberately understocked.
        Product scarce = new Product();
        scarce.setSku("SKU-" + UUID.randomUUID());
        scarce.setName("Scarce");
        Long scarceId = productRepository.save(scarce).getId();

        Inventory scarceStock = new Inventory();
        scarceStock.setProductId(scarceId);
        scarceStock.setWarehouseId(WAREHOUSE_ID);
        scarceStock.setAvailableQuantity(1);
        scarceStock.setReservedQuantity(0);
        inventoryRepository.save(scarceStock);

        String orderId = UUID.randomUUID().toString();

        OrderPlacedEvent event = new OrderPlacedEvent(
                UUID.randomUUID().toString(), orderId, "CUST-1",
                List.of(
                        new OrderPlacedEvent.Line(productId, WAREHOUSE_ID, 2),   // succeeds
                        new OrderPlacedEvent.Line(scarceId, WAREHOUSE_ID, 5)     // fails
                ),
                Instant.now());

        kafkaTemplate.send(KafkaTopics.ORDER_PLACED, orderId, event);

        JsonNode result = awaitEventFor(orderId);
        assertThat(result.get("topic").asText()).isEqualTo(KafkaTopics.INVENTORY_FAILED);

        // The whole point: the first line was reserved, then handed back. Leaking it would
        // make the stock permanently unavailable for an order that never happened.
        Inventory first = inventoryRepository
                .findByProductIdAndWarehouseId(productId, WAREHOUSE_ID).orElseThrow();
        assertThat(first.getAvailableQuantity()).isEqualTo(10);
        assertThat(first.getReservedQuantity()).isZero();

        assertThat(reservationRepository.findByOrderId(orderId))
                .allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(ReservationStatus.RELEASED));
    }

    @Test
    @DisplayName("the same OrderPlaced delivered twice reserves stock exactly once")
    void redeliveryReservesOnce() {

        String orderId = UUID.randomUUID().toString();
        OrderPlacedEvent event = orderPlaced(orderId, 4);

        kafkaTemplate.send(KafkaTopics.ORDER_PLACED, orderId, event);
        kafkaTemplate.send(KafkaTopics.ORDER_PLACED, orderId, event);   // at-least-once

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(reservationRepository.findByOrderId(orderId)).hasSize(1));

        // Stock moved once, not twice, despite two deliveries of the same event.
        Inventory current = inventoryRepository
                .findByProductIdAndWarehouseId(productId, WAREHOUSE_ID).orElseThrow();
        assertThat(current.getAvailableQuantity()).isEqualTo(6);
        assertThat(current.getReservedQuantity()).isEqualTo(4);
    }

    private OrderPlacedEvent orderPlaced(String orderId, int quantity) {
        return new OrderPlacedEvent(
                UUID.randomUUID().toString(),
                orderId,
                "CUST-1",
                List.of(new OrderPlacedEvent.Line(productId, WAREHOUSE_ID, quantity)),
                Instant.now());
    }

    /**
     * Polls the two result topics until an event for {@code orderId} shows up, returning it
     * with the topic name folded in so assertions can check which topic it arrived on.
     */
    private JsonNode awaitEventFor(String orderId) {

        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();

        while (System.currentTimeMillis() < deadline) {
            var records = resultConsumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                try {
                    JsonNode node = objectMapper.readTree(record.value());
                    if (orderId.equals(node.path("orderId").asText())) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) node)
                                .put("topic", record.topic());
                        return node;
                    }
                } catch (Exception parseFailure) {
                    throw new AssertionError("Unparseable event: " + record.value(), parseFailure);
                }
            }
        }

        throw new AssertionError("No inventory result published for order " + orderId
                + " within " + TIMEOUT);
    }
}
