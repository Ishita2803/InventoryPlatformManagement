package com.demo.order_service.outbox;

import com.demo.order_service.dto.CreateOrderRequest;
import com.demo.order_service.dto.OrderItemRequest;
import com.demo.order_service.dto.OrderResponse;
import com.demo.order_service.events.KafkaTopics;
import com.demo.order_service.models.OutboxEvent;
import com.demo.order_service.models.OutboxStatus;
import com.demo.order_service.repository.OrderRepository;
import com.demo.order_service.repository.OutboxEventRepository;
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
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbox, against a real broker.
 *
 * <p>The poller is left switched off (a one-hour interval, from the test config) and driven
 * by hand, so each test can assert the state <em>between</em> the commit and the publish —
 * which is precisely the window the outbox exists to make safe.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.listener.auto-startup=false"
})
@EmbeddedKafka(partitions = 1, topics = {KafkaTopics.ORDER_PLACED})
class OutboxIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private EmbeddedKafkaBroker broker;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Consumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        orderRepository.deleteAll();

        Map<String, Object> props = KafkaTestUtils.consumerProps(
                broker.getBrokersAsString(), "outbox-observer-" + UUID.randomUUID(), "true");
        consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<>(
                props,
                new org.apache.kafka.common.serialization.StringDeserializer(),
                new org.apache.kafka.common.serialization.StringDeserializer());
        broker.consumeFromEmbeddedTopics(consumer, KafkaTopics.ORDER_PLACED);
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @Test
    @DisplayName("placing an order writes the event to the outbox and publishes nothing yet")
    void orderAndEventCommitTogetherBeforeAnythingIsPublished() {

        OrderResponse order = orderService.createOrder(request());

        // The order exists...
        assertThat(orderRepository.findByOrderId(order.orderId())).isPresent();

        // ...and so does its event, in the same database, already committed.
        List<OutboxEvent> outbox = outboxEventRepository.findAll();
        assertThat(outbox).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(row.getTopic()).isEqualTo(KafkaTopics.ORDER_PLACED);
            assertThat(row.getAggregateId()).isEqualTo(order.orderId());
            assertThat(row.getAggregateType()).isEqualTo("Order");
            assertThat(row.getAttempts()).isZero();
            assertThat(row.getPublishedAt()).isNull();
        });

        // Nothing has reached Kafka. The commit did not depend on the broker at all.
        assertThat(pollFor(order.orderId(), Duration.ofSeconds(2)))
                .as("no event should be on the topic before the poller runs")
                .isNull();
    }

    @Test
    @DisplayName("draining the outbox publishes the event and marks the row PUBLISHED")
    void drainPublishesAndMarksTheRow() {

        OrderResponse order = orderService.createOrder(request());

        outboxPublisher.drainOutbox();

        JsonNode published = pollFor(order.orderId(), TIMEOUT);
        assertThat(published).isNotNull();
        assertThat(published.get("orderId").asText()).isEqualTo(order.orderId());
        assertThat(published.get("customerId").asText()).isEqualTo("CUST-1");
        assertThat(published.get("lines").get(0).get("productId").asLong()).isEqualTo(1L);

        OutboxEvent row = outboxEventRepository.findAll().getFirst();
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(row.getPublishedAt()).isNotNull();

        // The eventId in the row and in the payload are the same, so a message on the topic
        // can be traced back to its outbox row.
        assertThat(published.get("eventId").asText()).isEqualTo(row.getEventId());
    }

    @Test
    @DisplayName("draining twice does not publish twice")
    void drainIsSafeToRepeat() {

        String orderId = orderService.createOrder(request()).orderId();

        outboxPublisher.drainOutbox();
        outboxPublisher.drainOutbox();

        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isZero();
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PUBLISHED)).isEqualTo(1);

        // Exactly one message for THIS order. Counting every record on the topic would also
        // pick up whatever earlier tests in this class published.
        assertThat(countMessagesFor(orderId)).isEqualTo(1);
    }

    @Test
    @DisplayName("events publish oldest-first, so one order's events stay in order")
    void publishesInCreationOrder() {

        OrderResponse first = orderService.createOrder(request());
        OrderResponse second = orderService.createOrder(request());

        outboxPublisher.drainOutbox();

        List<String> orderIdsInOrder = drainTopicOrderIds().stream()
                .filter(id -> id.equals(first.orderId()) || id.equals(second.orderId()))
                .toList();
        assertThat(orderIdsInOrder).containsExactly(first.orderId(), second.orderId());
    }

    private JsonNode pollFor(String orderId, Duration timeout) {

        long deadline = System.currentTimeMillis() + timeout.toMillis();

        while (System.currentTimeMillis() < deadline) {
            var records = consumer.poll(Duration.ofMillis(300));
            for (ConsumerRecord<String, String> record : records) {
                JsonNode node = read(record.value());
                if (orderId.equals(node.path("orderId").asText())) {
                    return node;
                }
            }
        }
        return null;
    }

    private int countMessagesFor(String orderId) {
        int seen = 0;
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
                if (orderId.equals(read(record.value()).path("orderId").asText())) {
                    seen++;
                }
            }
        }
        return seen;
    }

    private List<String> drainTopicOrderIds() {
        List<String> ids = new java.util.ArrayList<>();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            var records = consumer.poll(Duration.ofMillis(300));
            records.forEach(r -> ids.add(read(r.value()).path("orderId").asText()));
            if (ids.size() >= 2) {
                break;
            }
        }
        return ids;
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception failure) {
            throw new AssertionError("Unparseable payload: " + json, failure);
        }
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
